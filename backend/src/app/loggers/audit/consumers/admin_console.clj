;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.admin-console
  "The admin console transport of the audit log consumer.

  It reads the stream from its own cursor and ingests the allowlisted
  events, enriched with the name of their team and the emails of the
  profiles they mention (the admin console shows people, not ids). The
  events outside the allowlist, and the telemetry shadows, count as
  skipped for the cursor: they are passed over and never reach the
  destination.

  The allowlist lives here: it is this consumer's contract, not the
  producer's (the producer only inserts rows). The mechanics (the
  cursor, the lock, the GC) live in `app.loggers.audit.consumers`."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit :as audit]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [app.nitrate :as nitrate]
   [integrant.core :as ig]))

(def event-names-for-admin-console
  "The event names the admin console archives. Each consumer owns its
  filter now: the producer only inserts the row, and this consumer
  decides on read which events concern it."
  #{"accept-organization-invitation"
    "accept-organization-invitation-from"
    "accept-team-invitation"
    "accept-team-invitation-from"
    "add-member-to-organization"
    "add-team-to-organization"
    "assign-organization-attribute"
    "auditlog-exported"
    "auditlog-guide-downloaded"
    "cancel-organization-invitation"
    "create-export-binfile-job"
    "create-file"
    "create-organization"
    "create-organization-attribute"
    "create-organization-invitation"
    "create-project"
    "create-team"
    "create-team-access-request"
    "create-team-invitation"
    "create-team-invitations"
    "create-webhook"
    "delete-file"
    "delete-font"
    "delete-organization"
    "delete-organization-member"
    "delete-project"
    "delete-team"
    "delete-team-invitation"
    "delete-team-member"
    "leave-team"
    "move-project"
    "move-team-to-organization"
    "organization-sso-auth-failed"
    "organization-sso-auth-started"
    "organization-sso-auth-succeeded"
    "organization-sso-token-issued"
    "organization-sso-token-revoked"
    "permanently-delete-team-files"
    "remove-organization-team"
    "remove-team-from-organization"
    "rename-organization"
    "rename-project"
    "restore-deleted-team-files"
    "sso-config-deleted"
    "sso-config-tested"
    "success-activate-organization-sso"
    "success-apply-organization-sso-changes"
    "unassign-organization-attribute"
    "update-organization-invitation"
    "update-organization-permissions"
    "update-team-invitation"
    "update-team-invitation-role"
    "update-team-member-role"
    "update-team-photo"
    "verify-token"})

;; Props id key -> email prop filled from profile.email (admin-console path only).
;; Some producers store ids as UUID strings (e.g. :user-who-send-invitation).
(def ^:private id->email-prop
  {:profile-id                 :profile-email
   :user-id                    :user-email
   :member-id                  :member-email
   :user-who-send-invitation   :user-who-send-invitation-email})

(defn- collect-team-ids-needing-name
  [events]
  (into #{}
        (comp (map :props)
              (keep (fn [props]
                      (when-let [team-id (and (not (contains? props :team-name))
                                              (uuid/coerce (:team-id props)))]
                        team-id))))
        events))

(defn- collect-profile-ids-needing-email
  [events]
  (into #{}
        (mapcat (fn [{:keys [props]}]
                  (keep (fn [[id-key email-key]]
                          (when-not (contains? props email-key)
                            (uuid/coerce (get props id-key))))
                        id->email-prop)))
        events))

(defn- collect-actor-ids-needing-profile
  "Actor ids (event :profile-id) that still need top-level :profile-email
  and/or :profile-name. Skips uuid/zero and non-uuids."
  [events]
  (into #{}
        (keep (fn [event]
                (when (or (not (contains? event :profile-email))
                          (not (contains? event :profile-name)))
                  (when-let [id (uuid/coerce (:profile-id event))]
                    (when-not (uuid/zero? id)
                      id)))))
        events))

(defn- load-team-names
  [conn ids]
  (if (seq ids)
    (let [arr  (db/create-array conn "uuid" ids)
          rows (db/exec! conn ["SELECT id, name FROM team WHERE id = ANY(?)" arr])]
      (into {} (map (juxt :id :name) rows)))
    {}))

(defn- load-profiles
  "Batch-load profile email and fullname (including soft-deleted)."
  [conn ids]
  (if (seq ids)
    (let [arr  (db/create-array conn "uuid" ids)
          rows (db/exec! conn ["SELECT id, email, fullname FROM profile WHERE id = ANY(?)" arr])]
      (into {} (map (juxt :id identity) rows)))
    {}))

(defn- maybe-assoc-team-name
  [event team-names]
  (let [props (:props event)]
    (if-let [team-name (and (not (contains? props :team-name))
                            (when-let [team-id (uuid/coerce (:team-id props))]
                              (get team-names team-id)))]
      (assoc-in event [:props :team-name] team-name)
      event)))

(defn- maybe-assoc-emails
  [event profiles]
  (update event :props
          (fn [props]
            (reduce-kv (fn [props id-key email-key]
                         (if-let [email (and (not (contains? props email-key))
                                             (when-let [id (uuid/coerce (get props id-key))]
                                               (:email (get profiles id))))]
                           (assoc props email-key email)
                           props))
                       props
                       id->email-prop))))

(defn- maybe-assoc-actor-profile
  "Fill-only top-level :profile-email / :profile-name from the event
  actor (:profile-id). Omits keys when the id is missing, zero, the
  profile row is absent, or the corresponding column is empty
  (fullname defaults to \"\" in the schema)."
  [event profiles]
  (let [id (uuid/coerce (:profile-id event))]
    (if (or (nil? id) (uuid/zero? id))
      event
      (if-let [profile (get profiles id)]
        (cond-> event
          (and (not (contains? event :profile-email))
               (not-empty (:email profile)))
          (assoc :profile-email (:email profile))

          (and (not (contains? event :profile-name))
               (not-empty (:fullname profile)))
          (assoc :profile-name (:fullname profile)))
        event))))

(defn- enrich-events-for-admin-console
  "Enrich allowlisted events before the admin-console ingest:
  1. Fill missing :team-name from team id (including soft-deleted).
  2. Fill missing emails from matching id keys in props (including
     soft-deleted profiles): :profile-email, :user-email, :member-email,
     :user-who-send-invitation-email.
  3. Fill missing top-level :profile-email / :profile-name from the
     event actor (:profile-id), including soft-deleted profiles; omit
     when there is no usable actor id or the value is empty."
  [{:keys [::db/conn]} events]
  (let [team-ids    (collect-team-ids-needing-name events)
        profile-ids (into (collect-profile-ids-needing-email events)
                          (collect-actor-ids-needing-profile events))
        team-names  (load-team-names conn team-ids)
        profiles    (load-profiles conn profile-ids)]
    (mapv (fn [event]
            (-> event
                (maybe-assoc-team-name team-names)
                (maybe-assoc-emails profiles)
                (maybe-assoc-actor-profile profiles)))
          events)))

(defn- send-to-admin-console
  "Ingest rows into the admin console. Returns {:processed :skipped}
  (filtered-out rows count as skipped); throws carrying the attempted
  count as :events, like send-to-nexus. Enrich failures propagate
  untouched: a local DB/decode failure is ours, not the destination's,
  and must not count as a send failure."
  [cfg rows]
  (let [events   (filterv #(and (contains? event-names-for-admin-console (:name %))
                                (not (audit/telemetry-shadow-source? (:source %))))
                          rows)
        total    (count rows)
        skipped  (- total (count events))
        uri-host (some-> (cf/get :admin-console-uri) u/uri u/get-domain)]
    (l/dbg :hint "consumer events chunk ready"
           :consumer "admin-console"
           :uri-host uri-host
           :total total
           :skipped skipped)
    (if (empty? events)
      {:processed 0 :skipped skipped}
      ;; Enrich first, outside the try: a local DB/decode failure is
      ;; ours, not the destination's, and must not count as
      ;; admin-console/failed.
      (let [enriched (enrich-events-for-admin-console cfg events)
            n        (count events)]
        (try
          (nitrate/call cfg :ingest-audit-log {:events enriched})
          (l/dbg :hint "consumer events chunk ingested"
                 :consumer "admin-console"
                 :uri-host uri-host
                 :processed n
                 :skipped skipped)
          {:processed n :skipped skipped}
          (catch Throwable cause
            (l/dbg :hint "consumer events chunk failed"
                   :consumer "admin-console"
                   :uri-host uri-host
                   :events n
                   :code :admin-console-send-failed)
            (throw (ex/raise :type :internal
                             :code :admin-console-send-failed
                             :hint "unable to archive events to the admin console"
                             :cause cause
                             :events n))))))))

(defn- process-admin-console-chunk
  "Send one chunk to the admin console. Returns {:processed :skipped}:
  the row outside the allowlist (and the telemetry shadows) count as
  skipped. Throws on failure, same contract as the nexus one."
  [cfg rows]
  (let [decoded (mapv consumers/decode-row rows)
        _       (l/trc :hint "consumer events chunk"
                       :consumer "admin-console"
                       :events (count decoded))]
    (send-to-admin-console cfg decoded)))

(def schema:audit-log-consumer-admin-console-params
  "Params of the admin-console job: none. Cron submits {}; system deps
  (pool, admin-console client) live on the job-def cfg closed over by
  the handler, not in job params. Closed on purpose, same as the nexus
  schema."
  [:map {:closed true}])

(declare execute-audit-log-consumer-admin-console)

(defmethod ig/assert-key ::job-def
  [_ cfg]
  ;; No assert on the admin-console client: it inits to nil without the
  ;; :admin-console flag, and the job skips on its flag anyway.
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  ;; Same as the nexus job-def: destination host once at boot. The
  ;; ingest uri is resolved by nitrate/call at send time (it raises
  ;; when missing); here it is display-only.
  (l/dbg :hint "audit log consumer job initialized"
         :consumer "admin-console"
         :uri-host (some-> (cf/get :admin-console-uri) u/uri u/get-domain))
  {::jobs/name      :audit-log-consumer-admin-console
   ::jobs/schema    schema:audit-log-consumer-admin-console-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-consumer-admin-console cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-consumer-admin-console-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-consumer-admin-console-params)})

(defn execute-audit-log-consumer-admin-console
  "Admin-console job handler: drain the stream from the admin-console
  cursor in chunks (heartbeat per iteration: the batches can be long)."
  [cfg _params]
  (if-not (contains? cf/flags :admin-console)
    (l/dbg :hint "audit log consumer (admin-console) skipped, flag off")
    (consumers/drain-consumer cfg consumers/consumer-admin-console process-admin-console-chunk)))

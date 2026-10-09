;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.archive-task
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.jobs :as jobs]
   [app.nitrate :as nitrate]
   [app.setup :as-alias setup]
   [integrant.core :as ig]
   [promesa.exec :as px]))

;; This is a task responsible to send the accumulated events to
;; external service for archival.

(defn- decode-row
  [{:keys [props ip-addr context] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))

    (db/pgobject? context)
    (assoc :context (db/decode-transit-pgobject context))

    (db/pgobject? ip-addr "inet")
    (assoc :ip-addr (db/decode-inet ip-addr))))

(def ^:private event-keys
  [:type
   :name
   :source
   :created-at
   :tracked-at
   :profile-id
   :ip-addr
   :props
   :context])

(defn- row->event
  [row]
  (select-keys row event-keys))

(defn- send!
  [{:keys [::uri] :as cfg} events]
  (let [skey    (-> cfg ::setup/shared-keys :nexus)
        body    (t/encode {:events events})
        headers {"content-type" "application/transit+json"
                 "origin" (str (cf/get :public-uri))
                 "x-shared-key" (str "nexus " skey)}
        params  {:uri uri
                 :timeout 12000
                 :method :post
                 :headers headers
                 :body body}
        resp    (http/req cfg params {:skip-ssrf-check? true})]

    (if (= (:status resp) 204)
      true
      (do
        (l/error :hint "unable to archive events"
                 :resp-status (:status resp)
                 :resp-body (:body resp))
        false))))

(def ^:private event-names-for-nitrate
  #{"accept-organization-invitation"
    "accept-team-invitation"
    "accept-team-invitation-from"
    "add-member-to-organization"
    "add-team-to-organization"
    "cancel-organization-invitation"
    "change-organization-advanced-permission"
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
    "permanently-delete-team-files"
    "remove-organization-team"
    "remove-team-from-organization"
    "rename-organization"
    "rename-project"
    "restore-deleted-team-files"
    "success-activate-organization-sso"
    "success-apply-organization-sso-changes"
    "update-organization-invitation"
    "update-organization-permissions"
    "update-team-invitation"
    "update-team-invitation-role"
    "update-team-member-role"
    "update-team-photo"
    "verify-token"})

;; Props id key -> email prop filled from profile.email (nitrate path only).
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

(defn- load-team-names
  [conn ids]
  (if (seq ids)
    (let [arr  (db/create-array conn "uuid" ids)
          rows (db/exec! conn ["SELECT id, name FROM team WHERE id = ANY(?)" arr])]
      (into {} (map (juxt :id :name) rows)))
    {}))

(defn- load-profile-emails
  [conn ids]
  (if (seq ids)
    (let [arr  (db/create-array conn "uuid" ids)
          rows (db/exec! conn ["SELECT id, email FROM profile WHERE id = ANY(?)" arr])]
      (into {} (map (juxt :id :email) rows)))
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
  [event emails]
  (update event :props
          (fn [props]
            (reduce-kv (fn [props id-key email-key]
                         (if-let [email (and (not (contains? props email-key))
                                             (when-let [id (uuid/coerce (get props id-key))]
                                               (get emails id)))]
                           (assoc props email-key email)
                           props))
                       props
                       id->email-prop))))

(defn- enrich-events-for-nitrate
  "Enrich allowlisted events before nitrate ingest:
  1. Fill missing :team-name from team id (including soft-deleted).
  2. Fill missing emails from matching id keys (including soft-deleted
     profiles): :profile-email, :user-email, :member-email,
     :user-who-send-invitation-email."
  [{:keys [::db/conn]} events]
  (let [team-ids    (collect-team-ids-needing-name events)
        profile-ids (collect-profile-ids-needing-email events)
        team-names  (load-team-names conn team-ids)
        emails      (load-profile-emails conn profile-ids)]
    (mapv (fn [event]
            (-> event
                (maybe-assoc-team-name team-names)
                (maybe-assoc-emails emails)))
          events)))

(defn- send-to-nitrate!
  [cfg rows]
  (when-let [events (->> rows
                         (filterv #(contains? event-names-for-nitrate (:name %)))
                         (not-empty))]
    (nitrate/call cfg :ingest-audit-log
                  {:events (enrich-events-for-nitrate cfg events)})))

(defn- mark-archived!
  [{:keys [::db/conn]} rows]
  (let [ids (db/create-array conn "uuid" (map :id rows))]
    (db/exec-one! conn ["update audit_log set archived_at=now() where id = ANY(?)" ids])))

(def ^:private sql:get-audit-log-chunk
  "SELECT *
     FROM audit_log
    WHERE archived_at IS NULL
    ORDER BY created_at ASC
    LIMIT 128
      FOR UPDATE
     SKIP LOCKED")

(defn- get-event-rows
  [{:keys [::db/conn] :as cfg}]
  (->> (db/exec! conn [sql:get-audit-log-chunk])
       (not-empty)))

(defn- archive-events!
  [{:keys [::uri] :as cfg}]
  (db/tx-run! cfg (fn [cfg]
                    (when-let [rows (get-event-rows cfg)]
                      (let [decoded (mapv decode-row rows)
                            events  (mapv row->event decoded)]
                        (l/trc :hint "archive events chunk" :uri uri :events (count events))

                        (when (contains? cf/flags :admin-console)
                          (send-to-nitrate! cfg decoded))

                        ;; When :nexus is off, treat Nexus as skipped
                        ;; success so nitrate-only still marks the chunk.
                        ;; REPL :enabled true with neither send flag also marks
                        ;; with no outbound send — always set at least one flag.
                        (let [nexus-ok? (if (contains? cf/flags :nexus)
                                          (send! cfg events)
                                          true)]
                          (when nexus-ok?
                            (mark-archived! cfg rows)
                            (count events))))))))

(declare execute-audit-log-archive)

(def schema:audit-log-archive-params
  "Optional overrides for the repl invocation defaults."
  [:map
   ::db/pool
   ::setup/shared-keys
   ::http/client
   [:app.nitrate/client {:optional true} [:maybe :map]]
   [:enabled {:optional true} :boolean]
   [:uri {:optional true} ::sm/uri]])

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :audit-log-archive
   ::jobs/schema    schema:audit-log-archive-params
   ::jobs/handler
   (fn [_context params]
     (execute-audit-log-archive cfg params))
   ::jobs/decoder   (sm/decoder schema:audit-log-archive-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:audit-log-archive-params)})

(defn execute-audit-log-archive
  "Plain job handler: archive the accumulated audit events in chunks
  (heartbeat per iteration: the sent chunk batches can be long)."
  [cfg params]
  ;; NOTE: this let allows overwrite default configured values from
  ;; the repl, when manually invoking the task. Prefer setting a send
  ;; flag (:admin-console and/or :nexus); :enabled alone
  ;; marks chunks without shipping.
  (let [enabled (or (contains? cf/flags :nexus)
                    (contains? cf/flags :admin-console)
                    (:enabled params false))
        uri     (cf/get :audit-log-archive-uri)
        uri     (or uri (:uri params))
        ;; Normalize to an uri object; params may carry a plain string
        ;; on direct invocations (validation only applies on submit!).
        uri     (u/uri uri)
        cfg     (assoc cfg ::uri uri)]
    (when (and (contains? cf/flags :nexus)
               (not uri))
      (ex/raise :type :internal
                :code :task-not-configured
                :hint "archive task not configured, missing uri"))
    (when enabled
      (loop [total 0]
        (if-let [n (archive-events! cfg)]
          (do
            (jobs/heartbeat cfg)
            (px/sleep 100)
            (recur (+ total ^long n)))
          (when (pos? total)
            (l/dbg :hint "events archived" :total total)))))))

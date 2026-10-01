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
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
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
    "create-file"
    "create-organization"
    "create-organization-invitation"
    "create-project"
    "create-team"
    "create-team-access-request"
    "create-team-invitation"
    "create-team-invitations"
    "create-webhook"
    "delete-font"
    "delete-organization"
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
    "update-organization-invitation"
    "update-organization-permissions"
    "update-team-invitation"
    "update-team-invitation-role"
    "update-team-member-role"
    "update-team-photo"
    "verify-token"})

(defn- send-to-nitrate!
  [cfg rows]
  (when-let [events (->> rows
                         (filterv #(contains? event-names-for-nitrate (:name %)))
                         (not-empty))]
    (nitrate/call cfg :ingest-audit-log {:events events})))

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

(def ^:private schema:handler-params
  [:map
   ::db/pool
   ::setup/shared-keys
   ::http/client
   [:app.nitrate/client {:optional true} [:maybe :map]]])

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (sm/valid? schema:handler-params params) "valid params expected for handler"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as params}]
    ;; NOTE: this let allows overwrite default configured values from
    ;; the repl, when manually invoking the task. Prefer setting a send
    ;; flag (:admin-console and/or :nexus); :enabled alone
    ;; marks chunks without shipping. `invoke!` passes params under
    ;; `:props`; direct REPL calls may pass a flat map.
    (let [props   (or props params)
          enabled (or (contains? cf/flags :nexus)
                      (contains? cf/flags :admin-console)
                      (:enabled props false))

          uri     (cf/get :audit-log-archive-uri)
          uri     (or uri (:uri props))
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
              (px/sleep 100)
              (recur (+ total ^long n)))

            (when (pos? total)
              (l/dbg :hint "events archived" :total total))))))))

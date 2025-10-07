;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.audit.archive-task
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]
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
  (let [token   (tokens/generate cfg
                                 {:iss "authentication"
                                  :uid uuid/zero})
        body    (t/encode {:events events})
        headers {"content-type" "application/transit+json"
                 "origin" (cf/get :public-uri)
                 "cookie" (u/map->query-string {:auth-token token})}
        params  {:uri uri
                 :timeout 12000
                 :method :post
                 :headers headers
                 :body body}
        resp    (http/req! cfg params)]
    (if (= (:status resp) 204)
      true
      (do
        (l/error :hint "unable to archive events"
                 :resp-status (:status resp)
                 :resp-body (:body resp))
        false))))

(defn- mark-archived!
  [{:keys [::db/conn]} rows]
  (let [ids (db/create-array conn "uuid" (map :id rows))]
    (db/exec-one! conn ["update audit_log set archived_at=now() where id = ANY(?)" ids])))

(def ^:private xf:create-event
  (comp (map decode-row)
        (map row->event)))

(def ^:private sql:get-audit-log-chunk
  "SELECT *
     FROM audit_log
    WHERE archived_at is null
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
                      (let [events (into [] xf:create-event rows)]
                        (l/trc :hint "archive events chunk" :uri uri :events (count events))
                        (when (send! cfg events)
                          (mark-archived! cfg rows)
                          (count events)))))))

(def ^:private schema:handler-params
  [:map
   ::db/pool
   ::setup/props
   ::http/client])

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (sm/valid? schema:handler-params params) "valid params expected for handler"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [params]
    ;; NOTE: this let allows overwrite default configured values from
    ;; the repl, when manually invoking the task.
    (let [enabled (or (contains? cf/flags :audit-log-archive)
                      (:enabled params false))

          uri     (cf/get :audit-log-archive-uri)
          uri     (or uri (:uri params))
          cfg     (assoc cfg ::uri uri)]

      (when (and enabled (not uri))
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


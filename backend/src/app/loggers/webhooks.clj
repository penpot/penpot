;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.webhooks
  "A mattermost integration for error reporting."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uri :as uri]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit :as audit]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.data.json :as json]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

;; --- HELPERS

(defn key-fn
  [k & keys]
  (fn [params]
    (reduce #(dm/str %1 ":" (get params %2))
            (dm/str (get params k))
            keys)))

;; --- PROC

(defn- lookup-webhooks-by-team
  [pool team-id]
  (db/exec! pool ["select w.* from webhook as w where team_id=? and is_active=true" team-id]))

(defn- lookup-webhooks-by-project
  [pool project-id]
  (let [sql [(str "select w.* from webhook as w"
                  "  join project as p on (p.team_id = w.team_id)"
                  " where p.id = ? and w.is_active = true")
             project-id]]
    (db/exec! pool sql)))

(defn- lookup-webhooks-by-file
  [pool file-id]
  (let [sql [(str "select w.* from webhook as w"
                  "  join project as p on (p.team_id = w.team_id)"
                  "  join file as f on (f.project_id = p.id)"
                  " where f.id = ? and w.is_active = true")
             file-id]]
    (db/exec! pool sql)))

(defn- lookup-webhooks
  [{:keys [::db/pool]} {:keys [props] :as event}]
  (or (some->> (:team-id props) (lookup-webhooks-by-team pool))
      (some->> (:project-id props) (lookup-webhooks-by-project pool))
      (some->> (:file-id props) (lookup-webhooks-by-file pool))))

(defmethod ig/assert-key ::process-event-handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool")
  (assert (http/client? (::http/client params)) "expect valid http client"))

(defmethod ig/init-key ::process-event-handler
  [_ cfg]
  (fn [{:keys [props] :as task}]

    (let [items (lookup-webhooks cfg props)
          event {::audit/profile-id (:profile-id props)
                 ::audit/name "webhook"
                 ::audit/type "trigger"
                 ::audit/props {:name (get props :name)
                                :event-id (get props :id)
                                :total-affected (count items)}}]

      (audit/insert! cfg event)

      (when items
        (l/trc :hint "webhooks found for event" :total (count items))
        (db/tx-run! cfg (fn [cfg]
                          (doseq [item items]
                            (wrk/submit! (-> cfg
                                             (assoc ::wrk/task :run-webhook)
                                             (assoc ::wrk/queue :webhooks)
                                             (assoc ::wrk/max-retries 3)
                                             (assoc ::wrk/params {:event props
                                                                  :config item}))))))))))
;; --- RUN

(declare interpret-exception)
(declare interpret-response)

(def json-write-opts
  {:key-fn str/camel
   :indent true})

(defmethod ig/assert-key ::run-webhook-handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool")
  (assert (http/client? (::http/client params)) "expect valid http client"))

(defmethod ig/expand-key ::run-webhook-handler
  [k v]
  {k (merge {::max-errors 3} (d/without-nils v))})

(defmethod ig/init-key ::run-webhook-handler
  [_ {:keys [::db/pool ::max-errors] :as cfg}]
  (letfn [(update-webhook! [whook err]
            (if err
              (let [sql [(str "update webhook "
                              "   set error_code=?, "
                              "       error_count=error_count+1 "
                              " where id=?")
                         err
                         (:id whook)]
                    res (db/exec-one! pool sql {::db/return-keys true})]
                (when (>= (:error-count res) max-errors)
                  (db/update! pool :webhook
                              {:is-active false}
                              {:id (:id whook)})))

              (db/update! pool :webhook
                          {:updated-at (dt/now)
                           :error-code nil
                           :error-count 0}
                          {:id (:id whook)})))

          (report-delivery! [whook req rsp err]
            (db/insert! pool :webhook-delivery
                        {:webhook-id (:id whook)
                         :created-at (dt/now)
                         :error-code err
                         :req-data (db/tjson req)
                         :rsp-data (db/tjson rsp)}))]

    (fn [{:keys [props] :as task}]
      (let [event (:event props)
            whook (:config props)

            body  (case (:mtype whook)
                    "application/json" (json/write-str event json-write-opts)
                    "application/transit+json" (t/encode-str event)
                    "application/x-www-form-urlencoded" (uri/map->query-string event))]

        (l/dbg :hint "run webhook"
               :event-name (:name event)
               :webhook-id (str (:id whook))
               :webhook-uri (:uri whook)
               :webhook-mtype (:mtype whook))

        (let [req {:uri (:uri whook)
                   :headers {"content-type" (:mtype whook)
                             "user-agent" (str/ffmt "penpot/%" (:main cf/version))}
                   :timeout (dt/duration "4s")
                   :method :post
                   :body body}]
          (try
            (let [rsp (http/req! cfg req {:response-type :input-stream :sync? true})
                  err (interpret-response rsp)]
              (report-delivery! whook req rsp err)
              (update-webhook! whook err))
            (catch Throwable cause
              (let [err (interpret-exception cause)]
                (report-delivery! whook req nil err)
                (update-webhook! whook err)
                (when (= err "unknown")
                  (l/err :hint "unknown error on webhook request"
                         :cause cause))))))))))

(defn interpret-response
  [{:keys [status] :as response}]
  (when-not (or (= 200 status)
                (= 204 status))
    (str/ffmt "unexpected-status:%" status)))

(defn interpret-exception
  [cause]
  (cond
    (instance? javax.net.ssl.SSLHandshakeException cause)
    "ssl-validation-error"

    (instance? java.net.ConnectException cause)
    "connection-error"

    (instance? java.lang.IllegalArgumentException cause)
    "invalid-uri"

    (instance? java.net.http.HttpConnectTimeoutException cause)
    "timeout"))

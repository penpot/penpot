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
   [app.util.json :as json]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
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

(defmethod ig/pre-init-spec ::process-event-handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::process-event-handler
  [_ {:keys [::db/pool] :as cfg}]
  (fn [{:keys [props] :as task}]
    (let [event (::event props)]

      (l/debug :hint "process webhook event"
               :name (:name event))

      (when-let [items (lookup-webhooks cfg event)]
        (l/trace :hint "webhooks found for event" :total (count items))

        (db/with-atomic [conn pool]
          (doseq [item items]
            (wrk/submit! ::wrk/conn conn
                         ::wrk/task :run-webhook
                         ::wrk/queue :webhooks
                         ::wrk/max-retries 3
                         ::event event
                         ::config item)))))))

;; --- RUN

(declare interpret-exception)
(declare interpret-response)

(def ^:private json-mapper
  (json/mapper
   {:encode-key-fn str/camel
    :decode-key-fn (comp keyword str/kebab)
    :pretty true}))

(defmethod ig/pre-init-spec ::run-webhook-handler [_]
  (s/keys :req [::http/client ::db/pool]))

(defmethod ig/prep-key ::run-webhook-handler
  [_ cfg]
  (merge {::max-errors 3} (d/without-nils cfg)))

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
                    res (db/exec-one! pool sql {::db/return-keys? true})]
                (when (>= (:error-count res) max-errors)
                  (db/update! pool :webhook {:is-active false} {:id (:id whook)})))

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
      (let [event (::event props)
            whook (::config props)

            body  (case (:mtype whook)
                    "application/json" (json/encode-str event json-mapper)
                    "application/transit+json" (t/encode-str event)
                    "application/x-www-form-urlencoded" (uri/map->query-string event))]

        (l/debug :hint "run webhook"
                 :event-name (:name event)
                 :webhook-id (:id whook)
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
                  (l/error :hint "unknown error on webhook request"
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
    "timeout"
    ))

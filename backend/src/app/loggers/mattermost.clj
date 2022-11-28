;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.mattermost
  "A mattermost integration for error reporting."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http.client :as http]
   [app.loggers.database :as ldb]
   [app.loggers.zmq :as lzmq]
   [app.util.json :as json]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(defonce enabled (atom true))

(defn- send-mattermost-notification!
  [cfg {:keys [host id public-uri] :as event}]
  (let [text (str "Exception on (host: " host ", url: " public-uri "/dbg/error/" id ")\n"
             (when-let [pid (:profile-id event)]
               (str "- profile-id: #uuid-" pid "\n")))
        resp (http/req! cfg
                        {:uri (cf/get :error-report-webhook)
                         :method :post
                         :headers {"content-type" "application/json"}
                         :body (json/write-str {:text text})}
                        {:sync? true})]

    (when (not= 200 (:status resp))
      (l/warn :hint "error on sending data"
              :response (pr-str resp)))))

(defn handle-event
  [cfg event]
  (try
    (let [event (ldb/parse-event event)]
      (when @enabled
        (send-mattermost-notification! cfg event)))
    (catch Throwable cause
      (l/warn :hint "unhandled error"
              :cause cause))))

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req [::http/client
                ::lzmq/receiver]))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (when-let [uri (cf/get :error-report-webhook)]
    (px/thread
      {:name "penpot/mattermost-reporter"}
      (l/info :msg "initializing error reporter" :uri uri)
      (let [input (a/chan (a/sliding-buffer 128)
                          (filter #(= (:logger/level %) "error")))]
        (try
          (lzmq/sub! (::lzmq/receiver cfg) input)
          (loop []
            (when-let [msg (a/<!! input)]
              (handle-event cfg msg)
              (recur)))
          (catch InterruptedException _
            (l/debug :hint "reporter interrupted"))
          (catch Throwable cause
            (l/error :hint "unexpected error" :cause cause))
          (finally
            (a/close! input)
            (l/info :hint "reporter terminated")))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))

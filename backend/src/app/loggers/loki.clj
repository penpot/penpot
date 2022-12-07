;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.loki
  "A Loki integration."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http.client :as http]
   [app.loggers.zmq :as lzmq]
   [app.util.json :as json]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(declare ^:private handle-event)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req [::http/client
                ::lzmq/receiver]))

(defmethod ig/init-key ::reporter
  [_ cfg]
  (when-let [uri (cf/get :loggers-loki-uri)]
    (px/thread
      {:name "penpot/loki-reporter"}
      (l/info :hint "reporter started" :uri uri)
      (let [input (a/chan (a/dropping-buffer 2048))
            cfg   (assoc cfg ::uri uri)]

        (try
          (lzmq/sub! (::lzmq/receiver cfg) input)
          (loop []
            (when-let [msg (a/<!! input)]
              (handle-event cfg msg)
              (recur)))

          (catch InterruptedException _
            (l/debug :hint "reporter interrupted"))
          (catch Throwable cause
            (l/error :hint "unexpected exception"
                     :cause cause))
          (finally
            (a/close! input)
            (l/info :hint "reporter terminated")))))))

(defmethod ig/halt-key! ::reporter
  [_ thread]
  (some-> thread px/interrupt!))

(defn- prepare-payload
  [event]
  (let [labels {:host    (cf/get :host)
                :tenant  (cf/get :tenant)
                :version (:full cf/version)
                :logger  (:logger/name event)
                :level   (:logger/level event)}]
    {:streams
     [{:stream labels
       :values [[(str (* (inst-ms (:created-at event)) 1000000))
                 (str (:message event)
                      (when-let [error (:trace event)]
                        (str "\n" error)))]]}]}))

(defn- make-request
  [{:keys [::uri] :as cfg} payload]
  (http/req! cfg
             {:uri uri
              :timeout 3000
              :method :post
              :headers {"content-type" "application/json"}
              :body (json/encode payload)}
             {:sync? true}))

(defn- handle-event
  [cfg event]
  (try
    (let [payload  (prepare-payload event)
          response (make-request cfg payload)]
      (when-not (= 204 (:status response))
        (l/error :hint "error on sending log to loki (unexpected response)"
                 :response (pr-str response))))
    (catch Throwable cause
      (l/error :hint "error on sending log to loki (unexpected exception)"
               :cause cause))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.loki
  "A Loki integration."
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.json :as json]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private handle-event)
(declare ^:private start-rcv-loop)

(s/def ::uri ::us/string)
(s/def ::receiver fn?)
(s/def ::http-client fn?)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [ ::receiver ::http-client]
          :opt-un [::uri]))

(defmethod ig/init-key ::reporter
  [_ {:keys [receiver uri] :as cfg}]
  (when uri
    (l/info :msg "initializing loki reporter" :uri uri)
    (let [input (a/chan (a/dropping-buffer 2048))]
      (receiver :sub input)

      (doto (Thread. #(start-rcv-loop cfg input))
        (.setDaemon true)
        (.setName "penpot/loki-sender")
        (.start))

      input)))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (when output
    (a/close! output)))

(defn- start-rcv-loop
  [cfg input]
  (loop []
    (let [msg (a/<!! input)]
      (when-not (nil? msg)
        (handle-event cfg msg)
        (recur))))

  (l/info :msg "stopping error reporting loop"))

(defn- prepare-payload
  [event]
  (let [labels {:host    (cfg/get :host)
                :tenant  (cfg/get :tenant)
                :version (:full cfg/version)
                :logger  (:logger/name event)
                :level   (:logger/level event)}]
    {:streams
     [{:stream labels
       :values [[(str (* (inst-ms (:created-at event)) 1000000))
                 (str (:message event)
                      (when-let [error (:trace event)]
                        (str "\n" error)))]]}]}))


(defn- make-request
  [{:keys [http-client uri] :as cfg} payload]
  (http-client {:uri uri
                :timeout 3000
                :method :post
                :headers {"content-type" "application/json"}
                :body (json/write payload)}
               {:sync? true}))

(defn- handle-event
  [cfg event]
  (try
    (let [payload  (prepare-payload event)
          response (make-request cfg payload)]
      (when-not (= 204 (:status response))
        (map? response)
        (l/error :hint "error on sending log to loki (unexpected response)"
                 :response (pr-str response))))
    (catch Throwable cause
      (l/error :hint "error on sending log to loki (unexpected exception)"
               :cause cause))))

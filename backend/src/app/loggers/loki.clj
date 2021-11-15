;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.loggers.loki
  "A Loki integration."
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.async :as aa]
   [app.util.http :as http]
   [app.util.json :as json]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare handle-event)

(s/def ::uri ::us/string)
(s/def ::receiver fn?)

(defmethod ig/pre-init-spec ::reporter [_]
  (s/keys :req-un [::wrk/executor ::receiver]
          :opt-un [::uri]))

(defmethod ig/init-key ::reporter
  [_ {:keys [receiver uri] :as cfg}]
  (when uri
    (l/info :msg "initializing loki reporter" :uri uri)
    (let [input (a/chan (a/dropping-buffer 512))]
      (receiver :sub input)
      (a/go-loop []
        (let [msg (a/<! input)]
          (if (nil? msg)
            (l/info :msg "stoping error reporting loop")
            (do
              (a/<! (handle-event cfg msg))
              (recur)))))
      input)))

(defmethod ig/halt-key! ::reporter
  [_ output]
  (when output
    (a/close! output)))

(defn- prepare-payload
  [event]
  (let [labels {:host    (cfg/get :host)
                :tenant  (cfg/get :tenant)
                :version (:full cfg/version)
                :logger  (:logger event)
                :level   (:level event)}]
    {:streams
     [{:stream labels
       :values [[(str (* (inst-ms (:created-at event)) 1000000))
                 (str (:message event)
                      (when-let [error (:error event)]
                        (str "\n" (:trace error))))]]}]}))

(defn- send-log
  [uri payload i]
  (try
    (let [response (http/send! {:uri uri
                                :timeout 6000
                                :method :post
                                :headers {"content-type" "application/json"}
                                :body (json/encode payload)})]
      (cond
        (= (:status response) 204)
        true

        (= (:status response) 400)
        (do
          (l/error :hint "error on sending log to loki (no retry)"
                   :rsp (pr-str response))
          true)

        :else
        (do
          (l/error :hint "error on sending log to loki" :try i
                   :rsp (pr-str response))
          false)))
    (catch Exception e
      (l/error :hint "error on sending message to loki" :cause e :try i)
      false)))

(defn- handle-event
  [{:keys [executor uri]} event]
  (aa/with-thread executor
    (let [payload (prepare-payload event)]
      (loop [i 1]
        (when (and (not (send-log uri payload i)) (< i 20))
          (Thread/sleep (* i 2000))
          (recur (inc i)))))))


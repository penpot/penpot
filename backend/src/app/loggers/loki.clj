;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.loggers.loki
  "A Loki integration."
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.async :as aa]
   [app.util.http :as http]
   [app.util.json :as json]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
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
    (log/info "Intializing loki reporter.")
    (let [output (a/chan (a/sliding-buffer 1024))]
      (receiver :sub output)
      (a/go-loop []
        (let [msg (a/<! output)]
          (if (nil? msg)
            (log/info "Stoping error reporting loop.")
            (do
              (a/<! (handle-event cfg msg))
              (recur)))))
      output)))

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
      (if (= (:status response) 204)
        true
        (do
          (log/errorf "Error on sending log to loki (try %s).\n%s" i (pr-str response))
          false)))
    (catch Exception e
      (log/errorf e "Error on sending message to loki (try %s)." i)
      false)))

(defn- handle-event
  [{:keys [executor uri]} event]
  (aa/with-thread executor
    (let [payload (prepare-payload event)]
      (loop [i 1]
        (when (and (not (send-log uri payload i)) (< i 20))
          (Thread/sleep (* i 2000))
          (recur (inc i)))))))


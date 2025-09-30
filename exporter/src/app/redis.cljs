;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.redis
  (:require
   ["ioredis" :as redis]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]))

(l/set-level! :trace)

(def client (atom nil))

(defn- create-client
  [uri]
  (let [^js client (new redis/default uri)]
    (.on client "connect"
         (fn [] (l/info :hint "redis connection established" :uri uri)))
    (.on client "error"
         (fn [cause] (l/error :hint "error on redis connection" :cause cause)))
    (.on client "close"
         (fn [] (l/warn :hint "connection closed")))
    (.on client "reconnect"
         (fn [ms] (l/warn :hint "reconnecting to redis" :ms ms)))
    (.on client "end"
         (fn [] (l/warn :hint "client ended, no more connections will be attempted")))
    client))

(defn init
  []
  (swap! client (fn [prev]
                  (when prev (.disconnect ^js prev))
                  (create-client (cf/get :redis-uri)))))


(defn stop
  []
  (swap! client (fn [client]
                  (when client (.quit ^js client))
                  nil)))

(def ^:private tenant (cf/get :tenant))

(defn pub!
  [topic payload]
  (let [payload (if (map? payload) (t/encode-str payload) payload)
        topic   (dm/str tenant "." topic)]
    (when-let [client @client]
      (.publish ^js client topic payload))))

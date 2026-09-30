;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.redis
  (:require
   ["ioredis" :as redis]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [promesa.core :as p]))

(l/set-level! :trace)

(def client (atom nil))

;; A connection in subscriber mode rejects every other command, so the
;; subscriptions need a connection of their own.
(def ^:private subscriber (atom nil))

;; A blocking pop holds its connection until a job arrives, stalling every
;; command queued behind it, so it gets a connection of its own too. Opened
;; on first use: only queue workers block.
(def ^:private blocking (atom nil))

(def ^:private subscriptions (atom {}))

(defn- create-client
  [uri role]
  (let [^js client (new redis/default uri)]
    (.on client "connect"
         (fn [] (l/info :hint "redis connection established" :uri uri :role role)))
    (.on client "error"
         (fn [cause] (l/error :hint "error on redis connection" :role role :cause cause)))
    (.on client "close"
         (fn [] (l/warn :hint "connection closed" :role role)))
    (.on client "reconnect"
         (fn [ms] (l/warn :hint "reconnecting to redis" :role role :ms ms)))
    (.on client "end"
         (fn [] (l/warn :hint "client ended, no more connections will be attempted" :role role)))
    client))

(defn- dispatch-message
  [topic payload]
  (doseq [handler (get @subscriptions topic)]
    (try
      (handler payload)
      (catch :default cause
        (l/error :hint "error on redis subscription handler" :topic topic :cause cause)))))

(defn init
  []
  (let [uri (cf/get :redis-uri)]
    (swap! client (fn [prev]
                    (when prev (.disconnect ^js prev))
                    (create-client uri "commands")))
    (swap! subscriber (fn [prev]
                        (when prev (.disconnect ^js prev))
                        (let [^js conn (create-client uri "subscriber")]
                          (.on conn "message" (fn [topic payload] (dispatch-message topic payload)))
                          ;; Reinstate subscriptions after a reconnection.
                          (.on conn "connect"
                               (fn []
                                 (doseq [topic (keys @subscriptions)]
                                   (.subscribe conn topic))))
                          conn)))))

(defn stop
  []
  (reset! subscriptions {})
  (swap! blocking (fn [conn]
                    (when conn (.disconnect ^js conn))
                    nil))
  (swap! subscriber (fn [conn]
                      (when conn (.quit ^js conn))
                      nil))
  (swap! client (fn [client]
                  (when client (.quit ^js client))
                  nil)))

(def ^:private tenant (cf/get :tenant))

(defn ->tenant-key
  "Namespaces `parts` under the tenant, the prefix the backend msgbus uses."
  [& parts]
  (dm/str tenant "." (apply str parts)))

(defn ->key
  "Namespaces `parts` under the exporter, inside the tenant."
  [& parts]
  (dm/str "penpot.exporter." tenant "." (apply str parts)))

(defn pub!
  "Publishes on `topic`, which must already be namespaced."
  [topic payload]
  (let [payload (if (map? payload) (t/encode-str payload) payload)]
    (when-let [client @client]
      (.publish ^js client topic payload))))

(defn sub!
  "Subscribes `handler` (fn of the raw payload string) to `topic`, which must
  already be namespaced. Returns a 0-arg fn that removes this handler."
  [topic handler]
  (swap! subscriptions update topic (fnil conj []) handler)
  (when-let [conn @subscriber]
    (.subscribe ^js conn topic))
  (fn []
    (swap! subscriptions update topic (fn [handlers] (vec (remove #(= % handler) handlers))))))

(defn- with-client
  "Runs `f` against the command connection. Rejects when there is no connection
  or the command fails: whether a failure is survivable depends on what the
  caller was doing, and only the caller knows."
  [f]
  (if-let [client @client]
    (p/do (f client))
    (p/rejected (ex/error :type :internal
                          :code :redis-not-available
                          :hint "no redis connection"))))

(defn- with-client-lenient
  "For reads, where an unreachable redis is reported as \"nothing there\"."
  [f]
  (->> (with-client f)
       (p/merr (fn [cause]
                 (l/warn :hint "redis command failed" :cause cause)
                 (p/resolved nil)))))

(defn hset!
  "Writes `data` (a map of string/keyword -> value) as a hash. Nil values are
  dropped, since redis has no null."
  [k data]
  (let [obj (reduce-kv (fn [obj field value]
                         (if (some? value)
                           (doto obj (unchecked-set (name field) (str value)))
                           obj))
                       #js {}
                       data)]
    (if (zero? (alength (js/Object.keys obj)))
      (p/resolved nil)
      (with-client (fn [^js client] (.hset client k obj))))))

(defn hgetall
  "Returns the hash as a map of string keys, or nil when it does not exist."
  [k]
  (->> (with-client-lenient (fn [^js client] (.hgetall client k)))
       (p/fmap (fn [result]
                 (when (and result (pos? (alength (js/Object.keys result))))
                   (persistent!
                    (reduce (fn [res field]
                              (assoc! res field (unchecked-get result field)))
                            (transient {})
                            (js/Object.keys result))))))))

(defn expire!
  [k seconds]
  (with-client (fn [^js client] (.expire client k seconds))))

(defn del!
  [k]
  (with-client (fn [^js client] (.del client k))))

(defn scan
  "Every key matching `pattern`, walked in cursor batches so a large keyspace is
  never blocked the way `KEYS` would block it.

  Batches are accumulated in memory rather than consumed as a stream, which a
  promise-returning fn cannot express. Fine for the job keyspace, but reading
  redis wants a streaming or reactive interface before it is used for more."
  [pattern]
  (letfn [(step [cursor found]
            (->> (with-client-lenient (fn [^js client] (.scan client cursor "MATCH" pattern "COUNT" 200)))
                 (p/mcat (fn [result]
                           (if (nil? result)
                             (p/resolved found)
                             (let [next-cursor (aget result 0)
                                   found       (into found (aget result 1))]
                               (if (= "0" next-cursor)
                                 (p/resolved found)
                                 (step next-cursor found))))))))]
    (step "0" [])))

(defn get-key
  "The string value at `k`, or nil."
  [k]
  (with-client-lenient (fn [^js client] (.get client k))))

(defn set-ex!
  "Writes `value` at `k`, expiring after `seconds`."
  [k value seconds]
  (with-client (fn [^js client] (.set client k value "EX" seconds))))

(defn key-exists?
  [k]
  (->> (with-client-lenient (fn [^js client] (.exists client k)))
       (p/fmap (fn [n] (and (some? n) (pos? n))))))

(defn rpush!
  [k value]
  (with-client (fn [^js client] (.rpush client k value))))

(defn llen
  [k]
  (with-client (fn [^js client] (.llen client k))))

(defn lrange
  "Every element of the list at `k`."
  [k]
  (->> (with-client-lenient (fn [^js client] (.lrange client k 0 -1)))
       (p/fmap (fn [result] (vec (or result []))))))

(defn lrem!
  "Removes every occurrence of `value` from the list at `k`. Resolves to the
  number removed."
  [k value]
  (with-client (fn [^js client] (.lrem client k 0 value))))

(defn lmove!
  "Atomically pops from one end of `src` and pushes onto one end of `dst`.
  Resolves to the moved element, or nil when `src` is empty."
  [src dst from to]
  (with-client (fn [^js client] (.lmove client src dst from to))))

(defn blmove!
  "Like `lmove!`, but waits up to `timeout` seconds for `src` to have an
  element. Runs on its own connection."
  [src dst from to timeout]
  (let [^js conn (or @blocking
                     (swap! blocking (fn [conn]
                                       (or conn (create-client (cf/get :redis-uri) "blocking")))))]
    (p/do (.blmove conn src dst from to timeout))))

(defn sadd!
  [k value]
  (with-client (fn [^js client] (.sadd client k value))))

(defn srem!
  [k value]
  (with-client (fn [^js client] (.srem client k value))))

(defn smembers
  [k]
  (->> (with-client-lenient (fn [^js client] (.smembers client k)))
       (p/fmap (fn [result] (vec (or result []))))))

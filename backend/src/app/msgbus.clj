;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import
   java.time.Duration
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisURI
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.codec.ByteArrayCodec
   io.lettuce.core.codec.RedisCodec
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands))

(s/def ::redis-uri ::us/string)
(s/def ::buffer-size ::us/integer)

(defmulti init-backend :backend)
(defmulti stop-backend :backend)
(defmulti init-pub-loop :backend)
(defmulti init-sub-loop :backend)

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::buffer-size ::redis-uri]))

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (merge {:buffer-size 128} cfg))

(defmethod ig/init-key ::msgbus
  [_ {:keys [backend buffer-size] :as cfg}]
  (log/debugf "initializing msgbus (backend=%s)" (name backend))

  (let [backend (init-backend cfg)

        ;; Channel used for receive publications from the application.
        pub-ch  (a/chan (a/dropping-buffer buffer-size))

        ;; Channel used for receive subscription requests.
        sub-ch  (a/chan)]

    (init-pub-loop (assoc backend :ch pub-ch))
    (init-sub-loop (assoc backend :ch sub-ch))

    (with-meta
      (fn run
        ([command] (run command nil))
        ([command params]
         (a/go
           (case command
             :pub (a/>! pub-ch params)
             :sub (a/>! sub-ch params)))))

      {::backend backend})))

(defmethod ig/halt-key! ::msgbus
  [_ f]
  (let [mdata (meta f)]
    (stop-backend (::backend mdata))))


;; --- REDIS BACKEND IMPL

(declare impl-redis-pub)
(declare impl-redis-sub)
(declare impl-redis-unsub)

(defmethod init-backend :redis
  [{:keys [redis-uri] :as cfg}]
  (let [codec    (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE)

        uri      (RedisURI/create redis-uri)
        rclient  (RedisClient/create ^RedisURI uri)

        pub-conn (.connect ^RedisClient rclient ^RedisCodec codec)
        sub-conn (.connectPubSub ^RedisClient rclient ^RedisCodec codec)]

    (.setTimeout ^StatefulRedisConnection pub-conn ^Duration (dt/duration {:seconds 10}))
    (.setTimeout ^StatefulRedisPubSubConnection sub-conn ^Duration (dt/duration {:seconds 10}))

    (-> cfg
        (assoc :pub-conn pub-conn)
        (assoc :sub-conn sub-conn)
        (assoc :close-ch (a/chan 1)))))

(defmethod stop-backend :redis
  [{:keys [pub-conn sub-conn close-ch] :as cfg}]
  (.close ^StatefulRedisConnection pub-conn)
  (.close ^StatefulRedisPubSubConnection sub-conn)
  (a/close! close-ch))

(defmethod init-pub-loop :redis
  [{:keys [pub-conn ch close-ch]}]
  (let [rac (.async ^StatefulRedisConnection pub-conn)]
    (a/go-loop []
      (let [[val _] (a/alts! [close-ch ch] :priority true)]
        (when (some? val)
          (let [result (a/<! (impl-redis-pub rac val))]
            (when (ex/exception? result)
              (log/error result "unexpected error on publish message to redis")))
          (recur))))))

(defmethod init-sub-loop :redis
  [{:keys [sub-conn ch close-ch buffer-size]}]
  (let [rcv-ch  (a/chan (a/dropping-buffer buffer-size))
        chans   (agent {} :error-handler #(log/error % "unexpected error on agent"))
        tprefix (str (cfg/get :tenant) ".")
        rac     (.async ^StatefulRedisPubSubConnection sub-conn)]

    ;; Add a unique listener to connection
    (.addListener sub-conn
                  (reify RedisPubSubListener
                    (message [it pattern topic message])
                    (message [it topic message]
                      ;; There are no back pressure, so we use a slidding
                      ;; buffer for cases when the pubsub broker sends
                      ;; more messages that we can process.
                      (let [val {:topic topic :message (blob/decode message)}]
                        (when-not (a/offer! rcv-ch val)
                          (log/warn "dropping message on subscription loop"))))
                    (psubscribed [it pattern count])
                    (punsubscribed [it pattern count])
                    (subscribed [it topic count])
                    (unsubscribed [it topic count])))

    (letfn [(subscribe-to-single-topic [nsubs topic chan]
              (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
                (when (= 1 (count nsubs))
                  (let [result (a/<!! (impl-redis-sub rac topic))]
                    (log/tracef "opening subscription to %s" topic)
                    (when (ex/exception? result)
                      (log/errorf result "unexpected exception on subscribing to '%s'" topic))))
                nsubs))

            (subscribe-to-topics [state topics chan]
              (let [state  (update state :chans assoc chan topics)]
                (reduce (fn [state topic]
                          (update-in state [:topics topic] subscribe-to-single-topic topic chan))
                        state
                        topics)))

            (unsubscribe-from-single-topic [nsubs topic chan]
              (let [nsubs (disj nsubs chan)]
                (when (empty? nsubs)
                  (let [result (a/<!! (impl-redis-unsub rac topic))]
                    (log/tracef "closing subscription to %s" topic)
                    (when (ex/exception? result)
                      (log/errorf result "unexpected exception on unsubscribing from '%s'" topic))))
                nsubs))

            (unsubscribe-channels [state pending]
              (reduce (fn [state ch]
                        (let [topics (get-in state [:chans ch])
                              state  (update state :chans dissoc ch)]
                          (reduce (fn [state topic]
                                    (update-in state [:topics topic] unsubscribe-from-single-topic topic ch))
                                  state
                                  topics)))
                      state
                      pending))]

      ;; Asynchronous subscription loop;
      (a/go-loop []
        (let [[val _] (a/alts! [close-ch ch])]
          (when-let [{:keys [topics chan]} val]
            (let [topics (into #{} (map #(str tprefix %)) topics)]
              (send-off chans subscribe-to-topics topics chan)
              (recur)))))

      (a/go-loop []
        (let [[val port] (a/alts! [close-ch rcv-ch])]
          (cond
            ;; Stop condition; close all underlying subscriptions and
            ;; exit. The close operation is performed asynchronously.
            (= port close-ch)
            (send-off chans (fn [state]
                              (log/tracef "close")
                              (->> (vals state)
                                   (mapcat identity)
                                   (filter some?)
                                   (run! a/close!))))

            ;; This means we receive data from redis and we need to
            ;; forward it to the underlying subscriptions.
            (= port rcv-ch)
            (let [topic   (:topic val)    ; topic is already string
                  pending (loop [chans   (seq (get-in @chans [:topics topic]))
                                 pending #{}]
                            (if-let [ch (first chans)]
                              (if (a/>! ch (:message val))
                                (recur (rest chans) pending)
                                (recur (rest chans) (conj pending ch)))
                              pending))]
              ;; (log/tracef "received message => pending: %s" (pr-str pending))
              (some->> (seq pending)
                       (send-off chans unsubscribe-channels))

              (recur))))))))

(defn- impl-redis-pub
  [^RedisAsyncCommands rac {:keys [topic message]}]
  (let [topic   (str (cfg/get :tenant) "." topic)
        message (blob/encode message)
        res     (a/chan 1)]
    (-> (.publish rac ^String topic ^bytes message)
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

(defn impl-redis-sub
  [^RedisPubSubAsyncCommands rac topic]
  (let [res (a/chan 1)]
    (-> (.subscribe rac (into-array String [topic]))
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

(defn impl-redis-unsub
  [rac topic]
  (let [res (a/chan 1)]
    (-> (.unsubscribe rac (into-array String [topic]))
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

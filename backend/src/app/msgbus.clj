;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import
   java.time.Duration
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisURI
   io.lettuce.core.api.StatefulConnection
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.async.RedisAsyncCommands
   io.lettuce.core.codec.ByteArrayCodec
   io.lettuce.core.codec.RedisCodec
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands))

(def ^:private prefix (cfg/get :tenant))

(defn- prefix-topic
  [topic]
  (str prefix "." topic))

(def xform-prefix (map prefix-topic))
(def xform-topics (map (fn [m] (update m :topics #(into #{} xform-prefix %)))))
(def xform-topic  (map (fn [m] (update m :topic prefix-topic))))

(s/def ::redis-uri ::us/string)
(s/def ::buffer-size ::us/integer)

(defmulti init-backend :backend)
(defmulti stop-backend :backend)
(defmulti init-pub-loop :backend)
(defmulti init-sub-loop :backend)

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :opt-un [::buffer-size ::redis-uri]))

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (merge {:buffer-size 128} cfg))

(defmethod ig/init-key ::msgbus
  [_ {:keys [backend buffer-size] :as cfg}]
  (l/debug :action "initialize msgbus"
           :backend (name backend))
  (let [cfg     (init-backend cfg)

        ;; Channel used for receive publications from the application.
        pub-ch  (-> (a/dropping-buffer buffer-size)
                    (a/chan xform-topic))

        ;; Channel used for receive subscription requests.
        sub-ch  (a/chan 1 xform-topics)

        cfg     (-> cfg
                    (assoc ::pub-ch pub-ch)
                    (assoc ::sub-ch sub-ch))]

    (init-pub-loop cfg)
    (init-sub-loop cfg)

    (with-meta
      (fn run
        ([command] (run command nil))
        ([command params]
         (a/go
           (case command
             :pub (a/>! pub-ch params)
             :sub (a/>! sub-ch params)))))
      cfg)))

(defmethod ig/halt-key! ::msgbus
  [_ f]
  (let [mdata (meta f)]
    (stop-backend mdata)
    (a/close! (::pub-ch mdata))
    (a/close! (::sub-ch mdata))))

;; --- IN-MEMORY BACKEND IMPL

(defmethod init-backend :memory [cfg] cfg)
(defmethod stop-backend :memory [_])
(defmethod init-pub-loop :memory [_])

(defmethod init-sub-loop :memory
  [{:keys [::sub-ch ::pub-ch]}]
  (a/go-loop [state {}]
    (let [[val port] (a/alts! [pub-ch sub-ch])]
      (cond
        (and (= port sub-ch) (some? val))
        (let [{:keys [topics chan]} val]
          (recur (reduce #(update %1 %2 (fnil conj #{}) chan) state topics)))

        (and (= port pub-ch) (some? val))
        (let [topic   (:topic val)
              message (:message val)
              state   (loop [state state
                             chans (get state topic)]
                        (if-let [c (first chans)]
                          (if (a/>! c message)
                            (recur state (rest chans))
                            (recur (update state topic disj c)
                                   (rest chans)))
                          state))]
          (recur state))

        :else
        (->> (vals state)
             (mapcat identity)
             (run! a/close!))))))


;; Add a unique listener to connection

;; --- REDIS BACKEND IMPL

(declare impl-redis-open?)
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
        (assoc ::pub-conn pub-conn)
        (assoc ::sub-conn sub-conn))))

(defmethod stop-backend :redis
  [{:keys [::pub-conn ::sub-conn] :as cfg}]
  (.close ^StatefulRedisConnection pub-conn)
  (.close ^StatefulRedisPubSubConnection sub-conn))

(defmethod init-pub-loop :redis
  [{:keys [::pub-conn ::pub-ch]}]
  (let [rac (.async ^StatefulRedisConnection pub-conn)]
    (a/go-loop []
      (when-let [val (a/<! pub-ch)]
        (let [result (a/<! (impl-redis-pub rac val))]
          (when (and (impl-redis-open? pub-conn)
                     (ex/exception? result))
            (l/error :cause result
                     :hint "unexpected error on publish message to redis")))
        (recur)))))

(defmethod init-sub-loop :redis
  [{:keys [::sub-conn ::sub-ch buffer-size]}]
  (let [rcv-ch  (a/chan (a/dropping-buffer buffer-size))
        chans   (agent {} :error-handler #(l/error :cause % :hint "unexpected error on agent"))
        rac     (.async ^StatefulRedisPubSubConnection sub-conn)]

    ;; Add a unique listener to connection
    (.addListener sub-conn
                  (reify RedisPubSubListener
                    (message [it pattern topic message])
                    (message [it topic message]
                      ;; There are no back pressure, so we use a sliding
                      ;; buffer for cases when the pubsub broker sends
                      ;; more messages that we can process.
                      (let [val {:topic topic :message (blob/decode message)}]
                        (when-not (a/offer! rcv-ch val)
                          (l/warn :msg "dropping message on subscription loop"))))
                    (psubscribed [it pattern count])
                    (punsubscribed [it pattern count])
                    (subscribed [it topic count])
                    (unsubscribed [it topic count])))

    (letfn [(subscribe-to-single-topic [nsubs topic chan]
              (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
                (when (= 1 (count nsubs))
                  (let [result (a/<!! (impl-redis-sub rac topic))]
                    (l/trace :action "open subscription"
                             :topic topic)
                    (when (ex/exception? result)
                      (l/error :cause result
                               :hint "unexpected exception on subscribing"
                               :topic topic))))
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
                    (l/trace :action "close subscription"
                             :topic topic)
                    (when (and (impl-redis-open? sub-conn)
                               (ex/exception? result))
                      (l/error :cause result
                               :hint "unexpected exception on unsubscribing"
                               :topic topic))))
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
        (if-let [{:keys [topics chan]} (a/<! sub-ch)]
          (do
            (send-off chans subscribe-to-topics topics chan)
            (recur))
          (a/close! rcv-ch)))

      ;; Asynchronous message processing loop;x
      (a/go-loop []
        (if-let [{:keys [topic message]} (a/<! rcv-ch)]
          ;; This means we receive data from redis and we need to
          ;; forward it to the underlying subscriptions.
          (let [pending (loop [chans   (seq (get-in @chans [:topics topic]))
                               pending #{}]
                          (if-let [ch (first chans)]
                            (if (a/>! ch message)
                              (recur (rest chans) pending)
                              (recur (rest chans) (conj pending ch)))
                            pending))]
            (some->> (seq pending)
                     (send-off chans unsubscribe-channels))

            (recur))

          ;; Stop condition; close all underlying subscriptions and
          ;; exit. The close operation is performed asynchronously.
          (send-off chans (fn [state]
                            (->> (vals state)
                                 (mapcat identity)
                                 (filter some?)
                                 (run! a/close!)))))))))


(defn- impl-redis-open?
  [^StatefulConnection conn]
  (.isOpen conn))

(defn- impl-redis-pub
  [^RedisAsyncCommands rac {:keys [topic message]}]
  (let [message (blob/encode message)
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

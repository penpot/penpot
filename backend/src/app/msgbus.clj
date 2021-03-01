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

(declare impl-publish-loop)
(declare impl-redis-pub)
(declare impl-redis-sub)
(declare impl-redis-unsub)
(declare impl-subscribe-loop)


;; --- STATE INIT: Publisher

(s/def ::uri ::us/string)
(s/def ::buffer-size ::us/integer)

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :req-un [::uri]
          :opt-un [::buffer-size]))

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (merge {:buffer-size 128} cfg))

(defmethod ig/init-key ::msgbus
  [_ {:keys [uri buffer-size] :as cfg}]
  (let [codec    (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE)

        uri      (RedisURI/create uri)
        rclient  (RedisClient/create ^RedisURI uri)

        snd-conn (.connect ^RedisClient rclient ^RedisCodec codec)
        rcv-conn (.connectPubSub ^RedisClient rclient ^RedisCodec codec)

        ;; Channel used for receive publications from the application.
        pub-chan (a/chan (a/dropping-buffer buffer-size))

        ;; Channel used for receive data from redis
        rcv-chan (a/chan (a/dropping-buffer buffer-size))

        ;; Channel used for receive subscription requests.
        sub-chan (a/chan)
        cch      (a/chan 1)]

    (.setTimeout ^StatefulRedisConnection snd-conn ^Duration (dt/duration {:seconds 10}))
    (.setTimeout ^StatefulRedisPubSubConnection rcv-conn ^Duration (dt/duration {:seconds 10}))

    (log/debugf "initializing msgbus (uri: '%s')" (str uri))

    ;; Start the sending (publishing) loop
    (impl-publish-loop snd-conn pub-chan cch)

    ;; Start the receiving (subscribing) loop
    (impl-subscribe-loop rcv-conn rcv-chan sub-chan cch)

    (with-meta
      (fn run
        ([command] (run command nil))
        ([command params]
         (a/go
           (case command
             :pub (a/>! pub-chan params)
             :sub (a/>! sub-chan params)))))

      {::snd-conn snd-conn
       ::rcv-conn rcv-conn
       ::cch cch
       ::pub-chan pub-chan
       ::rcv-chan rcv-chan})))

(defmethod ig/halt-key! ::msgbus
  [_ f]
  (let [mdata (meta f)]
    (.close ^StatefulRedisConnection (::snd-conn mdata))
    (.close ^StatefulRedisPubSubConnection (::rcv-conn mdata))
    (a/close! (::cch mdata))
    (a/close! (::pub-chan mdata))
    (a/close! (::rcv-chan mdata))))

(defn- impl-publish-loop
  [conn pub-chan cch]
  (let [rac (.async ^StatefulRedisConnection conn)]
    (a/go-loop []
      (let [[val _] (a/alts! [cch pub-chan] :priority true)]
        (when (some? val)
          (let [result (a/<! (impl-redis-pub rac val))]
            (when (ex/exception? result)
              (log/error result "unexpected error on publish message to redis")))
          (recur))))))

(defn- impl-subscribe-loop
  [conn rcv-chan sub-chan cch]
  ;; Add a unique listener to connection
  (.addListener conn (reify RedisPubSubListener
                       (message [it pattern topic message])
                       (message [it topic message]
                         ;; There are no back pressure, so we use a slidding
                         ;; buffer for cases when the pubsub broker sends
                         ;; more messages that we can process.
                         (let [val {:topic topic :message (blob/decode message)}]
                           (when-not (a/offer! rcv-chan val)
                             (log/warn "dropping message on subscription loop"))))
                       (psubscribed [it pattern count])
                       (punsubscribed [it pattern count])
                       (subscribed [it topic count])
                       (unsubscribed [it topic count])))

  (let [chans   (agent {} :error-handler #(log/error % "unexpected error on agent"))
        tprefix (str (cfg/get :tenant) ".")

        subscribe-to-single-topic
        (fn [nsubs topic chan]
          (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
            (when (= 1 (count nsubs))
              (let [result (a/<!! (impl-redis-sub conn topic))]
                (log/tracef "opening subscription to %s" topic)
                (when (ex/exception? result)
                  (log/errorf result "unexpected exception on subscribing to '%s'" topic))))
            nsubs))

        subscribe-to-topics
        (fn [state topics chan]
          (let [state  (update state :chans assoc chan topics)]
            (reduce (fn [state topic]
                      (update-in state [:topics topic] subscribe-to-single-topic topic chan))
                    state
                    topics)))

        unsubscribe-from-single-topic
        (fn [nsubs topic chan]
          (let [nsubs (disj nsubs chan)]
            (when (empty? nsubs)
              (let [result (a/<!! (impl-redis-unsub conn topic))]
                (log/tracef "closing subscription to %s" topic)
                (when (ex/exception? result)
                  (log/errorf result "unexpected exception on unsubscribing from '%s'" topic))))
            nsubs))

        unsubscribe-channels
        (fn [state pending]
          (reduce (fn [state ch]
                    (let [topics (get-in state [:chans ch])
                          state  (update state :chans dissoc ch)]
                      (reduce (fn [state topic]
                                (update-in state [:topics topic] unsubscribe-from-single-topic topic ch))
                              state
                              topics)))
                  state
                  pending))]

    ;; Asynchronous subscription loop; terminates when sub-chan is
    ;; closed.
    (a/go-loop []
      (when-let [{:keys [topics chan]} (a/<! sub-chan)]
        (let [topics (into #{} (map #(str tprefix %)) topics)]
          (send-off chans subscribe-to-topics topics chan)
          (recur))))

    (a/go-loop []
      (let [[val port] (a/alts! [cch rcv-chan])]
        (cond
          ;; Stop condition; close all underlying subscriptions and
          ;; exit. The close operation is performed asynchronously.
          (= port cch)
          (send-off chans (fn [state]
                            (log/tracef "close")
                            (->> (vals state)
                                 (mapcat identity)
                                 (filter some?)
                                 (run! a/close!))))

          ;; This means we receive data from redis and we need to
          ;; forward it to the underlying subscriptions.
          (= port rcv-chan)
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

            (recur)))))))

(defn- impl-redis-pub
  [rac {:keys [topic message]}]
  (let [topic   (str (cfg/get :tenant) "." topic)
        message (blob/encode message)
        res     (a/chan 1)]
    (-> (.publish ^RedisAsyncCommands rac ^String topic ^bytes message)
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

(defn impl-redis-sub
  [conn topic]
  (let [^RedisPubSubAsyncCommands cmd (.async ^StatefulRedisPubSubConnection conn)
        res (a/chan 1)]
    (-> (.subscribe cmd (into-array String [topic]))
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

(defn impl-redis-unsub
  [conn topic]
  (let [^RedisPubSubAsyncCommands cmd (.async ^StatefulRedisPubSubConnection conn)
        res (a/chan 1)]
    (-> (.unsubscribe cmd (into-array String [topic]))
        (p/finally (fn [_ e]
                     (when e (a/>!! res e))
                     (a/close! res))))
    res))

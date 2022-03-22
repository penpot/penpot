;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.config :as cfg]
   [app.util.async :as aa]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p])
  (:import
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
   io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
   io.lettuce.core.resource.ClientResources
   io.lettuce.core.resource.DefaultClientResources
   java.time.Duration))

(set! *warn-on-reflection* true)

(def ^:private prefix (cfg/get :tenant))

(defn- prefix-topic
  [topic]
  (str prefix "." topic))

(def ^:private xform-prefix-topic
  (map (fn [obj] (update obj :topic prefix-topic))))

(declare ^:private redis-connect)
(declare ^:private redis-disconnect)
(declare ^:private start-io-loop)
(declare ^:private subscribe)
(declare ^:private purge)
(declare ^:private redis-pub)
(declare ^:private redis-sub)
(declare ^:private redis-unsub)

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (merge {:buffer-size 128
          :timeout (dt/duration {:seconds 30})}
         (d/without-nils cfg)))

(s/def ::timeout ::dt/duration)
(s/def ::redis-uri ::us/string)
(s/def ::buffer-size ::us/integer)

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :req-un [::buffer-size ::redis-uri ::timeout ::wrk/executor]))

(defmethod ig/init-key ::msgbus
  [_ {:keys [buffer-size redis-uri] :as cfg}]
  (l/info :hint "initialize msgbus"
          :buffer-size buffer-size
          :redis-uri redis-uri)
  (let [cmd-ch (a/chan buffer-size)
        rcv-ch (a/chan (a/dropping-buffer buffer-size))
        pub-ch (a/chan (a/dropping-buffer buffer-size) xform-prefix-topic)
        state  (agent {} :error-handler #(l/error :cause % :hint "unexpected error on agent" ::l/async false))
        cfg    (-> (redis-connect cfg)
                   (assoc ::cmd-ch cmd-ch)
                   (assoc ::rcv-ch rcv-ch)
                   (assoc ::pub-ch pub-ch)
                   (assoc ::state state))]

    (start-io-loop cfg)

    (with-meta
      (fn [& {:keys [cmd] :as params}]
        (a/go
          (case cmd
            :pub   (a/>! pub-ch params)
            :sub   (a/<! (subscribe cfg params))
            :purge (a/<! (purge cfg params))
            (l/error :hint "unexpeced error on msgbus command processing" :params params))))
      cfg)))

(defmethod ig/halt-key! ::msgbus
  [_ f]
  (let [mdata (meta f)]
    (redis-disconnect mdata)
    (a/close! (::cmd-ch mdata))
    (a/close! (::rcv-ch mdata))))

;; --- IMPL

(defn- redis-connect
  [{:keys [redis-uri timeout] :as cfg}]
  (let [codec     (RedisCodec/of StringCodec/UTF8 ByteArrayCodec/INSTANCE)

        resources (.. (DefaultClientResources/builder)
                      (ioThreadPoolSize 4)
                      (computationThreadPoolSize 4)
                      (build))

        uri       (RedisURI/create redis-uri)
        rclient   (RedisClient/create ^ClientResources resources ^RedisURI uri)

        pconn     (.connect ^RedisClient rclient ^RedisCodec codec)
        sconn     (.connectPubSub ^RedisClient rclient ^RedisCodec codec)]

    (.setTimeout ^StatefulRedisConnection pconn ^Duration timeout)
    (.setTimeout ^StatefulRedisPubSubConnection sconn ^Duration timeout)

    (-> cfg
        (assoc ::resources resources)
        (assoc ::pconn pconn)
        (assoc ::sconn sconn))))

(defn- redis-disconnect
  [{:keys [::pconn ::sconn ::resources] :as cfg}]
  (.. ^StatefulConnection pconn close)
  (.. ^StatefulConnection sconn close)
  (.shutdown ^ClientResources resources))

(defn- conj-subscription
  "A low level function that is responsible to create on-demand
  subscriptions on redis. It reuses the same subscription if it is
  already established. Intended to be executed in agent."
  [nsubs cfg topic chan]
  (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
    (when (= 1 (count nsubs))
      (l/trace :hint "open subscription" :topic topic ::l/async false)
      (redis-sub cfg topic))
    nsubs))

(defn- disj-subscription
  "A low level function responsible on removing subscriptions. The
  subscription is trully removed from redis once no single local
  subscription is look for it. Intended to be executed in agent."
  [nsubs cfg topic chan]
  (let [nsubs (disj nsubs chan)]
    (when (empty? nsubs)
      (l/trace :hint "close subscription" :topic topic ::l/async false)
      (redis-unsub cfg topic))
    nsubs))

(defn- subscribe-to-topics
  "Function responsible to attach local subscription to the
  state. Intended to be used in agent."
  [state cfg topics chan done-ch]
  (l/trace :hint "subscribe-to-topics" :topics topics ::l/async false)
  (aa/with-closing done-ch
    (let [state (update state :chans assoc chan topics)]
      (reduce (fn [state topic]
                (update-in state [:topics topic] conj-subscription cfg topic chan))
              state
              topics))))

(defn- unsubscribe-single-channel
  "Auxiliar function responsible on removing a single local
  subscription from the state."
  [state cfg chan]
  (let [topics (get-in state [:chans chan])
        state  (update state :chans dissoc chan)]
    (reduce (fn [state topic]
              (update-in state [:topics topic] disj-subscription cfg topic chan))
            state
            topics)))

(defn- unsubscribe-channels
  "Function responsible from detach from state a seq of channels,
  useful when client disconnects or in-bulk unsubscribe
  operations. Intended to be executed in agent."
  [state cfg channels done-ch]
  (l/trace :hint "unsubscribe-channels" :chans (count channels) ::l/async false)
  (aa/with-closing done-ch
    (reduce #(unsubscribe-single-channel %1 cfg %2) state channels)))

(defn- subscribe
  [{:keys [::state executor] :as cfg} {:keys [topic topics chan]}]
  (let [done-ch (a/chan)
        topics  (into [] (map prefix-topic) (if topic [topic] topics))]
    (l/trace :hint "subscribe" :topics topics)
    (send-via executor state subscribe-to-topics cfg topics chan done-ch)
    done-ch))

(defn- purge
  [{:keys [::state executor] :as cfg} {:keys [chans]}]
  (l/trace :hint "purge" :chans (count chans))
  (let [done-ch (a/chan)]
    (send-via executor state unsubscribe-channels cfg chans done-ch)
    done-ch))

(defn- create-listener
  [rcv-ch]
  (reify RedisPubSubListener
    (message [_ _pattern _topic _message])
    (message [_ topic message]
      ;; There are no back pressure, so we use a slidding
      ;; buffer for cases when the pubsub broker sends
      ;; more messages that we can process.
      (let [val {:topic topic :message (t/decode message)}]
        (when-not (a/offer! rcv-ch val)
          (l/warn :msg "dropping message on subscription loop"))))
    (psubscribed [_ _pattern _count])
    (punsubscribed [_ _pattern _count])
    (subscribed [_ _topic _count])
    (unsubscribed [_ _topic _count])))

(defn start-io-loop
  [{:keys [::sconn ::rcv-ch ::pub-ch ::state executor] :as cfg}]

  ;; Add a single listener to the pubsub connection
  (.addListener ^StatefulRedisPubSubConnection sconn
                ^RedisPubSubListener (create-listener rcv-ch))

  (letfn [(send-to-topic [topic message]
            (a/go-loop [chans  (seq (get-in @state [:topics topic]))
                        closed #{}]
              (if-let [ch (first chans)]
                (if (a/>! ch message)
                  (recur (rest chans) closed)
                  (recur (rest chans) (conj closed ch)))
                (seq closed))))

          (process-incoming [{:keys [topic message]}]
            (a/go
              (when-let [closed (a/<! (send-to-topic topic message))]
                (send-via executor state unsubscribe-channels cfg closed nil))))
          ]

  (a/go-loop []
    (let [[val port] (a/alts! [pub-ch rcv-ch])]
      (cond
        (nil? val)
        (do
          (l/trace :hint "stoping io-loop, nil received")
          (send-via executor state (fn [state]
                                     (->> (vals state)
                                          (mapcat identity)
                                          (filter some?)
                                          (run! a/close!))
                                     nil)))

        (= port rcv-ch)
        (do
          (a/<! (process-incoming val))
          (recur))

        (= port pub-ch)
        (let [result (a/<! (redis-pub cfg val))]
          (when (ex/exception? result)
            (l/error :hint "unexpected error on publishing" :message val
                     :cause result))
          (recur)))))))

(defn- redis-pub
  "Publish a message to the redis server. Asynchronous operation,
  intended to be used in core.async go blocks."
  [{:keys [::pconn] :as cfg} {:keys [topic message]}]
  (let [message (t/encode message)
        res     (a/chan 1)
        pcomm   (.async ^StatefulRedisConnection pconn)]
    (-> (.publish ^RedisAsyncCommands pcomm ^String topic ^bytes message)
        (p/finally (fn [_ cause]
                     (when (and cause (.isOpen ^StatefulConnection pconn))
                       (a/offer! res cause))
                     (a/close! res))))
    res))

(defn redis-sub
  "Create redis subscription. Blocking operation, intended to be used
  inside an agent."
  [{:keys [::sconn] :as cfg} topic]
  (let [topic (into-array String [topic])
        scomm (.sync ^StatefulRedisPubSubConnection sconn)]
    (.subscribe ^RedisPubSubCommands scomm topic)))

(defn redis-unsub
  "Removes redis subscription. Blocking operation, intended to be used
  inside an agent."
  [{:keys [::sconn] :as cfg} topic]
  (let [topic (into-array String [topic])
        scomm (.sync ^StatefulRedisPubSubConnection sconn)]
    (.unsubscribe ^RedisPubSubCommands scomm topic)))

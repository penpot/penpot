;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.config :as cfg]
   [app.redis :as redis]
   [app.util.async :as aa]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]))

(set! *warn-on-reflection* true)

(def ^:private prefix (cfg/get :tenant))

(defn- prefix-topic
  [topic]
  (str prefix "." topic))

(def ^:private xform-prefix-topic
  (map (fn [obj] (update obj :topic prefix-topic))))

(declare ^:private redis-connect)
(declare ^:private redis-disconnect)
(declare ^:private redis-pub)
(declare ^:private redis-sub)
(declare ^:private redis-unsub)
(declare ^:private start-io-loop!)
(declare ^:private subscribe-to-topics)
(declare ^:private unsubscribe-channels)

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (merge {:buffer-size 128
          :timeout (dt/duration {:seconds 30})}
         (d/without-nils cfg)))

(s/def ::cmd-ch ::aa/channel)
(s/def ::rcv-ch ::aa/channel)
(s/def ::pub-ch ::aa/channel)
(s/def ::state ::us/agent)
(s/def ::pconn ::redis/connection-holder)
(s/def ::sconn ::redis/connection-holder)
(s/def ::msgbus
  (s/keys :req [::cmd-ch ::rcv-ch ::pub-ch ::state ::pconn ::sconn ::wrk/executor]))

(s/def ::buffer-size ::us/integer)

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :req-un [::buffer-size ::redis/timeout ::redis/redis ::wrk/executor]))

(defmethod ig/init-key ::msgbus
  [_ {:keys [buffer-size executor] :as cfg}]
  (l/info :hint "initialize msgbus" :buffer-size buffer-size)
  (let [cmd-ch (a/chan buffer-size)
        rcv-ch (a/chan (a/dropping-buffer buffer-size))
        pub-ch (a/chan (a/dropping-buffer buffer-size) xform-prefix-topic)
        state  (agent {})
        msgbus (-> (redis-connect cfg)
                   (assoc ::cmd-ch cmd-ch)
                   (assoc ::rcv-ch rcv-ch)
                   (assoc ::pub-ch pub-ch)
                   (assoc ::state state)
                   (assoc ::wrk/executor executor))]

    (us/verify! ::msgbus msgbus)

    (set-error-handler! state #(l/error :cause % :hint "unexpected error on agent" ::l/sync? true))
    (set-error-mode! state :continue)
    (start-io-loop! msgbus)

    msgbus))

(defn sub!
  [{:keys [::state ::wrk/executor] :as cfg} & {:keys [topic topics chan]}]
  (let [done-ch (a/chan)
        topics  (into [] (map prefix-topic) (if topic [topic] topics))]
    (l/debug :hint "subscribe" :topics topics)
    (send-via executor state subscribe-to-topics cfg topics chan done-ch)
    done-ch))

(defn pub!
  [{::keys [pub-ch]} & {:as params}]
  (a/go
    (a/>! pub-ch params)))

(defn purge!
  [{:keys [::state ::wrk/executor] :as msgbus} chans]
  (l/trace :hint "purge" :chans (count chans))
  (let [done-ch (a/chan)]
    (send-via executor state unsubscribe-channels msgbus chans done-ch)
    done-ch))

(defmethod ig/halt-key! ::msgbus
  [_ msgbus]
  (redis-disconnect msgbus)
  (a/close! (::cmd-ch msgbus))
  (a/close! (::rcv-ch msgbus))
  (a/close! (::pub-ch msgbus)))

;; --- IMPL

(defn- redis-connect
  [{:keys [timeout redis] :as cfg}]
  (let [pconn (redis/connect redis :timeout timeout)
        sconn (redis/connect redis :type :pubsub :timeout timeout)]
    {::pconn pconn
     ::sconn sconn}))

(defn- redis-disconnect
  [{:keys [::pconn ::sconn] :as cfg}]
  (d/close! pconn)
  (d/close! sconn))

(defn- conj-subscription
  "A low level function that is responsible to create on-demand
  subscriptions on redis. It reuses the same subscription if it is
  already established. Intended to be executed in agent."
  [nsubs cfg topic chan]
  (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
    (when (= 1 (count nsubs))
      (l/trace :hint "open subscription" :topic topic ::l/sync? true)
      (redis-sub cfg topic))
    nsubs))

(defn- disj-subscription
  "A low level function responsible on removing subscriptions. The
  subscription is truly removed from redis once no single local
  subscription is look for it. Intended to be executed in agent."
  [nsubs cfg topic chan]
  (let [nsubs (disj nsubs chan)]
    (when (empty? nsubs)
      (l/trace :hint "close subscription" :topic topic ::l/sync? true)
      (redis-unsub cfg topic))
    nsubs))

(defn- subscribe-to-topics
  "Function responsible to attach local subscription to the
  state. Intended to be used in agent."
  [state cfg topics chan done-ch]
  (aa/with-closing done-ch
    (let [state (update state :chans assoc chan topics)]
      (reduce (fn [state topic]
                (update-in state [:topics topic] conj-subscription cfg topic chan))
              state
              topics))))

(defn- unsubscribe-single-channel
  "Auxiliary function responsible on removing a single local
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
  (aa/with-closing done-ch
    (reduce #(unsubscribe-single-channel %1 cfg %2) state channels)))

(defn- create-listener
  [rcv-ch]
  (redis/pubsub-listener
   :on-message (fn [_ topic message]
                 ;; There are no back pressure, so we use a slidding
                 ;; buffer for cases when the pubsub broker sends
                 ;; more messages that we can process.
                 (let [val {:topic topic :message (t/decode message)}]
                   (when-not (a/offer! rcv-ch val)
                     (l/warn :msg "dropping message on subscription loop"))))))

(defn start-io-loop!
  [{:keys [::sconn ::rcv-ch ::pub-ch ::state ::wrk/executor] :as cfg}]
  (redis/add-listener! sconn (create-listener rcv-ch))
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
    (px/thread
      {:name "penpot/msgbus-io-loop"}
      (loop []
        (let [[val port] (a/alts!! [pub-ch rcv-ch])]
          (cond
            (nil? val)
            (do
              (l/trace :hint "stopping io-loop, nil received")
              (send-via executor state (fn [state]
                                         (->> (vals state)
                                              (mapcat identity)
                                              (filter some?)
                                              (run! a/close!))
                                         nil)))

            (= port rcv-ch)
            (do
              (a/<!! (process-incoming val))
              (recur))

            (= port pub-ch)
            (let [result (a/<!! (redis-pub cfg val))]
              (when (ex/exception? result)
                (l/error :hint "unexpected error on publishing"
                         :message val
                         :cause result))
              (recur))))))))

(defn- redis-pub
  "Publish a message to the redis server. Asynchronous operation,
  intended to be used in core.async go blocks."
  [{:keys [::pconn] :as cfg} {:keys [topic message]}]
  (let [message (t/encode message)
        res     (a/chan 1)]
    (-> (redis/publish! pconn topic message)
        (p/finally (fn [_ cause]
                     (when (and cause (redis/open? pconn))
                       (a/offer! res cause))
                     (a/close! res))))
    res))

(defn redis-sub
  "Create redis subscription. Blocking operation, intended to be used
  inside an agent."
  [{:keys [::sconn] :as cfg} topic]
  (redis/subscribe! sconn topic))

(defn redis-unsub
  "Removes redis subscription. Blocking operation, intended to be used
  inside an agent."
  [{:keys [::sconn] :as cfg} topic]
  (redis/unsubscribe! sconn topic))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.config :as cfg]
   [app.redis :as rds]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(set! *warn-on-reflection* true)

(def ^:private prefix (cfg/get :tenant))

(defn- prefix-topic
  [topic]
  (str prefix "." topic))

(def ^:private xform-prefix-topic
  (map (fn [obj] (update obj :topic prefix-topic))))

(declare ^:private redis-pub!)
(declare ^:private redis-sub!)
(declare ^:private redis-unsub!)
(declare ^:private start-io-loop!)
(declare ^:private subscribe-to-topics)
(declare ^:private unsubscribe-channels)

(s/def ::cmd-ch sp/chan?)
(s/def ::rcv-ch sp/chan?)
(s/def ::pub-ch sp/chan?)
(s/def ::state ::us/agent)
(s/def ::pconn ::rds/connection-holder)
(s/def ::sconn ::rds/connection-holder)
(s/def ::msgbus
  (s/keys :req [::cmd-ch ::rcv-ch ::pub-ch ::state ::pconn ::sconn ::wrk/executor]))

(defmethod ig/pre-init-spec ::msgbus [_]
  (s/keys :req [::rds/redis ::wrk/executor]))

(defmethod ig/prep-key ::msgbus
  [_ cfg]
  (-> cfg
      (assoc ::buffer-size 128)
      (assoc ::timeout (dt/duration {:seconds 30}))))

(defmethod ig/init-key ::msgbus
  [_ {:keys [::buffer-size ::wrk/executor ::timeout ::rds/redis] :as cfg}]
  (l/info :hint "initialize msgbus" :buffer-size buffer-size)
  (let [cmd-ch (sp/chan :buf buffer-size)
        rcv-ch (sp/chan :buf (sp/dropping-buffer buffer-size))
        pub-ch (sp/chan :buf (sp/dropping-buffer buffer-size)
                        :xf  xform-prefix-topic)
        state  (agent {})

        pconn  (rds/connect redis :timeout timeout)
        sconn  (rds/connect redis :type :pubsub :timeout timeout)
        msgbus (-> cfg
                   (assoc ::pconn pconn)
                   (assoc ::sconn sconn)
                   (assoc ::cmd-ch cmd-ch)
                   (assoc ::rcv-ch rcv-ch)
                   (assoc ::pub-ch pub-ch)
                   (assoc ::state state)
                   (assoc ::wrk/executor executor))]

    (set-error-handler! state #(l/error :cause % :hint "unexpected error on agent" ::l/sync? true))
    (set-error-mode! state :continue)

    (assoc msgbus ::io-thr (start-io-loop! msgbus))))

(defmethod ig/halt-key! ::msgbus
  [_ msgbus]
  (px/interrupt! (::io-thr msgbus))
  (sp/close! (::cmd-ch msgbus))
  (sp/close! (::rcv-ch msgbus))
  (sp/close! (::pub-ch msgbus))
  (d/close! (::pconn msgbus))
  (d/close! (::sconn msgbus)))

(defn sub!
  [{:keys [::state ::wrk/executor] :as cfg} & {:keys [topic topics chan]}]
  (let [topics (into [] (map prefix-topic) (if topic [topic] topics))]
    (l/debug :hint "subscribe" :topics topics :chan (hash chan))
    (send-via executor state subscribe-to-topics cfg topics chan)
    nil))

(defn pub!
  [{::keys [pub-ch]} & {:as params}]
  (sp/put! pub-ch params))

(defn purge!
  [{:keys [::state ::wrk/executor] :as msgbus} chans]
  (l/debug :hint "purge" :chans (count chans))
  (send-via executor state unsubscribe-channels msgbus chans)
  nil)

;; --- IMPL

(defn- conj-subscription
  "A low level function that is responsible to create on-demand
  subscriptions on redis. It reuses the same subscription if it is
  already established."
  [nsubs cfg topic chan]
  (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
    (when (= 1 (count nsubs))
      (l/trace :hint "open subscription" :topic topic ::l/sync? true)
      (redis-sub! cfg topic))
    nsubs))

(defn- disj-subscription
  "A low level function responsible on removing subscriptions. The
  subscription is truly removed from redis once no single local
  subscription is look for it."
  [nsubs cfg topic chan]
  (let [nsubs (disj nsubs chan)]
    (when (empty? nsubs)
      (l/trace :hint "close subscription" :topic topic ::l/sync? true)
      (redis-unsub! cfg topic))
    nsubs))

(defn- subscribe-to-topics
  "Function responsible to attach local subscription to the state."
  [state cfg topics chan]
  (let [state (update state :chans assoc chan topics)]
    (reduce (fn [state topic]
              (update-in state [:topics topic] conj-subscription cfg topic chan))
            state
            topics)))

(defn- unsubscribe-channel
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
  [state cfg channels]
  (reduce #(unsubscribe-channel %1 cfg %2) state channels))

(defn- create-listener
  [rcv-ch]
  (rds/pubsub-listener
   :on-message (fn [_ topic message]
                 ;; There are no back pressure, so we use a slidding
                 ;; buffer for cases when the pubsub broker sends
                 ;; more messages that we can process.
                 (let [val {:topic topic :message (t/decode message)}]
                   (when-not (sp/offer! rcv-ch val)
                     (l/warn :msg "dropping message on subscription loop"))))))

(defn- process-input!
  [{:keys [::state ::wrk/executor] :as cfg} topic message]
  (let [chans (get-in @state [:topics topic])]
    (when-let [closed (loop [chans  (seq chans)
                             closed #{}]
                        (if-let [ch (first chans)]
                          (if (sp/put! ch message)
                            (recur (rest chans) closed)
                            (recur (rest chans) (conj closed ch)))
                          (seq closed)))]
      (send-via executor state unsubscribe-channels cfg closed))))


(defn start-io-loop!
  [{:keys [::sconn ::rcv-ch ::pub-ch ::state ::wrk/executor] :as cfg}]
  (rds/add-listener! sconn (create-listener rcv-ch))

  (px/thread
    {:name "penpot/msgbus/io-loop"
     :virtual true}
    (try
      (loop []
        (let [timeout-ch (sp/timeout-chan 1000)
              [val port] (sp/alts! [timeout-ch pub-ch rcv-ch])]
          (cond
            (identical? port timeout-ch)
            (let [closed (->> (:chans @state)
                              (map key)
                              (filter sp/closed?))]
              (when (seq closed)
                (send-via executor state unsubscribe-channels cfg closed)
                (l/debug :hint "proactively purge channels" :count (count closed)))
              (recur))

            (nil? val)
            (throw (InterruptedException. "internally interrupted"))

            (identical? port rcv-ch)
            (let [{:keys [topic message]} val]
              (process-input! cfg topic message)
              (recur))

            (identical? port pub-ch)
            (do
              (redis-pub! cfg val)
              (recur)))))

      (catch InterruptedException _
        (l/trace :hint "io-loop thread interrumpted"))

      (catch Throwable cause
        (l/error :hint "unexpected exception on io-loop thread"
                 :cause cause))
      (finally
        (l/trace :hint "clearing io-loop state")
        (when-let [chans (:chans @state)]
          (run! sp/close! (keys chans)))

        (l/debug :hint "io-loop thread terminated")))))


(defn- redis-pub!
  "Publish a message to the redis server. Asynchronous operation,
  intended to be used in core.async go blocks."
  [{:keys [::pconn] :as cfg} {:keys [topic message]}]
  (try
    (p/await! (rds/publish! pconn topic (t/encode message)))
    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (l/error :hint "unexpected error on publishing"
               :message message
               :cause cause))))

(defn- redis-sub!
  "Create redis subscription. Blocking operation, intended to be used
  inside an agent."
  [{:keys [::sconn] :as cfg} topic]
  (try
    (rds/subscribe! sconn topic)
    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (l/trace :hint "exception on subscribing" :topic topic :cause cause))))

(defn- redis-unsub!
  "Removes redis subscription. Blocking operation, intended to be used
  inside an agent."
  [{:keys [::sconn] :as cfg} topic]
  (try
    (rds/unsubscribe! sconn topic)
    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (l/trace :hint "exception on unsubscribing" :topic topic :cause cause))))


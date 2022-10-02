;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.websocket
  "A penpot notification service for file cooperative edition."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.util.time :as dt]
   [app.util.websocket :as ws]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [yetti.websocket :as yws]))

(def recv-labels
  (into-array String ["recv"]))

(def send-labels
  (into-array String ["send"]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBSOCKET HOOKS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state (atom {}))

(defn- on-connect
  [{:keys [metrics]} wsp]
  (let [created-at (dt/now)]
    (swap! state assoc (::ws/id @wsp) wsp)
    (mtx/run! metrics
              :id :websocket-active-connections
              :inc 1)
    (fn []
      (swap! state dissoc (::ws/id @wsp))
      (mtx/run! metrics :id :websocket-active-connections :dec 1)
      (mtx/run! metrics
                :id :websocket-session-timing
                :val (/ (inst-ms (dt/diff created-at (dt/now))) 1000.0)))))

(defn- on-rcv-message
  [{:keys [metrics]} _ message]
  (mtx/run! metrics
            :id :websocket-messages-total
            :labels recv-labels
            :inc 1)
  message)

(defn- on-snd-message
  [{:keys [metrics]} _ message]
  (mtx/run! metrics
            :id :websocket-messages-total
            :labels send-labels
            :inc 1)
  message)

;; REPL HELPERS

(defn repl-get-connections-for-file
  [file-id]
  (->> (vals @state)
       (filter #(= file-id (-> % deref ::file-subscription :file-id)))
       (map deref)
       (map ::ws/id)))

(defn repl-get-connections-for-team
  [team-id]
  (->> (vals @state)
       (filter #(= team-id (-> % deref ::team-subscription :team-id)))
       (map deref)
       (map ::ws/id)))

(defn repl-close-connection
  [id]
  (when-let [wsp (get @state id)]
    (a/>!! (::ws/close-ch @wsp) [8899 "closed from server"])
    (a/close! (::ws/close-ch @wsp))))

(defn repl-get-connection-info
  [id]
  (when-let [wsp (get @state id)]
    {:id               id
     :created-at       (::created-at @wsp)
     :profile-id       (::profile-id @wsp)
     :session-id       (::session-id @wsp)
     :user-agent       (::ws/user-agent @wsp)
     :ip-addr          (::ws/remote-addr @wsp)
     :last-activity-at (::ws/last-activity-at @wsp)
     :http-session-id  (::ws/http-session-id @wsp)
     :subscribed-file  (-> wsp deref ::file-subscription :file-id)
     :subscribed-team  (-> wsp deref ::team-subscription :team-id)}))

(defn repl-print-connection-info
  [id]
  (some-> id repl-get-connection-info pp/pprint))

(defn repl-print-connection-info-for-file
  [file-id]
  (some->> (repl-get-connections-for-file file-id)
           (map repl-get-connection-info)
           (pp/pprint)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBSOCKET HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti handle-message
  (fn [_ _ message]
    (:type message)))

(defmethod handle-message :connect
  [cfg wsp _]

  (let [msgbus     (:msgbus cfg)
        conn-id    (::ws/id @wsp)
        profile-id (::profile-id @wsp)
        session-id (::session-id @wsp)
        output-ch  (::ws/output-ch @wsp)

        xform      (remove #(= (:session-id %) session-id))
        channel    (a/chan (a/dropping-buffer 16) xform)]

    (l/trace :fn "handle-message" :event "connect" :conn-id conn-id)

    ;; Subscribe to the profile channel and forward all messages to
    ;; websocket output channel (send them to the client).
    (swap! wsp assoc ::profile-subscription channel)
    (a/pipe channel output-ch false)
    (mbus/sub! msgbus :topic profile-id :chan channel)))

(defmethod handle-message :disconnect
  [cfg wsp _]
  (let [msgbus     (:msgbus cfg)
        conn-id    (::ws/id @wsp)
        profile-id (::profile-id @wsp)
        session-id (::session-id @wsp)
        profile-ch (::profile-subscription @wsp)
        fsub       (::file-subscription @wsp)
        tsub       (::team-subscription @wsp)

        message    {:type :disconnect
                    :subs-id profile-id
                    :profile-id profile-id
                    :session-id session-id}]

    (l/trace :fn "handle-message"
             :event :disconnect
             :conn-id conn-id)

    (a/go
      ;; Close the main profile subscription
      (a/close! profile-ch)
      (a/<! (mbus/purge! msgbus [profile-ch]))

      ;; Close tram subscription if exists
      (when-let [channel (:channel tsub)]
        (a/close! channel)
        (a/<! (mbus/purge! msgbus channel)))

      (when-let [{:keys [topic channel]} fsub]
        (a/close! channel)
        (a/<! (mbus/purge! msgbus channel))
        (a/<! (mbus/pub! msgbus :topic topic :message message))))))

(defmethod handle-message :subscribe-team
  [cfg wsp {:keys [team-id] :as params}]
  (let [msgbus     (:msgbus cfg)
        conn-id    (::ws/id @wsp)
        session-id (::session-id @wsp)
        output-ch  (::ws/output-ch @wsp)
        prev-subs  (get @wsp ::team-subscription)
        xform      (comp
                    (remove #(= (:session-id %) session-id))
                    (map #(assoc % :subs-id team-id)))

        channel    (a/chan (a/dropping-buffer 64) xform)]

    (l/trace :fn "handle-message"
             :event :subscribe-team
             :team-id team-id
             :conn-id conn-id)

    (a/pipe channel output-ch false)

    (let [state {:team-id team-id :channel channel :topic team-id}]
      (swap! wsp assoc ::team-subscription state))

    (a/go
      ;; Close previous subscription if exists
      (when-let [channel (:channel prev-subs)]
        (a/close! channel)
        (a/<! (mbus/purge! msgbus channel))))

    (a/go
      (a/<! (mbus/sub! msgbus :topic team-id :chan channel)))))

(defmethod handle-message :subscribe-file
  [cfg wsp {:keys [file-id] :as params}]
  (let [msgbus     (:msgbus cfg)
        conn-id    (::ws/id @wsp)
        profile-id (::profile-id @wsp)
        session-id (::session-id @wsp)
        output-ch  (::ws/output-ch @wsp)
        prev-subs  (::file-subscription @wsp)
        xform      (comp (remove #(= (:session-id %) session-id))
                         (map #(assoc % :subs-id file-id)))
        channel    (a/chan (a/dropping-buffer 64) xform)]

    (l/trace :fn "handle-message"
             :event :subscribe-file
             :file-id file-id
             :conn-id conn-id)

    (let [state {:file-id file-id :channel channel :topic file-id}]
      (swap! wsp assoc ::file-subscription state))

    (a/go
      ;; Close previous subscription if exists
      (when-let [channel (:channel prev-subs)]
        (a/close! channel)
        (a/<! (mbus/purge! msgbus channel))))

    ;; Message forwarding
    (a/go
      (loop []
        (when-let [{:keys [type] :as message} (a/<! channel)]
          (when (or (= :join-file type)
                    (= :leave-file type)
                    (= :disconnect type))
            (let [message {:type :presence
                           :file-id file-id
                           :session-id session-id
                           :profile-id profile-id}]
              (a/<! (mbus/pub! msgbus :topic file-id :message message))))
          (a/>! output-ch message)
          (recur))))

    (a/go
      ;; Subscribe to file topic
      (a/<! (mbus/sub! msgbus :topic file-id :chan channel))

      ;; Notifify the rest of participants of the new connection.
      (let [message {:type :join-file
                     :file-id file-id
                     :subs-id file-id
                     :session-id session-id
                     :profile-id profile-id}]
        (a/<! (mbus/pub! msgbus :topic file-id :message message))))))

(defmethod handle-message :unsubscribe-file
  [cfg wsp {:keys [file-id] :as params}]
  (let [msgbus     (:msgbus cfg)
        conn-id    (::ws/id @wsp)
        session-id (::session-id @wsp)
        profile-id (::profile-id @wsp)
        subs       (::file-subscription @wsp)

        message    {:type :leave-file
                    :file-id file-id
                    :session-id session-id
                    :profile-id profile-id}]

    (l/trace :fn "handle-message"
             :event :unsubscribe-file
             :file-id file-id
             :conn-id conn-id)

    (a/go
      (when (= (:file-id subs) file-id)
        (let [channel (:channel subs)]
          (a/close! channel)
          (a/<! (mbus/purge! msgbus channel))
          (a/<! (mbus/pub! msgbus :topic file-id :message message)))))))

(defmethod handle-message :keepalive
  [_ _ _]
  (l/trace :fn "handle-message" :event :keepalive)
  (a/go :nothing))

(defmethod handle-message :pointer-update
  [cfg wsp {:keys [file-id] :as message}]
  (let [msgbus     (:msgbus cfg)
        profile-id (::profile-id @wsp)
        session-id (::session-id @wsp)
        subs       (::file-subscription @wsp)
        message    (-> message
                       (assoc :subs-id file-id)
                       (assoc :profile-id profile-id)
                       (assoc :session-id session-id))]
    (a/go
      ;; Only allow receive pointer updates when active subscription
      (when subs
        (a/<! (mbus/pub! msgbus :topic file-id :message message))))))

(defmethod handle-message :default
  [_ wsp message]
  (let [conn-id (::ws/id @wsp)]
    (l/warn :hint "received unexpected message"
            :message message
            :conn-id conn-id)
    (a/go :none)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::msgbus ::mbus/msgbus)
(s/def ::session-id ::us/uuid)

(s/def ::handler-params
  (s/keys :req-un [::session-id]))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::msgbus ::db/pool ::mtx/metrics]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [profile-id params] :as req} respond raise]
    (let [{:keys [session-id]} (us/conform ::handler-params params)]
      (cond
        (not profile-id)
        (raise (ex/error :type :authentication
                         :hint "Authentication required."))

        (not (yws/upgrade-request? req))
        (raise (ex/error :type :validation
                         :code :websocket-request-expected
                         :hint "this endpoint only accepts websocket connections"))

        :else
        (do
          (l/trace :hint "websocket request" :profile-id profile-id :session-id session-id)

          (->> (ws/handler
                ::ws/on-rcv-message (partial on-rcv-message cfg)
                ::ws/on-snd-message (partial on-snd-message cfg)
                ::ws/on-connect (partial on-connect cfg)
                ::ws/handler (partial handle-message cfg)
                ::profile-id profile-id
                ::session-id session-id)
               (yws/upgrade req)
               (respond)))))))

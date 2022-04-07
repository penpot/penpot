;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.websocket
  "A penpot notification service for file cooperative edition."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.websocket :as ws]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [yetti.websocket :as yws]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WEBSOCKET HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti handle-message
  (fn [_ message]
    (:type message)))

(defmethod handle-message :connect
  [wsp _]
  (l/trace :fn "handle-message" :event :connect)

  (let [msgbus-fn  (:msgbus @wsp)
        profile-id (::profile-id @wsp)
        session-id (::session-id @wsp)
        output-ch  (::ws/output-ch @wsp)

        xform      (remove #(= (:session-id %) session-id))
        channel    (a/chan (a/dropping-buffer 16) xform)]

    (swap! wsp assoc ::profile-subs-channel channel)
    (a/pipe channel output-ch false)
    (msgbus-fn :cmd :sub :topic profile-id :chan channel)))

(defmethod handle-message :disconnect
  [wsp _]
  (l/trace :fn "handle-message" :event :disconnect)
  (a/go
    (let [msgbus-fn  (:msgbus @wsp)
          profile-id (::profile-id @wsp)
          session-id (::session-id @wsp)
          profile-ch (::profile-subs-channel @wsp)
          subs       (::subscriptions @wsp)]

      ;; Close the main profile subscription
      (a/close! profile-ch)
      (a/<! (msgbus-fn :cmd :purge :chans [profile-ch]))

      ;; Close all other active subscrption on this websocket context.
      (doseq [{:keys [channel topic]} (map second subs)]
        (a/close! channel)
        (a/<! (msgbus-fn :cmd :pub :topic topic
                         :message {:type :disconnect
                                   :profile-id profile-id
                                   :session-id session-id}))
        (a/<! (msgbus-fn :cmd :purge :chans [channel]))))))

(defmethod handle-message :subscribe-team
  [wsp {:keys [team-id] :as params}]
  (l/trace :fn "handle-message" :event :subscribe-team :team-id team-id)

  (let [msgbus-fn  (:msgbus @wsp)
        session-id (::session-id @wsp)
        output-ch  (::ws/output-ch @wsp)
        subs       (get-in @wsp [::subscriptions team-id])
        xform      (comp
                    (remove #(= (:session-id %) session-id))
                    (map #(assoc % :subs-id team-id)))]

    (a/go
      (when (not= (:team-id subs) team-id)
        ;; if it exists we just need to close that
        (when-let [channel (:channel subs)]
          (a/close! channel)
          (a/<! (msgbus-fn :cmd :purge :chans [channel])))


        (let [channel (a/chan (a/dropping-buffer 64) xform)]
          ;; Message forwarding
          (a/pipe channel output-ch false)

          (let [state {:team-id team-id :channel channel :topic team-id}]
            (swap! wsp update ::subscriptions assoc team-id state))

          (a/<! (msgbus-fn :cmd :sub :topic team-id :chan channel)))))))

(defmethod handle-message :subscribe-file
  [wsp {:keys [subs-id file-id] :as params}]
  (l/trace :fn "handle-message" :event :subscribe-file :subs-id subs-id :file-id file-id)
  (let [msgbus-fn  (:msgbus @wsp)
        profile-id (::profile-id @wsp)
        session-id (::session-id @wsp)
        output-ch  (::ws/output-ch @wsp)

        xform      (comp
                    (remove #(= (:session-id %) session-id))
                    (map #(assoc % :subs-id subs-id)))

        channel    (a/chan (a/dropping-buffer 64) xform)]

    ;; Message forwarding
    (a/go-loop []
      (when-let [{:keys [type] :as message} (a/<! channel)]
        (when (or (= :join-file type)
                  (= :leave-file type)
                  (= :disconnect type))
          (let [message {:type :presence
                         :file-id file-id
                         :session-id session-id
                         :profile-id profile-id}]
            (a/<! (msgbus-fn :cmd :pub
                             :topic file-id
                             :message message))))
        (a/>! output-ch message)
        (recur)))

    (let [state {:file-id file-id :channel channel :topic file-id}]
      (swap! wsp update ::subscriptions assoc subs-id state))

    (a/go
      ;; Subscribe to file topic
      (a/<! (msgbus-fn :cmd :sub :topic file-id :chan channel))

      ;; Notifify the rest of participants of the new connection.
      (let [message {:type :join-file
                     :file-id file-id
                     :session-id session-id
                     :profile-id profile-id}]
        (a/<! (msgbus-fn :cmd :pub
                         :topic file-id
                         :message message))))))

(defmethod handle-message :unsubscribe-file
  [wsp {:keys [subs-id] :as params}]
  (l/trace :fn "handle-message" :event :unsubscribe-file :subs-id subs-id)
  (let [msgbus-fn  (:msgbus @wsp)
        session-id (::session-id @wsp)
        profile-id (::profile-id @wsp)]
    (a/go
      (when-let [{:keys [file-id channel]} (get-in @wsp [::subscriptions subs-id])]
        (let [message {:type :leave-file
                       :file-id file-id
                       :session-id session-id
                       :profile-id profile-id}]
          (a/close! channel)
          (a/<! (msgbus-fn :cmd :pub :topic file-id :message message))
          (a/<! (msgbus-fn :cmd :purge :chans [channel])))))))

(defmethod handle-message :keepalive
  [_ _]
  (l/trace :fn "handle-message" :event :keepalive)
  (a/go :nothing))

(defmethod handle-message :pointer-update
  [wsp {:keys [subs-id] :as message}]
  (a/go
    ;; Only allow receive pointer updates when active subscription
    (when-let [{:keys [topic]} (get-in @wsp [::subscriptions subs-id])]
      (let [msgbus-fn  (:msgbus @wsp)
            profile-id (::profile-id @wsp)
            session-id (::session-id @wsp)
            message    (-> message
                           (dissoc :subs-id)
                           (assoc :profile-id profile-id)
                           (assoc :session-id session-id))]

        (a/<! (msgbus-fn :cmd :pub
                         :topic topic
                         :message message))))))

(defmethod handle-message :default
  [_ message]
  (a/go
    (l/log :level :warn
           :msg "received unexpected message"
           :message message)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::msgbus fn?)
(s/def ::session-id ::us/uuid)

(s/def ::handler-params
  (s/keys :req-un [::session-id]))

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::msgbus ::db/pool ::mtx/metrics]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [profile-id params] :as req} respond raise]
    (let [{:keys [session-id]} (us/conform ::handler-params params)
          cfg    (-> cfg
                     (assoc ::profile-id profile-id)
                     (assoc ::session-id session-id))]

      (l/trace :hint "http request to websocket" :profile-id profile-id :session-id session-id)
      (cond
        (not profile-id)
        (raise (ex/error :type :authentication
                         :hint "Authentication required."))

        (not (yws/upgrade-request? req))
        (raise (ex/error :type :validation
                         :code :websocket-request-expected
                         :hint "this endpoint only accepts websocket connections"))

        :else
        (->> (ws/handler handle-message cfg)
             (yws/upgrade req)
             (respond))))))

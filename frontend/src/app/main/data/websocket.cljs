;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.websocket
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.websocket :as ws]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(dm/export ws/send!)

(defn- prepare-uri
  [params]
  (let [base (-> (u/join cf/public-uri "ws/notifications")
                 (assoc :query (u/map->query-string params)))]
    (cond-> base
      (= "https" (:scheme base))
      (assoc :scheme "wss")

      (= "http" (:scheme base))
      (assoc :scheme "ws"))))

(defn send
  [message]
  (ptk/reify ::send-message
    ptk/EffectEvent
    (effect [_ state _]
      (let [ws-conn (:ws-conn state)]
        (ws/send! ws-conn message)))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [sid (:session-id state)
            uri (prepare-uri {:session-id sid})]
        (assoc state :ws-conn (ws/create uri))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [ws-conn (:ws-conn state)
            stoper  (rx/merge
                     (rx/filter (ptk/type? ::finalize) stream)
                     (rx/filter (ptk/type? ::initialize) stream))]

        (->> (rx/merge
              (->> (ws/get-rcv-stream ws-conn)
                   (rx/filter ws/message-event?)
                   (rx/map :payload)
                   (rx/map #(ptk/data-event ::message %)))
              (->> (ws/get-rcv-stream ws-conn)
                   (rx/filter ws/opened-event?)
                   (rx/map (fn [_] (ptk/data-event ::opened {})))))
             (rx/take-until stoper))))))

;; --- Finalize Websocket

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/EffectEvent
    (effect [_ state _]
      (some-> (:ws-conn state) ws/close!))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.websocket
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.websocket :as ws]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(l/set-level! :error)

(dm/export ws/send!)

(defonce ws-conn (volatile! nil))

(defn- prepare-uri
  [params]
  (let [base (-> cf/public-uri
                 (u/join "ws/notifications")
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
    (effect [_ _ _]
      (some-> @ws-conn (ws/send! message)))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ state stream]
      (l/trace :hint "initialize" :fn "watch")
      (let [sid (:session-id state)
            uri (prepare-uri {:session-id sid})
            ws  (ws/create uri)]

        (vreset! ws-conn ws)

        (let [stoper  (rx/merge
                       (rx/filter (ptk/type? ::finalize) stream)
                       (rx/filter (ptk/type? ::initialize) stream))]

          (->> (rx/merge
                (rx/of #(assoc % :ws-conn ws))
                (->> (ws/get-rcv-stream ws)
                     (rx/filter ws/message-event?)
                     (rx/map :payload)
                     (rx/map #(ptk/data-event ::message %)))
                (->> (ws/get-rcv-stream ws)
                     (rx/filter ws/opened-event?)
                     (rx/map (fn [_] (ptk/data-event ::opened {})))))
               (rx/take-until stoper)))))))

;; --- Finalize Websocket

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :ws-conn))

    ptk/EffectEvent
    (effect [_ _ _]
      (l/trace :hint "event:finalize" :fn "effect")
      (some-> @ws-conn ws/close!))))

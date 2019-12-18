;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace-websocket
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]
   [uxbox.config :as cfg]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.main.websockets :as ws]
   [uxbox.main.data.icons :as udi]
   [uxbox.main.data.projects :as dp]
   [uxbox.main.repo.core :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.transit :as t]
   [vendor.randomcolor]))

;; --- Initialize WebSocket

(declare fetch-users)
(declare handle-who)

(s/def ::type keyword?)
(s/def ::message
  (s/keys :req-un [::type]))

(defn initialize
  [file-id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [uri (str "ws://localhost:6060/sub/" file-id)]
        (assoc-in state [::ws file-id] (ws/open uri))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/merge
       (rx/of (fetch-users file-id))
       (->> (ws/-stream (get-in state [::ws file-id]))
            (rx/filter #(= :message (:type %)))
            (rx/map (comp t/decode :payload))
            (rx/filter #(s/valid? ::message %))
            (rx/map (fn [{:keys [type] :as msg}]
                      (case type
                        :who (handle-who msg)
                        ::unknown))))))))

;; --- Finalize Websocket

(defn finalize
  [file-id]
  (ptk/reify ::finalize
    ptk/EffectEvent
    (effect [_ state stream]
      (ws/-close (get-in state [::ws file-id])))))

;; --- Fetch Workspace Users

(declare users-fetched)

(defn fetch-users
  [file-id]
  (ptk/reify ::fetch-users
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :project-file-users {:file-id file-id})
           (rx/map users-fetched)))))

(defn users-fetched
  [users]
  (ptk/reify ::users-fetched
    ptk/UpdateEvent
    (update [_ state]
      (reduce (fn [state user]
                (assoc-in state [:workspace-users :by-id (:id user)] user))
              state
              users))))


;; --- Handle: Who

;; TODO: assign color

(defn handle-who
  [{:keys [users] :as msg}]
  (s/assert set? users)
  (ptk/reify ::handle-who
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-users :active] users))))

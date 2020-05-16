;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.workspace.notifications
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]
   [uxbox.common.data :as d]
   [uxbox.common.spec :as us]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.data.workspace.common :as dwc]
   [uxbox.main.data.workspace.persistence :as dwp]
   [uxbox.util.avatars :as avatars]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.time :as dt]
   [uxbox.util.transit :as t]
   [uxbox.util.websockets :as ws]))

(declare handle-presence)
(declare handle-pointer-update)
(declare handle-page-change)
(declare handle-pointer-send)
(declare send-keepalive)

(s/def ::type keyword?)
(s/def ::message
  (s/keys :req-un [::type]))

(defn initialize
  [file-id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [sid (:session-id state)
            url (ws/url "/ws/notifications" {:file-id file-id
                                             :session-id sid})]
        (assoc-in state [:ws file-id] (ws/open url))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [wsession (get-in state [:ws file-id])
            stoper   (rx/filter #(= ::finalize %) stream)
            interval (* 1000 60)]
        (->> (rx/merge
              (->> (rx/timer interval interval)
                   (rx/map #(send-keepalive file-id)))
              (->> (ws/-stream wsession)
                   (rx/filter #(= :message (:type %)))
                   (rx/map (comp t/decode :payload))
                   (rx/filter #(s/valid? ::message %))
                   (rx/map (fn [{:keys [type] :as msg}]
                             (case type
                               :presence (handle-presence msg)
                               :pointer-update (handle-pointer-update msg)
                               :page-change (handle-page-change msg)
                               ::unknown))))

              (->> stream
                   (rx/filter ms/pointer-event?)
                   (rx/sample 50)
                   (rx/map #(handle-pointer-send file-id (:pt %)))))

             (rx/take-until stoper))))))

(defn send-keepalive
  [file-id]
  (ptk/reify ::send-keepalive
    ptk/EffectEvent
    (effect [_ state stream]
      (when-let [ws (get-in state [:ws file-id])]
        (ws/-send ws (t/encode {:type :keepalive}))))))

;; --- Finalize Websocket

(defn finalize
  [file-id]
  (ptk/reify ::finalize
    ptk/WatchEvent
    (watch [_ state stream]
      (when-let [ws (get-in state [:ws file-id])]
        (ws/-close ws))
      (rx/of ::finalize))))

;; --- Handle: Presence

(def ^:private presence-palette
  #{"#2e8b57" ; seagreen
    "#808000" ; olive
    "#b22222" ; firebrick
    "#ff8c00" ; darkorage
    "#ffd700" ; gold
    "#ba55d3" ; mediumorchid
    "#00fa9a" ; mediumspringgreen
    "#00bfff" ; deepskyblue
    "#dda0dd" ; plum
    "#ff1493" ; deeppink
    "#ffa07a" ; lightsalmon
    })

(defn handle-presence
  [{:keys [sessions] :as msg}]
  (letfn [(assign-color [sessions session]
            (if (string? (:color session))
              session
              (let [used (into #{}
                               (comp (map second)
                                     (map :color)
                                     (remove nil?))
                               sessions)
                    avail (set/difference presence-palette used)
                    color (or (first avail) "#000000")]
                (assoc session :color color))))
          (update-sessions [previous profiles]
            (reduce (fn [current [session-id profile-id]]
                      (let [profile (get profiles profile-id)
                            session {:id session-id
                                     :fullname (:fullname profile)
                                     :photo-uri (or (:photo-uri profile)
                                                    (avatars/generate {:name (:fullname profile)}))}
                            session (assign-color current session)]
                        (assoc current session-id session)))
                    (select-keys previous (map first sessions))
                    (filter (fn [[sid]] (not (contains? previous sid))) sessions)))]

    (ptk/reify ::handle-presence
      ptk/UpdateEvent
      (update [_ state]
        (let [profiles  (:workspace-users state)]
          (update state :workspace-presence update-sessions profiles))))))

(defn handle-pointer-update
  [{:keys [page-id profile-id session-id x y] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (let [profile  (get-in state [:workspace-users profile-id])]
        (update-in state [:workspace-presence session-id]
                   (fn [session]
                     (assoc session
                            :point (gpt/point x y)
                            :updated-at (dt/now)
                            :page-id page-id)))))))

(defn handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-update
    ptk/EffectEvent
    (effect [_ state stream]
      (let [ws (get-in state [:ws file-id])
            sid (:session-id state)
            pid (get-in state [:workspace-page :id])
            msg {:type :pointer-update
                 :page-id pid
                 :x (:x point)
                 :y (:y point)}]
        (ws/-send ws (t/encode msg))))))

(defn handle-page-change
  [msg]
  (ptk/reify ::handle-page-change
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (dwp/shapes-changes-persisted msg)
             (dwc/update-page-indices (:page-id msg))))))



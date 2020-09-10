;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.notifications
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.avatars :as avatars]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [app.util.websockets :as ws]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]))

(declare process-message)
(declare handle-presence)
(declare handle-pointer-update)
(declare handle-file-change)
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
            uri (ws/uri "/ws/notifications" {:file-id file-id
                                             :session-id sid})]
        (assoc-in state [:ws file-id] (ws/open uri))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [wsession (get-in state [:ws file-id])
            stoper   (rx/filter #(= ::finalize %) stream)
            interval (* 1000 60)]
        (->> (rx/merge
              (->> (rx/timer interval interval)
                   (rx/map #(send-keepalive file-id)))
              (->> (ws/-stream wsession)
                   (rx/filter ws/message?)
                   (rx/map (comp t/decode :payload))
                   (rx/filter #(s/valid? ::message %))
                   (rx/map process-message))
              (->> stream
                   (rx/filter ms/pointer-event?)
                   (rx/sample 50)
                   (rx/map #(handle-pointer-send file-id (:pt %)))))
             (rx/take-until stoper))))))

(defn- process-message
  [{:keys [type] :as msg}]
  (case type
    :presence (handle-presence msg)
    :pointer-update (handle-pointer-update msg)
    :file-change (handle-file-change msg)
    ::unknown))

(defn- send-keepalive
  [file-id]
  (ptk/reify ::send-keepalive
    ptk/EffectEvent
    (effect [_ state stream]
      (when-let [ws (get-in state [:ws file-id])]
        (ws/send! ws {:type :keepalive})))))

(defn- handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-update
    ptk/EffectEvent
    (effect [_ state stream]
      (let [ws (get-in state [:ws file-id])
            sid (:session-id state)
            pid (:current-page-id state)
            msg {:type :pointer-update
                 :page-id pid
                 :x (:x point)
                 :y (:y point)}]
        (ws/send! ws msg)))))

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

          (assign-session [sessions {:keys [id profile]}]
            (let [session {:id id
                           :fullname (:fullname profile)
                           :updated-at (dt/now)
                           :photo-uri (or (:photo-uri profile)
                                          (avatars/generate {:name (:fullname profile)}))}
                  session (assign-color sessions session)]
              (assoc sessions id session)))

          (update-sessions [previous profiles]
            (let [previous    (select-keys previous (map first sessions)) ; Initial clearing
                  pending     (->> sessions
                                   (filter #(not (contains? previous (first %))))
                                   (map (fn [[session-id profile-id]]
                                          {:id session-id
                                           :profile (get profiles profile-id)})))]
              (reduce assign-session previous pending)))]

    (ptk/reify ::handle-presence
      ptk/UpdateEvent
      (update [_ state]
        (let [profiles (:workspace-users state)]
          (update state :workspace-presence update-sessions profiles))))))

(defn handle-pointer-update
  [{:keys [page-id profile-id session-id x y] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-presence session-id]
                 (fn [session]
                   (assoc session
                          :point (gpt/point x y)
                          :updated-at (dt/now)
                          :page-id page-id))))))

(s/def ::type keyword?)
(s/def ::profile-id uuid?)
(s/def ::file-id uuid?)
(s/def ::session-id uuid?)
(s/def ::revn integer?)
(s/def ::changes ::cp/changes)

(s/def ::file-change-event
  (s/keys :req-un [::type ::profile-id ::file-id ::session-id ::revn ::changes]))

(defn handle-file-change
  [{:keys [file-id changes] :as msg}]
  (us/assert ::file-change-event msg)
  (ptk/reify ::handle-file-change
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (into #{} (comp (map :page-id)
                                     (filter identity))
                           changes)]
        (rx/merge
         (rx/of (dwp/shapes-changes-persisted file-id msg))
         (when (seq page-ids)
           (rx/from (map dwc/update-indices page-ids))))))))

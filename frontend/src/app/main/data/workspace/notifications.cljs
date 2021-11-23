;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.notifications
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.streams :as ms]
   [app.util.time :as dt]
   [app.util.websockets :as ws]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [potok.core :as ptk]))

(declare process-message)
(declare handle-presence)
(declare handle-pointer-update)
(declare handle-file-change)
(declare handle-library-change)
(declare handle-pointer-send)
(declare send-keepalive)

(s/def ::type keyword?)
(s/def ::message
  (s/keys :req-un [::type]))

(defn prepare-uri
  [params]
  (let [base (-> (u/join cf/public-uri "ws/notifications")
                 (assoc :query (u/map->query-string params)))]
    (cond-> base
      (= "https" (:scheme base))
      (assoc :scheme "wss")

      (= "http" (:scheme base))
      (assoc :scheme "ws"))))

(defn initialize
  [file-id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (let [sid (:session-id state)
            uri (prepare-uri {:file-id file-id :session-id sid})]
        (assoc-in state [:ws file-id] (ws/open uri))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [wsession (get-in state [:ws file-id])
            stoper   (->> stream
                          (rx/filter (ptk/type? ::finalize)))
            interval (* 1000 60)]
        (->> (rx/merge
              ;; Each 60 seconds send a keepalive message for maintain
              ;; this socket open.
              (->> (rx/timer interval interval)
                   (rx/map #(send-keepalive file-id)))

              ;; Process all incoming messages.
              (->> (ws/-stream wsession)
                   (rx/filter ws/message?)
                   (rx/map (comp t/decode-str :payload))
                   (rx/filter #(s/valid? ::message %))
                   (rx/map process-message))

              (rx/of (handle-presence {:type :connect
                                       :session-id (:session-id state)
                                       :profile-id (:profile-id state)}))

              ;; Send back to backend all pointer messages.
              (->> stream
                   (rx/filter ms/pointer-event?)
                   (rx/sample 50)
                   (rx/map #(handle-pointer-send file-id (:pt %)))))
             (rx/take-until stoper))))))

(defn- process-message
  [{:keys [type] :as msg}]
  (case type
    :connect        (handle-presence msg)
    :presence       (handle-presence msg)
    :disconnect     (handle-presence msg)
    :pointer-update (handle-pointer-update msg)
    :file-change    (handle-file-change msg)
    :library-change (handle-library-change msg)
    ::unknown))

(defn- send-keepalive
  [file-id]
  (ptk/reify ::send-keepalive
    ptk/EffectEvent
    (effect [_ state _]
      (when-let [ws (get-in state [:ws file-id])]
        (ws/send! ws {:type :keepalive})))))

(defn- handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-send
    ptk/EffectEvent
    (effect [_ state _]
      (let [ws (get-in state [:ws file-id])
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
    ptk/EffectEvent
    (effect [_ state _]
      (when-let [ws (get-in state [:ws file-id])]
        (ws/-close ws)))))

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
  [{:keys [type session-id profile-id] :as message}]
  (letfn [(get-next-color [presence]
            (let [xfm   (comp (map second)
                              (map :color)
                              (remove nil?))
                  used  (into #{} xfm presence)
                  avail (set/difference presence-palette used)]
              (or (first avail) "#000000")))

          (update-color [color presence]
            (if (some? color)
              color
              (get-next-color presence)))

          (update-session [session presence]
            (-> session
                (assoc :id session-id)
                (assoc :profile-id profile-id)
                (assoc :updated-at (dt/now))
                (update :color update-color presence)))

          (update-presence [presence]
            (-> presence
                (update session-id update-session presence)
                (d/without-nils)))

          ]

    (ptk/reify ::handle-presence
      ptk/UpdateEvent
      (update [_ state]
        ;; (let [profiles (:users state)]
        (if (= :disconnect type)
          (update state :workspace-presence dissoc session-id)
          (update state :workspace-presence update-presence))))))

(defn handle-pointer-update
  [{:keys [page-id session-id x y] :as msg}]
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
    (watch [_ _ _]
      (let [changes-by-pages (group-by :page-id changes)
            process-page-changes
            (fn [[page-id changes]]
              (dch/update-indices page-id changes))]

        (rx/merge
         (rx/of (dwp/shapes-changes-persisted file-id msg))

         (when-not (empty? changes-by-pages)
           (rx/from (map process-page-changes changes-by-pages))))))))

(s/def ::library-change-event
  (s/keys :req-un [::type
                   ::profile-id
                   ::file-id
                   ::session-id
                   ::revn
                   ::modified-at
                   ::changes]))

(defn handle-library-change
  [{:keys [file-id modified-at changes revn] :as msg}]
  (us/assert ::library-change-event msg)
  (ptk/reify ::handle-library-change
    ptk/WatchEvent
    (watch [_ state _]
      (when (contains? (:workspace-libraries state) file-id)
        (rx/of (dwl/ext-library-changed file-id modified-at revn changes)
               (dwl/notify-sync-file file-id))))))


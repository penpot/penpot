;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.notifications
  (:require
   [app.common.data :as d]
   [app.common.pages.changes-spec :as pcs]
   [app.common.spec :as us]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.streams :as ms]
   [app.util.time :as dt]
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
(declare handle-export-update)

(defn initialize
  [team-id file-id]
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stoper     (rx/filter (ptk/type? ::finalize) stream)
            profile-id (:profile-id state)

            initmsg    [{:type :subscribe-file
                         :file-id file-id}
                        {:type :subscribe-team
                         :team-id team-id}]

            endmsg     {:type :unsubscribe-file
                        :file-id file-id}

            stream     (->> (rx/merge
                             ;; Send the subscription message
                             (->> (rx/from initmsg)
                                  (rx/map dws/send))

                             ;; Subscribe to notifications of the subscription
                             (->> stream
                                  (rx/filter (ptk/type? ::dws/message))
                                  (rx/map deref)
                                  (rx/filter (fn [{:keys [subs-id] :as msg}]
                                               (or (= subs-id team-id)
                                                   (= subs-id profile-id)
                                                   (= subs-id file-id))))
                                  (rx/map process-message))

                             ;; On reconnect, send again the subscription messages
                             (->> stream
                                  (rx/filter (ptk/type? ::dws/opened))
                                  (rx/mapcat #(->> (rx/from initmsg)
                                                   (rx/map dws/send))))

                             ;; Emit presence event for current user;
                             ;; this is because websocket server don't
                             ;; emits this for the same user.
                             (rx/of (handle-presence {:type :connect
                                                      :session-id (:session-id state)
                                                      :profile-id (:profile-id state)}))

                             ;; Emit to all other connected users the current pointer
                             ;; position changes.
                             (->> stream
                                  (rx/filter ms/pointer-event?)
                                  (rx/sample 50)
                                  (rx/map #(handle-pointer-send file-id (:pt %)))))

                            (rx/take-until stoper))]

        (rx/concat stream (rx/of (dws/send endmsg)))))))

(defn- process-message
  [{:keys [type] :as msg}]
  (case type
    :join-file      (handle-presence msg)
    :leave-file     (handle-presence msg)
    :presence       (handle-presence msg)
    :disconnect     (handle-presence msg)
    :pointer-update (handle-pointer-update msg)
    :file-change    (handle-file-change msg)
    :library-change (handle-library-change msg)
    nil))

(defn- handle-pointer-send
  [file-id point]
  (ptk/reify ::handle-pointer-send
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            message {:type :pointer-update
                     :file-id file-id
                     :page-id page-id
                     :position point}]
        (rx/of (dws/send message))))))

;; --- Finalize Websocket

(defn finalize
  [_]
  (ptk/reify ::finalize))

;; --- Handle: Presence

(def ^:private presence-palette
  #{"#02bf51" ; darkpastelgreen text white
    "#00fa9a" ; mediumspringgreen text black
    "#b22222" ; firebrick text white
    "#ff8c00" ; darkorage text white
    "#ffd700" ; gold text black
    "#ba55d3" ; mediumorchid text white
    "#dda0dd" ; plum text black
    "#008ab8" ; blueNCS text white
    "#00bfff" ; deepskyblue text white
    "#ff1493" ; deeppink text white
    "#ffafda" ; carnationpink text black
    })

(defn handle-presence
  [{:keys [type session-id profile-id] :as message}]
  (letfn [(get-next-color [presence]
            (let [xfm   (comp (map second)
                              (map :color)
                              (remove nil?))
                  used  (into #{} xfm presence)
                  avail (set/difference presence-palette used)]
              (or (first avail) "var(--color-black)")))

          (update-color [color presence]
            (if (some? color)
              color
              (get-next-color presence)))

          (update-session [session presence]
            (-> session
                (assoc :id session-id)
                (assoc :profile-id profile-id)
                (assoc :updated-at (dt/now))
                (update :color update-color presence)
                (assoc :text-color (if (contains? ["#00fa9a" "#ffd700" "#dda0dd" "#ffafda"]
                                                  (update-color (:color presence) presence))
                                     "#000"
                                     "#fff"))))

          (update-presence [presence]
            (-> presence
                (update session-id update-session presence)
                (d/without-nils)))]

    (ptk/reify ::handle-presence
      ptk/UpdateEvent
      (update [_ state]
        (if (or (= :disconnect type) (= :leave-file type))
          (update state :workspace-presence dissoc session-id)
          (update state :workspace-presence update-presence))))))

(defn handle-pointer-update
  [{:keys [page-id session-id position] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-presence session-id]
                 (fn [session]
                   (assoc session
                          :point position
                          :updated-at (dt/now)
                          :page-id page-id))))))

(s/def ::type keyword?)
(s/def ::profile-id uuid?)
(s/def ::file-id uuid?)
(s/def ::session-id uuid?)
(s/def ::revn integer?)
(s/def ::changes ::pcs/changes)

(s/def ::file-change-event
  (s/keys :req-un [::type ::profile-id ::file-id ::session-id ::revn ::changes]))

(defn handle-file-change
  [{:keys [file-id changes] :as msg}]
  (us/assert ::file-change-event msg)
  (ptk/reify ::handle-file-change
    IDeref
    (-deref [_] {:changes changes})

    ptk/WatchEvent
    (watch [_ _ _]
      (let [position-data-operation?
            (fn [{:keys [type attr]}]
              (and (= :set type) (= attr :position-data)))

            add-origin-session-id
            (fn [{:keys [] :as op}]
              (cond-> op
                (position-data-operation? op)
                (update :val with-meta {:session-id (:session-id msg)})))

            update-position-data
            (fn [change]
              (cond-> change
                (= :mod-obj (:type change))
                (update :operations #(mapv add-origin-session-id %))))

            process-page-changes
            (fn [[page-id changes]]
              (dch/update-indices page-id changes))

            ;; We update `position-data` from the incoming message
            changes (->> changes (mapv update-position-data))
            changes-by-pages (group-by :page-id changes)]

        (rx/merge
         (rx/of (dwp/shapes-changes-persisted file-id (assoc msg :changes changes)))

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

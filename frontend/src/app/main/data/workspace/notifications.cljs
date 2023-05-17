;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.notifications
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.changes :as cpc]
   [app.common.schema :as sm]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.streams :as ms]
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [app.util.time :as dt]
   [beicon.core :as rx]
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
                         :file-id file-id
                         :version (obj/get global "penpotVersion")}
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
  [{:keys [type session-id profile-id version] :as message}]
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
                (assoc :version version)
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

(def schema:handle-file-change
  [:map
   [:type :keyword]
   [:profile-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn :int]
   [:changes ::cpc/changes]])

(defn handle-file-change
  [{:keys [file-id changes] :as msg}]
  (dm/assert! (sm/valid? schema:handle-file-change msg))
  (ptk/reify ::handle-file-change
    IDeref
    (-deref [_] {:changes changes})

    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            position-data-operation?
            (fn [{:keys [type attr]}]
              (and (= :set type) (= attr :position-data)))

            ;;add-origin-session-id
            ;;(fn [{:keys [] :as op}]
            ;;  (cond-> op
            ;;    (position-data-operation? op)
            ;;    (update :val with-meta {:session-id (:session-id msg)})))

            update-position-data
            (fn [change]
              ;; Remove the position data from remote operations. Will be changed localy, otherwise
              ;; creates a strange "out-of-sync" behaviour.
              (cond-> change
                (and (= page-id (:page-id change))
                     (= :mod-obj (:type change)))
                (update :operations #(d/removev position-data-operation? %))))

            process-page-changes
            (fn [[page-id changes]]
              (dch/update-indices page-id changes))

            ;; We update `position-data` from the incoming message
            changes (->> changes
                         (mapv update-position-data)
                         (d/removev (fn [change]
                                      (and (= page-id (:page-id change))
                                           (:ignore-remote? change)))))

            changes-by-pages (group-by :page-id changes)]

        (rx/merge
         (rx/of (dwp/shapes-changes-persisted file-id (assoc msg :changes changes)))

         (when-not (empty? changes-by-pages)
           (rx/from (map process-page-changes changes-by-pages))))))))

(def schema:handle-library-change
  [:map
   [:type :keyword]
   [:profile-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn :int]
   [:modified-at ::sm/inst]
   [:changes ::cpc/changes]])

(defn handle-library-change
  [{:keys [file-id modified-at changes revn] :as msg}]
  (dm/assert! (sm/valid? schema:handle-library-change msg))
  (ptk/reify ::handle-library-change
    ptk/WatchEvent
    (watch [_ state _]
      (when (contains? (:workspace-libraries state) file-id)
        (rx/of (dwl/ext-library-changed file-id modified-at revn changes)
               (dwl/notify-sync-file file-id))))))

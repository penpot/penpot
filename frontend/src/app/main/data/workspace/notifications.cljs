;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.notifications
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cpc]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.main.data.common :refer [handle-notification]]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.persistence :as dwp]
   [app.util.globals :refer [global]]
   [app.util.mouse :as mse]
   [app.util.object :as obj]
   [app.util.rxops :as rxs]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

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
      (let [stopper     (rx/filter (ptk/type? ::finalize) stream)
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
                                               (or (= subs-id uuid/zero)
                                                   (= subs-id profile-id)
                                                   (= subs-id team-id)
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
                                  (rx/filter mse/pointer-event?)
                                  (rx/filter #(= :viewport (mse/get-pointer-source %)))
                                  (rx/pipe (rxs/throttle 100))
                                  (rx/map #(handle-pointer-send file-id (:pt %)))))

                            (rx/take-until stopper))]

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
    :notification   (handle-notification msg)
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
  #{"#f49ef7" ; pink
    "#75cafc" ; blue
    "#fdcf79" ; gold
    "#a9bdfa" ; indigo
    "#faa6b7" ; red
    "#cbaaff" ; purple
    "#f9b489" ; orange
    "#dee563" ; yellow -> default presence color
    "#b1e96f" ; lemon
    })

(defn handle-presence
  [{:keys [type session-id profile-id version] :as message}]
  (letfn [(get-next-color [presence]
            (let [xfm   (comp (map second)
                              (map :color)
                              (remove nil?))
                  used  (into #{} xfm presence)
                  avail (set/difference presence-palette used)]
              ;; If all colores are used we select the default one
              (or (first avail) "#dee563")))

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
                (assoc :text-color "#000000")))

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

(def ^:private
  schema:handle-file-change
  (sm/define
    [:map {:title "handle-file-change"}
     [:type :keyword]
     [:profile-id ::sm/uuid]
     [:file-id ::sm/uuid]
     [:session-id ::sm/uuid]
     [:revn :int]
     [:changes ::cpc/changes]]))

(defn handle-file-change
  [{:keys [file-id changes] :as msg}]
  (dm/assert!
   "expected valid arguments"
   (sm/check! schema:handle-file-change msg))

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

(def ^:private
  schema:handle-library-change
  (sm/define
    [:map {:title "handle-library-change"}
     [:type :keyword]
     [:profile-id ::sm/uuid]
     [:file-id ::sm/uuid]
     [:session-id ::sm/uuid]
     [:revn :int]
     [:modified-at ::sm/inst]
     [:changes ::cpc/changes]]))

(defn handle-library-change
  [{:keys [file-id modified-at changes revn] :as msg}]
  (dm/assert!
   "expected valid arguments"
   (sm/check! schema:handle-library-change msg))

  (ptk/reify ::handle-library-change
    ptk/WatchEvent
    (watch [_ state _]
      (when (contains? (:workspace-libraries state) file-id)
        (rx/of (dwl/ext-library-changed file-id modified-at revn changes)
               (dwl/notify-sync-file file-id))))))

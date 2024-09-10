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
   [app.main.data.changes :as dch]
   [app.main.data.common :refer [handle-notification]]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace.libraries :as dwl]
   [app.util.globals :refer [global]]
   [app.util.mouse :as mse]
   [app.util.object :as obj]
   [app.util.rxops :as rxs]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

;; FIXME: this ns should be renamed to something different

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
                                  (rx/pipe (rxs/throttle 50))
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
            local   (:workspace-local state)

            message {:type :pointer-update
                     :file-id file-id
                     :page-id page-id
                     :zoom (:zoom local)
                     :zoom-inverse (:zoom-inverse local)
                     :vbox (:vbox local)
                     :vport (:vport local)
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
  [{:keys [page-id session-id position zoom zoom-inverse vbox vport] :as msg}]
  (ptk/reify ::handle-pointer-update
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-presence session-id]
                 (fn [session]
                   (assoc session
                          :zoom zoom
                          :zoom-inverse zoom-inverse
                          :vbox vbox
                          :vport vport
                          :point position
                          :updated-at (dt/now)
                          :page-id page-id))))))

(def ^:private
  schema:handle-file-change
  [:map {:title "handle-file-change"}
   [:type :keyword]
   [:profile-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn :int]
   [:changes ::cpc/changes]])

(def ^:private check-file-change-params!
  (sm/check-fn schema:handle-file-change))

(defn handle-file-change
  [{:keys [file-id changes revn] :as msg}]

  (dm/assert!
   "expected valid parameters"
   (check-file-change-params! msg))

  (ptk/reify ::handle-file-change
    IDeref
    (-deref [_] {:changes changes})

    ptk/WatchEvent
    (watch [_ _ _]
      ;; The commit event is responsible to apply the data localy
      ;; and update the persistence internal state with the updated
      ;; file-revn
      (rx/of (dch/commit {:file-id file-id
                          :file-revn revn
                          :save-undo? false
                          :source :remote
                          :redo-changes (vec changes)
                          :undo-changes []})))))

(def ^:private schema:handle-library-change
  [:map {:title "handle-library-change"}
   [:type :keyword]
   [:profile-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn :int]
   [:modified-at ::sm/inst]
   [:changes ::cpc/changes]])

(def ^:private check-library-change-params!
  (sm/check-fn schema:handle-library-change))

(defn handle-library-change
  [{:keys [file-id modified-at changes revn] :as msg}]
  (dm/assert!
   "expected valid arguments"
   (check-library-change-params! msg))

  (ptk/reify ::handle-library-change
    ptk/WatchEvent
    (watch [_ state _]
      (when (contains? (:workspace-libraries state) file-id)
        (rx/of (dwl/ext-library-changed file-id modified-at revn changes)
               (dwl/notify-sync-file file-id))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.notifications
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cpc]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.common :as dc]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.persistence :as dps]
   [app.main.data.plugins :as dpl]
   [app.main.data.websocket :as dws]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.layout :as dwly]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.texts :as dwt]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.util.globals :refer [global]]
   [app.util.mouse :as mse]
   [app.util.object :as obj]
   [app.util.rxops :as rxs]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

;; FIXME: this ns should be renamed to something different

(declare process-message)
(declare handle-presence)
(declare handle-pointer-update)
(declare handle-file-change)
(declare handle-file-deleted)
(declare handle-file-restored)
(declare handle-library-change)
(declare handle-pointer-send)
(declare handle-export-update)
(declare send-presence-update)
(declare mark-resync)
(declare resync-file)

(defn- resubscribed?
  "True when `event` is a new subscription to `file-id`, which a reload of
  the open file starts."
  [file-id event]
  (and (ptk/type? ::initialize event)
       (= file-id (:file-id (deref event)))))

(defn initialize
  "Subscribes to the notifications of `file-id` and its team until the file
  is left or a new subscription starts. A new subscription to the same file
  replaces this one, so a reload never doubles the handled messages."
  [team-id file-id]
  (ptk/reify ::initialize
    IDeref
    (-deref [_] {:file-id file-id})

    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopped-by  (volatile! nil)
            stopper     (->> stream
                             (rx/filter #(or (ptk/type? ::finalize %)
                                             (ptk/type? ::initialize %)))
                             (rx/tap #(vreset! stopped-by %)))
            profile-id (:profile-id state)
            organization-id (dm/get-in state [:teams team-id :organization :id])

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
                                  (rx/filter (fn [{:keys [topic] :as msg}]
                                               (or (= topic uuid/zero)
                                                   (= topic profile-id)
                                                   (= topic team-id)
                                                   (= topic file-id)
                                                   (when (some? organization-id)
                                                     (= topic organization-id)))))
                                  (rx/map process-message))

                             ;; On reconnect, send again the subscription messages
                             (->> stream
                                  (rx/filter (ptk/type? ::dws/opened))
                                  (rx/mapcat #(rx/concat
                                               (->> (rx/from initmsg)
                                                    (rx/map dws/send))
                                               (rx/of (send-presence-update)))))

                             ;; The other sessions learn the page this one opens
                             (->> stream
                                  (rx/filter (ptk/type? ::dwpg/initialized))
                                  (rx/map (fn [_] (send-presence-update))))

                             ;; The changes of the file sent while the
                             ;; connection was lost or the tab was frozen
                             (let [resyncs (->> (rx/merge
                                                 (rx/filter (ptk/type? ::dws/opened) stream)
                                                 (if (exists? js/document)
                                                   (rx/merge
                                                    (rx/from-event js/document "resume")
                                                    (->> (rx/from-event js/document "visibilitychange")
                                                         (rx/filter #(= "visible" (.-visibilityState js/document)))))
                                                   (rx/empty)))
                                                (rx/share))]
                               (rx/merge
                                (rx/map (fn [_] (mark-resync file-id)) resyncs)
                                (->> resyncs
                                     (rx/debounce 500)
                                     (rx/map (fn [_] (resync-file file-id))))))

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

        (rx/concat
         stream
         ;; The server keeps one file subscription per connection: an
         ;; unsubscribe sent after the new subscribe would end it
         (rx/defer #(if (resubscribed? file-id @stopped-by)
                      (rx/empty)
                      (rx/of (dws/send endmsg)))))))))

(defn- handle-change-team-role
  [{:keys [team-id role] :as msg}]
  (ptk/reify ::handle-change-team-role
    ptk/WatchEvent
    (watch [_ state _]
      ;; A change to another team must not touch the open file: only
      ;; the team that owns it changes what can be done here
      (if (not= team-id (:current-team-id state))
        (rx/of (dc/change-team-role msg))
        (rx/concat
         (rx/of :interrupt
                (dwe/clear-edition-mode)
                (dwc/set-workspace-read-only false))
         (->> (rx/of (dc/change-team-role msg)
                     ::dwt/update-editor-state)
              ;; Delay so anything that launched :interrupt can finish
              (rx/delay 100))
         (if (= :viewer role)
           (rx/of (modal/hide)
                  (dwly/set-options-mode :inspect)
                  (dpl/close-current-plugin {:close-only-edition-plugins? true}))
           (rx/of (dwly/set-options-mode :design))))))))

(defn- process-message
  [{:keys [type] :as msg}]
  (case type
    :join-file               (handle-presence msg)
    :leave-file              (handle-presence msg)
    :presence                (handle-presence msg)
    :disconnect              (handle-presence msg)
    :pointer-update          (handle-pointer-update msg)
    :file-change             (handle-file-change msg)
    :file-deleted            (handle-file-deleted msg)
    :file-restored           (handle-file-restored msg)
    :library-change          (handle-library-change msg)
    :notification            (dc/handle-notification msg)
    :team-role-change        (handle-change-team-role msg)
    :team-membership-change  (dc/team-membership-change msg)
    :team-organization-change         (dc/handle-change-team-organization msg)
    :organization-change-sso (dc/handle-organization-change-sso msg)
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

(defn send-presence-update
  "Tell the other sessions of the file the page of this one."
  []
  (ptk/reify ::send-presence-update
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        (rx/of (dws/send {:type :presence-update
                          :file-id file-id
                          :page-id (:current-page-id state)}))))))

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
  [{:keys [type session-id profile-id version page-id can-edit] :as message}]
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
                (assoc :updated-at (ct/now))
                (assoc :version version)
                (cond-> (some? page-id) (assoc :page-id page-id))
                (cond-> (boolean? can-edit) (assoc :can-edit can-edit))
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
                          :updated-at (ct/now)
                          :page-id page-id))))))

(def ^:private max-applied-revns
  "Revisions of the changes applied that are kept, to skip the ones that
  come again; past it the older half is dropped."
  512)

(def ^:private
  schema:handle-file-change
  [:map {:title "handle-file-change"}
   [:type :keyword]
   [:profile-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn :int]
   [:vern :int]
   [:changes cpc/schema:changes]
   [:repaired {:optional true} :boolean]])

(def ^:private check-file-change-params!
  (sm/check-fn schema:handle-file-change))

(defn handle-file-change
  [{:keys [file-id changes revn vern] :as msg}]

  (dm/assert!
   "expected valid parameters"
   (check-file-change-params! msg))

  (let [applied? (volatile! false)]
    (ptk/reify ::handle-file-change
      IDeref
      (-deref [_] {:changes changes :session-id (:session-id msg)})

      ptk/UpdateEvent
      (update [_ state]
        ;; A change can come twice: notified and fetched again after a
        ;; lost connection
        (vreset! applied? (contains? (get-in state [::applied-revns file-id]) revn))
        (update-in state [::applied-revns file-id]
                   (fn [revns]
                     (let [revns (conj (or revns (sorted-set)) revn)]
                       (if (> (count revns) max-applied-revns)
                         (into (sorted-set) (drop (quot max-applied-revns 2)) revns)
                         revns)))))

      ptk/WatchEvent
      (watch [_ _ _]
        ;; The commit event is responsible to apply the data localy
        ;; and update the persistence internal state with the updated
        ;; file-revn
        (if @applied?
          (rx/empty)
          (rx/concat
           (rx/of (dch/commit {:file-id file-id
                               :file-revn revn
                               :file-vern vern
                               :save-undo? false
                               :source :remote
                               :redo-changes (vec changes)
                               :undo-changes []}))
           (if (:repaired msg)
             (rx/of (dps/notify-repaired))
             (rx/empty))))))))

(defn- previewing?
  "True while the workspace shows an earlier version of the file."
  [state]
  (some? (dm/get-in state [:workspace-global :preview-id])))

(defn- mark-resync
  "Keep the revision of `file-id` when the connection opens or the tab comes
  back: the changes after it are the ones to fetch, even when the changes
  notified from then on arrive before the fetch."
  [file-id]
  (ptk/reify ::mark-resync
    ptk/UpdateEvent
    (update [_ state]
      (let [revn (:revn (dsh/lookup-file state file-id))]
        (cond-> state
          (and (some? revn) (not (previewing? state)))
          (update-in [::resync-revn file-id] #(if (some? %) (min % revn) revn)))))))

(defn- apply-missed-changes
  "Apply the changes of other sessions in `rows`, fetched after a lost
  connection, that are not applied yet. When one of them is older than a
  change already applied it would go after a newer one, so the file is
  loaded again instead."
  [file-id vern rows]
  (ptk/reify ::apply-missed-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [session-id (:session-id state)
            applied    (dm/get-in state [::applied-revns file-id])
            newest     (some-> applied rseq first)
            missed     (into []
                             (remove #(or (= session-id (:session-id %))
                                          (contains? applied (:revn %))))
                             rows)]
        (if (and (some? newest) (some #(< (:revn %) newest) missed))
          (rx/of (ptk/event ::dw/reload-current-file))
          (->> (rx/from missed)
               (rx/map (fn [{:keys [revn changes] :as row}]
                         (handle-file-change {:type :file-change
                                              :file-id file-id
                                              :profile-id (or (:profile-id row) uuid/zero)
                                              :session-id (:session-id row)
                                              :revn revn
                                              :vern vern
                                              :changes changes})))))))))

(defn- resync-file
  "Apply the changes of other sessions saved in the file after the revision
  kept by `mark-resync`, or else the last one this session has: the ones
  notified while the connection was lost or the tab was frozen. When they
  can't all be fetched, or not in order, the file is loaded again. Nothing
  is fetched while an earlier version is shown."
  [file-id]
  (let [revn* (volatile! nil)]
    (ptk/reify ::resync-file
      ptk/UpdateEvent
      (update [_ state]
        (vreset! revn* (or (dm/get-in state [::resync-revn file-id])
                           (:revn (dsh/lookup-file state file-id))))
        (update state ::resync-revn dissoc file-id))

      ptk/WatchEvent
      (watch [_ state _]
        (let [revn @revn*]
          (when (and (some? revn) (not (previewing? state)))
            (->> (rp/cmd! :get-file-changes {:file-id file-id :revn revn})
                 (rx/map
                  (fn [{:keys [complete changes vern]}]
                    (if complete
                      ;; Checked with the changes applied while fetching
                      (apply-missed-changes file-id vern changes)
                      (ptk/event ::dw/reload-current-file))))
                 (rx/catch (fn [cause]
                             (log/wrn :hint "unable to fetch the missed changes of the file"
                                      :file-id (dm/str file-id)
                                      :cause cause)
                             (rx/empty))))))))))

(defn handle-file-deleted
  [{:keys [file-id] :as msg}]
  (ptk/reify ::handle-file-deleted
    ptk/WatchEvent
    (watch [_ state _]
      (let [curr-file-id (:current-file-id state)
            team-id      (:current-team-id state)]
        ;; If the deleted file is the currently open one
        (when (= file-id curr-file-id)
          (rx/of
           (rt/nav :dashboard-recent {:team-id team-id})))))))

(def ^:private
  schema:handle-file-restored
  [:map {:title "handle-file-restored"}
   [:type :keyword]
   [:file-id ::sm/uuid]
   [:vern :int]
   [:profile-id {:optional true} ::sm/uuid]
   [:label {:optional true} :string]
   [:created-at {:optional true} ::ct/inst]])

(def ^:private check-file-restored-params
  (sm/check-fn schema:handle-file-restored))

(defn- restorer-fullname
  "Name of the user who restored the file, when known: by `profile-id`, or
  else by the presence of the session that restored it."
  [state profile-id session-id]
  (when-let [profile-id (or profile-id
                            (dm/get-in state [:workspace-presence session-id :profile-id]))]
    (dm/get-in state [:profiles profile-id :fullname])))

(defn handle-file-restored
  "Reloads the file a peer restored to an earlier version and tells the
  user who restored which version, and whether their edits not saved yet
  were dropped with the replaced version."
  [{:keys [file-id vern session-id profile-id label created-at] :as msg}]

  (assert (check-file-restored-params msg)
          "expected valid parameters")

  (ptk/reify ::handle-file-restored
    ptk/WatchEvent
    (watch [_ state _]
      (let [curr-file-id    (:current-file-id state)
            file            (dsh/lookup-file state curr-file-id)
            curr-vern       (:vern file)]

        (if (and (= file-id curr-file-id)
                 (not= vern curr-vern))
          (rx/of (ptk/event ::dw/reload-current-file)
                 (modal/show :file-restored
                             {:fullname   (restorer-fullname state profile-id session-id)
                              :label      label
                              :date       created-at
                              ;; Made on the replaced version, so the reload drops them
                              :unsaved    (boolean (seq (dps/pending-file-changes state file)))}))
          (rx/empty))))))

(def ^:private schema:handle-library-change
  [:map {:title "handle-library-change"}
   [:type :keyword]
   [:profile-id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn :int]
   [:modified-at ::ct/inst]
   [:changes cpc/schema:changes]])

(def ^:private check-library-change-params
  (sm/check-fn schema:handle-library-change))

(defn handle-library-change
  [{:keys [file-id modified-at changes revn] :as msg}]
  (assert (check-library-change-params msg)
          "expected valid arguments")

  (ptk/reify ::handle-library-change
    ptk/WatchEvent
    (watch [_ state _]
      (when (contains? (:files state) file-id)
        (rx/of (dwl/ext-library-changed file-id modified-at revn changes)
               (dwl/notify-sync-file))))))

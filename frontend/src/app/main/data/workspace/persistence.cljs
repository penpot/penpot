;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.persistence
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes :as cpc]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]))

(log/set-level! :info)

(declare persist-changes)
(declare persist-synchronous-changes)
(declare shapes-changes-persisted)
(declare shapes-changes-persisted-finished)
(declare update-persistence-status)

;; --- Persistence

(defn initialize-file-persistence
  [file-id]
  (ptk/reify ::initialize-persistence
    ptk/WatchEvent
    (watch [_ _ stream]
      (log/debug :hint "initialize persistence")
      (let [stoper   (rx/filter (ptk/type? ::initialize-persistence) stream)
            commits  (l/atom [])
            saving?  (l/atom false)

            local-file?
            #(as-> (:file-id %) event-file-id
               (or (nil? event-file-id)
                   (= event-file-id file-id)))

            library-file?
            #(as-> (:file-id %) event-file-id
               (and (some? event-file-id)
                    (not= event-file-id file-id)))

            on-dirty
            (fn []
              ;; Enable reload stoper
              (swap! st/ongoing-tasks conj :workspace-change)
              (st/emit! (update-persistence-status {:status :pending})))

            on-saving
            (fn []
              (reset! saving? true)
              (st/emit! (update-persistence-status {:status :saving})))

            on-saved
            (fn []
              ;; Disable reload stoper
              (swap! st/ongoing-tasks disj :workspace-change)
              (st/emit! (update-persistence-status {:status :saved}))
              (reset! saving? false))]

        (rx/merge
         (->> stream
              (rx/filter dch/commit-changes?)
              (rx/map deref)
              (rx/filter local-file?)
              (rx/tap on-dirty)
              (rx/filter (complement empty?))
              (rx/map (fn [commit]
                        (-> commit
                            (assoc :id (uuid/next))
                            (assoc :file-id file-id))))
              (rx/observe-on :async)
              (rx/tap #(swap! commits conj %))
              (rx/take-until (rx/delay 100 stoper))
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: changes watcher"))))

         (->> (rx/from-atom commits)
              (rx/filter (complement empty?))
              (rx/sample-when
               (rx/merge
                (rx/filter #(= ::force-persist %) stream)
                (->> (rx/merge
                      (rx/interval 5000)
                      (->> (rx/from-atom commits)
                           (rx/filter (complement empty?))
                           (rx/debounce 2000)))
                     ;; Not sample while saving so there are no race conditions
                     (rx/filter #(not @saving?)))))
              (rx/tap #(reset! commits []))
              (rx/tap on-saving)
              (rx/mapcat (fn [changes]
                           ;; NOTE: this is needed for don't start the
                           ;; next persistence before this one is
                           ;; finished.
                           (if-let [file-revn (dm/get-in @st/state [:workspace-file :revn])]
                             (rx/merge
                              (->> (rx/of (persist-changes file-id file-revn changes commits))
                                   (rx/observe-on :async))
                              (->> stream
                                   ;; We wait for every change to be persisted
                                   (rx/filter (ptk/type? ::shapes-changes-persisted-finished))
                                   (rx/take 1)
                                   (rx/tap on-saved)
                                   (rx/ignore)))
                             (rx/empty))))
              (rx/take-until (rx/delay 100 stoper))
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: save loop"))))

         ;; Synchronous changes
         (->> stream
              (rx/filter dch/commit-changes?)
              (rx/map deref)
              (rx/filter library-file?)
              (rx/filter (complement #(empty? (:changes %))))
              (rx/map persist-synchronous-changes)
              (rx/take-until (rx/delay 100 stoper))
              (rx/finalize (fn []
                             (log/debug :hint "finalize persistence: synchronous save loop")))))))))

(defn persist-changes
  [file-id file-revn changes pending-commits]
  (log/debug :hint "persist changes" :changes (count changes))
  (dm/assert! (uuid? file-id))
  (ptk/reify ::persist-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [sid      (:session-id state)

            features (features/get-team-enabled-features state)
            params   {:id file-id
                      :revn file-revn
                      :session-id sid
                      :changes-with-metadata (into [] changes)
                      :features features
                      }]

        (->> (rp/cmd! :update-file params)
             (rx/mapcat (fn [lagged]
                          (log/debug :hint "changes persisted" :lagged (count lagged))
                          (let [frame-updates
                                (-> (group-by :page-id changes)
                                    (update-vals #(into #{} (mapcat :frames) %)))

                                commits
                                (->> @pending-commits
                                     (map #(assoc % :revn file-revn)))]

                            (rx/concat
                             (rx/merge
                              (->> (rx/from frame-updates)
                                   (rx/mapcat (fn [[page-id frames]]
                                                (->> frames (map (fn [frame-id] [file-id page-id frame-id])))))
                                   (rx/map (fn [data]
                                             (ptk/data-event ::dwt/update data))))

                              (->> (rx/from (concat lagged commits))
                                   (rx/merge-map
                                    (fn [{:keys [changes] :as entry}]
                                      (rx/merge
                                       (rx/from
                                        (for [[page-id changes] (group-by :page-id changes)]
                                          (dch/update-indices page-id changes)))
                                       (rx/of (shapes-changes-persisted file-id entry)))))))

                             (rx/of (shapes-changes-persisted-finished))))))
             (rx/catch (fn [cause]
                         (cond
                           (= :authentication (:type cause))
                           (rx/throw cause)

                           (instance? js/TypeError cause)
                           (->> (rx/timer 2000)
                                (rx/map (fn [_]
                                          (persist-changes file-id file-revn changes pending-commits))))

                           :else
                           (rx/concat
                            (rx/of (rt/assign-exception cause))
                            (rx/throw cause))))))))))

;; Event to be thrown after the changes have been persisted
(defn shapes-changes-persisted-finished
  []
  (ptk/reify ::shapes-changes-persisted-finished))

(defn persist-synchronous-changes
  [{:keys [file-id changes]}]
  (dm/assert! (uuid? file-id))
  (ptk/reify ::persist-synchronous-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (cond-> #{}
                       (features/active-feature? state "components/v2")
                       (conj "components/v2"))
            sid      (:session-id state)
            file     (dm/get-in state [:workspace-libraries file-id])

            params  {:id (:id file)
                     :revn (:revn file)
                     :session-id sid
                     :changes changes
                     :features features}]

        (when (:id params)
          (->> (rp/cmd! :update-file params)
               (rx/ignore)))))))

(defn update-persistence-status
  [{:keys [status reason]}]
  (ptk/reify ::update-persistence-status
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-persistence
              (fn [local]
                (assoc local
                       :reason reason
                       :status status
                       :updated-at (dt/now)))))))


(defn shapes-persisted-event? [event]
  (= (ptk/type event) ::changes-persisted))

(defn shapes-changes-persisted
  [file-id {:keys [revn changes]}]
  (dm/assert! (uuid? file-id))
  (dm/assert! (int? revn))
  (dm/assert! (cpc/valid-changes? changes))

  (ptk/reify ::shapes-changes-persisted
    ptk/UpdateEvent
    (update [_ state]
      ;; NOTE: we don't set the file features context here because
      ;; there are no useful context for code that need to be executed
      ;; on the frontend side

      (if-let [current-file-id (:current-file-id state)]
        (if (= file-id current-file-id)
          (let [changes (group-by :page-id changes)]
            (-> state
                (update-in [:workspace-file :revn] max revn)
                (update :workspace-data (fn [file]
                                          (loop [fdata file
                                                 entries (seq changes)]
                                            (if-let [[page-id changes] (first entries)]
                                              (recur (-> fdata
                                                         (cp/process-changes changes)
                                                         (ctst/update-object-indices page-id))
                                                     (rest entries))
                                              fdata))))))
          (-> state
              (update-in [:workspace-libraries file-id :revn] max revn)
              (update-in [:workspace-libraries file-id :data] cp/process-changes changes)))

        state))))

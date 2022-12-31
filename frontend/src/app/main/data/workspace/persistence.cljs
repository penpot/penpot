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
   [app.common.pages.changes-spec :as pcs]
   [app.common.spec :as us]
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
   [cljs.spec.alpha :as s]
   [okulary.core :as l]
   [potok.core :as ptk]))

(log/set-level! :info)

(declare persist-changes)
(declare persist-synchronous-changes)
(declare shapes-changes-persisted)
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
              (st/emit! (update-persistence-status {:status :saving})))

            on-saved
            (fn []
              ;; Disable reload stoper
              (swap! st/ongoing-tasks disj :workspace-change)
              (st/emit! (update-persistence-status {:status :saved})))]

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
              (rx/sample-when (rx/merge
                               (rx/interval 5000)
                               (rx/filter #(= ::force-persist %) stream)
                               (->> (rx/from-atom commits)
                                    (rx/filter (complement empty?))
                                    (rx/debounce 2000))))
              (rx/tap #(reset! commits []))
              (rx/tap on-saving)
              (rx/mapcat (fn [changes]
                           ;; NOTE: this is needed for don't start the
                           ;; next persistence before this one is
                           ;; finished.
                           (rx/merge
                            (rx/of (persist-changes file-id changes))
                            (->> stream
                                 (rx/filter (ptk/type? ::changes-persisted))
                                 (rx/take 1)
                                 (rx/tap on-saved)
                                 (rx/ignore)))))
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
  [file-id changes]
  (log/debug :hint "persist changes" :changes (count changes))
  (us/verify ::us/uuid file-id)
  (ptk/reify ::persist-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [;; this features set does not includes the ffeat/enabled
            ;; because they are already available on the backend and
            ;; this request provides a set of features to enable in
            ;; this request.
            features (cond-> #{}
                       (features/active-feature? state :components-v2)
                       (conj "components/v2"))
            sid      (:session-id state)
            file     (get state :workspace-file)
            params   {:id (:id file)
                      :revn (:revn file)
                      :session-id sid
                      :changes-with-metadata (into [] changes)
                      :features features}]

        (when (= file-id (:id params))
          (->> (rp/cmd! :update-file params)
               (rx/mapcat (fn [lagged]
                            (log/debug :hint "changes persisted" :lagged (count lagged))
                            (let [frame-updates
                                  (-> (group-by :page-id changes)
                                      (update-vals #(into #{} (mapcat :frames) %)))]

                              (rx/merge
                               (->> (rx/from frame-updates)
                                    (rx/mapcat (fn [[page-id frames]]
                                                 (->> frames (map #(vector page-id %)))))
                                    (rx/map (fn [[page-id frame-id]] (dwt/update-thumbnail (:id file) page-id frame-id))))
                               (->> (rx/from lagged)
                                    (rx/merge-map (fn [{:keys [changes] :as entry}]
                                                    (rx/merge
                                                     (rx/from
                                                      (for [[page-id changes] (group-by :page-id changes)]
                                                        (dch/update-indices page-id changes)))
                                                     (rx/of (shapes-changes-persisted file-id entry))))))))))
               (rx/catch (fn [cause]
                           (rx/concat
                            (if (= :authentication (:type cause))
                              (rx/empty)
                              (rx/of (rt/assign-exception cause)))
                            (rx/throw cause))))))))))

(defn persist-synchronous-changes
  [{:keys [file-id changes]}]
  (us/verify ::us/uuid file-id)
  (ptk/reify ::persist-synchronous-changes
    ptk/WatchEvent
    (watch [_ state _]
      (let [features (cond-> #{}
                       (features/active-feature? state :components-v2)
                       (conj "components/v2"))
            sid      (:session-id state)
            file     (dm/get-in state [:workspace-libraries file-id])

            params  {:id (:id file)
                     :revn (:revn file)
                     :session-id sid
                     :changes changes
                     :features features}]

        (when (:id params)
          (->> (rp/mutation :update-file params)
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

(s/def ::revn ::us/integer)
(s/def ::shapes-changes-persisted
  (s/keys :req-un [::revn ::pcs/changes]))

(defn shapes-persisted-event? [event]
  (= (ptk/type event) ::changes-persisted))

(defn shapes-changes-persisted
  [file-id {:keys [revn changes] :as params}]
  (us/verify! ::us/uuid file-id)
  (us/verify! ::shapes-changes-persisted params)
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




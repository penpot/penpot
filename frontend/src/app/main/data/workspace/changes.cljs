;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.changes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.pages.changes :as cpc]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.schema :as sm]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(defonce page-change? #{:add-page :mod-page :del-page :mov-page})
(defonce update-layout-attr? #{:hidden})

(declare commit-changes)

(defn- add-undo-group
  [changes state]
  (let [undo            (:workspace-undo state)
        items           (:items undo)
        index           (or (:index undo) (dec (count items)))
        prev-item       (when-not (or (empty? items) (= index -1))
                          (get items index))
        undo-group      (:undo-group prev-item)
        add-undo-group? (and
                         (not (nil? undo-group))
                         (= (get-in changes [:redo-changes 0 :type]) :mod-obj)
                         (= (get-in prev-item [:redo-changes 0 :type]) :add-obj)
                         (contains? (:tags prev-item) :alt-duplication))] ;; This is a copy-and-move with mouse+alt

    (cond-> changes add-undo-group? (assoc :undo-group undo-group))))

(def commit-changes? (ptk/type? ::commit-changes))

(defn update-shapes
  ([ids update-fn] (update-shapes ids update-fn nil))
  ([ids update-fn {:keys [reg-objects? save-undo? stack-undo? attrs ignore-tree page-id ignore-remote? ignore-touched]
                   :or {reg-objects? false save-undo? true stack-undo? false ignore-remote? false ignore-touched false}}]
   (dm/assert! (sm/coll-of-uuid? ids))
   (dm/assert! (fn? update-fn))

   (ptk/reify ::update-shapes
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id   (or page-id (:current-page-id state))
             objects   (wsh/lookup-page-objects state page-id)
             ids       (into [] (filter some?) ids)

             update-layout-ids
             (->> ids
                  (map (d/getf objects))
                  (filter #(some update-layout-attr? (pcb/changed-attrs % update-fn {:attrs attrs})))
                  (map :id))

             changes   (reduce
                        (fn [changes id]
                          (let [opts {:attrs attrs
                                      :ignore-geometry? (get ignore-tree id)
                                      :ignore-touched ignore-touched}]
                            (pcb/update-shapes changes [id] update-fn (d/without-nils opts))))
                        (-> (pcb/empty-changes it page-id)
                            (pcb/set-save-undo? save-undo?)
                            (pcb/set-stack-undo? stack-undo?)
                            (pcb/with-objects objects))
                        ids)
             changes (pcb/reorder-grid-children changes ids)
             changes (add-undo-group changes state)]
         (rx/concat
          (if (seq (:redo-changes changes))
            (let [changes  (cond-> changes reg-objects? (pcb/resize-parents ids))
                  changes (cond-> changes ignore-remote? (pcb/ignore-remote))]
              (rx/of (commit-changes changes)))
            (rx/empty))

          ;; Update layouts for properties marked
          (if (d/not-empty? update-layout-ids)
            (rx/of (ptk/data-event :layout/update update-layout-ids))
            (rx/empty))))))))

(defn send-update-indices
  []
  (ptk/reify ::send-update-indices
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rx/of
            (fn [state]
              (-> state
                  (dissoc ::update-indices-debounce)
                  (dissoc ::update-changes))))
           (rx/observe-on :async)))

    ptk/EffectEvent
    (effect [_ state _]
      (doseq [[page-id changes] (::update-changes state)]
        (uw/ask! {:cmd :update-page-index
                  :page-id page-id
                  :changes changes})))))

;; Update indices will debounce operations so we don't have to update
;; the index several times (which is an expensive operation)
(defn update-indices
  [page-id changes]

  (let [start (uuid/next)]
    (ptk/reify ::update-indices
      ptk/UpdateEvent
      (update [_ state]
        (if (nil? (::update-indices-debounce state))
          (assoc state ::update-indices-debounce start)
          (update-in state [::update-changes page-id] (fnil d/concat-vec []) changes)))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= (::update-indices-debounce state) start)
          (let [stopper (->> stream (rx/filter (ptk/type? :app.main.data.workspace/finalize)))]
            (rx/merge
             (->> stream
                  (rx/filter (ptk/type? ::update-indices))
                  (rx/debounce 50)
                  (rx/take 1)
                  (rx/map #(send-update-indices))
                  (rx/take-until stopper))
             (rx/of (update-indices page-id changes))))
          (rx/empty))))))

(defn changed-frames
  "Extracts the frame-ids changed in the given changes"
  [changes objects]

  (let [change->ids
        (fn [change]
          (case (:type change)
            :add-obj
            [(:parent-id change)]

            (:mod-obj :del-obj)
            [(:id change)]

            :mov-objects
            (d/concat-vec (:shapes change) [(:parent-id change)])

            []))]
    (into #{}
          (comp (mapcat change->ids)
                (keep #(cph/get-shape-id-root-frame objects %))
                (remove #(= uuid/zero %)))
          changes)))

(defn commit-changes
  "Schedules a list of changes to execute now, and add the corresponding undo changes to
   the undo stack.

   Options:
   - save-undo?: if set to false, do not add undo changes.
   - undo-group: if some consecutive changes (or even transactions) share the same
                 undo-group, they will be undone or redone in a single step
   "
  [{:keys [redo-changes undo-changes
           origin save-undo? file-id undo-group tags stack-undo?]
    :or {save-undo? true stack-undo? false tags #{} undo-group (uuid/next)}}]
  (let [error   (volatile! nil)
        page-id (:current-page-id @st/state)
        frames  (changed-frames redo-changes (wsh/lookup-page-objects @st/state))]
    (ptk/reify ::commit-changes
      cljs.core/IDeref
      (-deref [_]
        {:file-id file-id
         :hint-events @st/last-events
         :hint-origin (ptk/type origin)
         :changes redo-changes
         :page-id page-id
         :frames frames
         :save-undo? save-undo?
         :undo-group undo-group
         :tags tags
         :stack-undo? stack-undo?})

      ptk/UpdateEvent
      (update [_ state]
        (log/info :msg "commit-changes"
                  :js/undo-group (str undo-group)
                  :js/file-id (str (or file-id "nil"))
                  :js/redo-changes redo-changes
                  :js/undo-changes undo-changes)
        (let [current-file-id (get state :current-file-id)
              file-id         (or file-id current-file-id)
              path            (if (= file-id current-file-id)
                                [:workspace-data]
                                [:workspace-libraries file-id :data])]
          (try
            (dm/assert!
             "expect valid vector of changes"
             (and (cpc/changes? redo-changes)
                  (cpc/changes? undo-changes)))

            (update-in state path (fn [file]
                                    (-> file
                                        (cp/process-changes redo-changes false)
                                        (ctst/update-object-indices page-id))))

            (catch :default err
              (log/error :js/error err)
              (vreset! error err)
              state))))

      ptk/WatchEvent
      (watch [_ _ _]
        (when-not @error
          (let [;; adds page-id to page changes (that have the `id` field instead)
                add-page-id
                (fn [{:keys [id type page] :as change}]
                  (cond-> change
                    (and (page-change? type) (nil? (:page-id change)))
                    (assoc :page-id (or id (:id page)))))

                changes-by-pages
                (->> redo-changes
                     (map add-page-id)
                     (remove #(nil? (:page-id %)))
                     (group-by :page-id))

                process-page-changes
                (fn [[page-id _changes]]
                  (update-indices page-id redo-changes))]

            (rx/concat
             (rx/from (map process-page-changes changes-by-pages))

             (when (and save-undo? (seq undo-changes))
               (let [entry {:undo-changes undo-changes
                            :redo-changes redo-changes
                            :undo-group undo-group
                            :tags tags}]
                 (rx/of (dwu/append-undo entry stack-undo?)))))))))))

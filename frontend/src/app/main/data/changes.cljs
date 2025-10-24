;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.changes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cpc]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.features :as features]
   [app.main.worker :as mw]
   [app.render-wasm.shape :as wasm.shape]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :info)

(def page-change?
  #{:add-page :mod-page :del-page :mov-page})
(def update-layout-attr?
  #{:hidden})

(def commit?
  (ptk/type? ::commit))

(defn- fix-page-id
  "For events that modifies the page, page-id does not comes
  as a property so we assign it from the `id` property."
  [{:keys [id type page] :as change}]
  (cond-> change
    (and (page-change? type)
         (nil? (:page-id change)))
    (assoc :page-id (or id (:id page)))))

(defn- update-indexes
  "Given a commit, send the changes to the worker for updating the
  indexes."
  [commit attr]
  (ptk/reify ::update-indexes
    ptk/WatchEvent
    (watch [_ _ _]
      (let [changes (->> (get commit attr)
                         (map fix-page-id)
                         (filter :page-id)
                         (group-by :page-id))]

        (->> (rx/from changes)
             (rx/merge-map (fn [[page-id changes]]
                             (log/debug :hint "update-indexes" :page-id page-id :changes (count changes))
                             (mw/ask! {:cmd :index/update
                                       :page-id page-id
                                       :changes changes})))
             (rx/catch (fn [cause]
                         (log/warn :hint "unable to update index"
                                   :cause cause)
                         (rx/empty)))
             (rx/ignore))))))

(defn- get-pending-commits
  [{:keys [persistence]}]
  (->> (:queue persistence)
       (map (d/getf (:index persistence)))
       (not-empty)))

(def ^:private xf:map-page-id
  (map :page-id))

(defn- apply-changes-localy
  [{:keys [file-id redo-changes] :as commit} pending]
  (ptk/reify ::apply-changes-localy
    ptk/UpdateEvent
    (update [_ state]
      (let [undo-changes
            (if pending
              (->> pending
                   (map :undo-changes)
                   (reverse)
                   (mapcat identity)
                   (vec))
              nil)

            redo-changes
            (if pending
              (into redo-changes
                    (mapcat :redo-changes)
                    pending)
              redo-changes)

            apply-changes
            (fn [fdata]
              (let [fdata (cpc/process-changes fdata undo-changes false)
                    fdata (cpc/process-changes fdata redo-changes false)
                    pids (into #{} xf:map-page-id redo-changes)]
                (reduce #(ctst/update-object-indices %1 %2) fdata pids)))]

        (if (features/active-feature? state "render-wasm/v1")
          ;; Update the wasm model
          (let [shape-changes (volatile! {})

                state
                (binding [cts/*shape-changes* shape-changes]
                  (update-in state [:files file-id :data] apply-changes))]

            (let [objects (dm/get-in state [:files file-id :data :pages-index (:current-page-id state) :objects])]
              (wasm.shape/process-shape-changes! objects @shape-changes))

            state)

          ;; wasm renderer deactivated
          (update-in state [:files file-id :data] apply-changes))))))

(defn commit
  "Create a commit event instance"
  [{:keys [commit-id redo-changes undo-changes origin save-undo? features
           file-id file-revn file-vern undo-group tags stack-undo? source]}]

  (assert (cpc/check-changes redo-changes)
          "expect valid vector of changes for redo-changes")

  (assert (cpc/check-changes undo-changes)
          "expect valid vector of changes for undo-changes")

  (let [commit-id (or commit-id (uuid/next))
        source    (d/nilv source :local)
        local?    (= source :local)
        commit    {:id commit-id
                   :created-at (ct/now)
                   :source source
                   :origin (ptk/type origin)
                   :features features
                   :file-id file-id
                   :file-revn file-revn
                   :file-vern file-vern
                   :changes redo-changes
                   :redo-changes redo-changes
                   :undo-changes undo-changes
                   :save-undo? save-undo?
                   :undo-group undo-group
                   :tags tags
                   :stack-undo? stack-undo?}]

    (ptk/reify ::commit
      cljs.core/IDeref
      (-deref [_] commit)

      ptk/WatchEvent
      (watch [_ state _]
        (let [pending (when-not local?
                        (get-pending-commits state))]
          (rx/concat
           (rx/of (apply-changes-localy commit pending))
           (if pending
             (rx/concat
              (->> (rx/from (reverse pending))
                   (rx/map (fn [commit] (update-indexes commit :undo-changes))))
              (rx/of (update-indexes commit :redo-changes))
              (->> (rx/from pending)
                   (rx/map (fn [commit] (update-indexes commit :redo-changes)))))
             (rx/of (update-indexes commit :redo-changes)))))))))

(defn- resolve-file-revn
  [state file-id]
  (:revn (dsh/lookup-file state file-id)))

(defn- resolve-file-vern
  [state file-id]
  (:vern (dsh/lookup-file state file-id)))

(defn commit-changes
  "Schedules a list of changes to execute now, and add the corresponding undo changes to
   the undo stack.

   Options:
   - save-undo?: if set to false, do not add undo changes.
   - undo-group: if some consecutive changes (or even transactions) share the same
                 undo-group, they will be undone or redone in a single step
   "
  [{:keys [redo-changes undo-changes save-undo? undo-group tags stack-undo? file-id]
    :or {save-undo? true
         stack-undo? false
         undo-group (uuid/next)
         tags #{}}
    :as params}]
  (ptk/reify ::commit-changes
    ev/PerformanceEvent

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id     (or file-id (:current-file-id state))
            uchg        (vec undo-changes)
            rchg        (vec redo-changes)
            features    (get state :features)
            permissions (get state :permissions)]

        ;; Prevent commit changes by a viewer team member (it really should never happen)
        (when (:can-edit permissions)
          (rx/of (-> params
                     (assoc :undo-group undo-group)
                     (assoc :features features)
                     (assoc :tags tags)
                     (assoc :stack-undo? stack-undo?)
                     (assoc :save-undo? save-undo?)
                     (assoc :file-id file-id)
                     (assoc :file-revn (resolve-file-revn state file-id))
                     (assoc :file-vern (resolve-file-vern state file-id))
                     (assoc :undo-changes uchg)
                     (assoc :redo-changes rchg)
                     (commit))))))))

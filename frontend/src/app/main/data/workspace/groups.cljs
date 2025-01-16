;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.groups
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (cfh/order-by-indexed-shapes objects)
       reverse
       (map #(get objects %))))

(defn- get-empty-groups-after-group-creation
  "An auxiliary function that finds and returns a set of ids that
  corresponds to groups that should be deleted after a group creation.

  The corner case happens when you selects two (or more) shapes that
  belongs each one to different groups, and after creating the new
  group, one (or many) groups can become empty because they have had a
  single shape which is moved to the created group."
  [objects parent-id shapes]
  (let [ids     (cfh/clean-loops objects (into #{} (map :id) shapes))
        parents (into #{} (map #(cfh/get-parent-id objects %)) ids)]
    (loop [current-id (first parents)
           to-check (rest parents)
           removed-id? ids
           result #{}]

      (if-not current-id
        ;; Base case, no next element
        result

        (let [group (get objects current-id)]
          (if (and (not= :frame (:type group))
                   (not= current-id parent-id)
                   (empty? (remove removed-id? (:shapes group))))

            ;; Adds group to the remove and check its parent
            (let [to-check (concat to-check [(cfh/get-parent-id objects current-id)])]
              (recur (first to-check)
                     (rest to-check)
                     (conj removed-id? current-id)
                     (conj result current-id)))

            ;; otherwise recur
            (recur (first to-check)
                   (rest to-check)
                   removed-id?
                   result)))))))

(defn prepare-create-group
  [changes id objects page-id shapes base-name keep-name?]
  (let [frame-id  (:frame-id (first shapes))
        parent-id (:parent-id (first shapes))
        gname     (if (and keep-name?
                           (= (count shapes) 1)
                           (= (:type (first shapes)) :group))
                    (:name (first shapes))
                    base-name)

        selrect   (gsh/shapes->rect shapes)
        group-idx (->> shapes
                       last
                       :id
                       (cfh/get-position-on-parent objects)
                       inc)

        group     (cts/setup-shape {:id id
                                    :type :group
                                    :name gname
                                    :shapes (mapv :id shapes)
                                    :selrect selrect
                                    :x (:x selrect)
                                    :y (:y selrect)
                                    :width (:width selrect)
                                    :height (:height selrect)
                                    :parent-id parent-id
                                    :frame-id frame-id
                                    :index group-idx})

        ;; Shapes that are in a component, but are not root, must be detached,
        ;; because they will be now children of a non instance group.
        shapes-to-detach (filter ctk/in-component-copy-not-head? shapes)

        ;; Look at the `get-empty-groups-after-group-creation`
        ;; docstring to understand the real purpose of this code
        ids-to-delete (get-empty-groups-after-group-creation objects parent-id shapes)

        target-cell
        (when (ctl/grid-layout? objects parent-id)
          (ctl/get-cell-by-shape-id (get objects parent-id) (-> shapes last :id)))

        grid-parents
        (into []
              (comp (map :parent-id)
                    (filter (partial ctl/grid-layout? objects)))
              shapes)

        changes   (-> changes
                      (pcb/with-page-id page-id)
                      (pcb/with-objects objects)
                      (pcb/add-object group {:index group-idx})
                      (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
                      (pcb/change-parent (:id group) (reverse shapes))
                      (pcb/update-shapes (map :id shapes-to-detach) ctk/detach-shape)
                      (cond-> target-cell
                        (pcb/update-shapes
                         [parent-id]
                         (fn [parent]
                           (assoc-in parent [:layout-grid-cells (:id target-cell) :shapes] [(:id group)]))))
                      (pcb/update-shapes grid-parents ctl/assign-cells {:with-objects? true})
                      (pcb/remove-objects ids-to-delete))]

    [group changes]))

(defn remove-group-changes
  [it page-id group objects]
  (let [children (->> (:shapes group)
                      (cfh/order-by-indexed-shapes objects)
                      (mapv #(get objects %)))
        parent-id (cfh/get-parent-id objects (:id group))
        parent    (get objects parent-id)

        index-in-parent
        (->> (:shapes parent)
             (map-indexed vector)
             (filter #(#{(:id group)} (second %)))
             (ffirst)
             inc)]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)
        (pcb/change-parent parent-id children index-in-parent)
        (pcb/remove-objects [(:id group)]))))

(defn remove-frame-changes
  [it page-id frame objects]
  (let [children (->> (:shapes frame)
                      (cfh/order-by-indexed-shapes objects)
                      (mapv #(get objects %)))
        parent-id     (cfh/get-parent-id objects (:id frame))
        idx-in-parent (->> (:id frame)
                           (cfh/get-position-on-parent objects)
                           inc)]

    (-> (pcb/empty-changes it page-id)
        (pcb/with-objects objects)
        (cond-> (ctl/any-layout? frame)
          (pcb/update-shapes (:shapes frame) ctl/remove-layout-item-data))
        (pcb/change-parent parent-id children idx-in-parent)
        (pcb/remove-objects [(:id frame)]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GROUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-shapes
  [id ids & {:keys [change-selection?] :or {change-selection? false}}]
  (ptk/reify ::group-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [id (d/nilv id (uuid/next))
            page-id  (:current-page-id state)
            objects  (dsh/lookup-page-objects state page-id)

            shapes
            (->> ids
                 (cfh/clean-loops objects)
                 (remove #(ctn/has-any-copy-parent? objects (get objects %)))
                 (shapes-for-grouping objects))
            parents  (into #{} (map :parent-id) shapes)]
        (when-not (empty? shapes)
          (let [[group changes]
                (prepare-create-group (pcb/empty-changes it) id objects page-id shapes "Group" false)]
            (rx/of (dch/commit-changes changes)
                   (when change-selection?
                     (dws/select-shapes (d/ordered-set (:id group))))
                   (ptk/data-event :layout/update {:ids parents}))))))))

(defn group-selected
  []
  (ptk/reify ::group-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (group-shapes nil selected :change-selection? true))))))

(defn ungroup-shapes
  [ids & {:keys [change-selection?] :or {change-selection? false}}]
  (ptk/reify ::ungroup-shapes
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)

            prepare
            (fn [shape-id]
              (let [shape (get objects shape-id)
                    changes
                    (cond
                      (or (cfh/group-shape? shape) (cfh/bool-shape? shape))
                      (remove-group-changes it page-id shape objects)

                      (cfh/frame-shape? shape)
                      (remove-frame-changes it page-id shape objects))]

                (cond-> changes
                  (ctl/grid-layout? objects (:parent-id shape))
                  (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells {:with-objects? true}))))

            ids (->> ids
                     (remove #(ctn/has-any-copy-parent? objects (get objects %)))
                     ;; components can't be ungrouped
                     (remove #(ctk/instance-head? (get objects %))))

            changes-list (sequence (keep prepare) ids)

            parents (into #{}
                          (comp (map #(cfh/get-parent objects %))
                                (keep :id))
                          ids)

            child-ids
            (into (d/ordered-set)
                  (mapcat #(dm/get-in objects [% :shapes]))
                  ids)

            changes {:redo-changes (vec (mapcat :redo-changes changes-list))
                     :undo-changes (vec (mapcat :undo-changes changes-list))
                     :origin it}
            undo-id (js/Symbol)]

        (when-not (empty? ids)
          (rx/of (dwu/start-undo-transaction undo-id)
                 (dch/commit-changes changes)
                 (ptk/data-event :layout/update {:ids parents})
                 (dwu/commit-undo-transaction undo-id)
                 (when change-selection?
                   (dws/select-shapes child-ids))))))))

(defn ungroup-selected
  []
  (ptk/reify ::ungroup-selected
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected (dsh/lookup-selected state)]
        (rx/of (ungroup-shapes selected :change-selection? true))))))

(defn mask-group
  ([]
   (mask-group nil))
  ([ids]
   (ptk/reify ::mask-group
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id     (:current-page-id state)
             objects     (dsh/lookup-page-objects state page-id)
             selected    (->> (or ids (dsh/lookup-selected state))
                              (cfh/clean-loops objects)
                              (remove #(ctn/has-any-copy-parent? objects (get objects %))))
             shapes      (shapes-for-grouping objects selected)
             first-shape (first shapes)]
         (when-not (empty? shapes)
           (let [;; If the selected shape is a group, we can use it. If not,
                 ;; create a new group and set it as masked.
                 [group changes]
                 (if (and (= (count shapes) 1)
                          (= (:type (first shapes)) :group))
                   [first-shape (-> (pcb/empty-changes it page-id)
                                    (pcb/with-objects objects))]
                   (prepare-create-group (pcb/empty-changes it) (uuid/next) objects page-id shapes "Mask" true))

                 changes  (-> changes
                              (pcb/update-shapes (:shapes group)
                                                 (fn [shape]
                                                   (assoc shape
                                                          :constraints-h :scale
                                                          :constraints-v :scale)))
                              (pcb/update-shapes [(:id group)]
                                                 (fn [group]
                                                   (assoc group
                                                          :masked-group true
                                                          :selrect (:selrect first-shape)
                                                          :points (:points first-shape)
                                                          :transform (:transform first-shape)
                                                          :transform-inverse (:transform-inverse first-shape))))
                              (pcb/resize-parents [(:id group)]))
                 undo-id (js/Symbol)]

             (rx/of (dwu/start-undo-transaction undo-id)
                    (dch/commit-changes changes)
                    (dws/select-shapes (d/ordered-set (:id group)))
                    (ptk/data-event :layout/update {:ids [(:id group)]})
                    (dwu/commit-undo-transaction undo-id)))))))))

(defn unmask-group
  ([]
   (unmask-group nil))

  ([ids]
   (ptk/reify ::unmask-group
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id  (:current-page-id state)
             objects  (dsh/lookup-page-objects state page-id)

             masked-groups (->> (d/nilv ids (dsh/lookup-selected state))
                                (map  #(get objects %))
                                (filter #(or (= :bool (:type %)) (= :group (:type %)))))

             changes (reduce (fn [changes mask]
                               (-> changes
                                   (pcb/update-shapes [(:id mask)]
                                                      (fn [shape]
                                                        (dissoc shape :masked-group)))
                                   (pcb/resize-parents [(:id mask)])))
                             (-> (pcb/empty-changes it page-id)
                                 (pcb/with-objects objects))
                             masked-groups)]

         (rx/of (dch/commit-changes changes)))))))

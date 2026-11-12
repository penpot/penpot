;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.groups
  "The changes of the group, ungroup, mask and unmask actions."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]))

(defn shapes-for-grouping
  [objects selected]
  (->> selected
       (cfh/order-by-indexed-shapes objects)
       reverse
       (map #(get objects %))))

(defn shapes-for-group-or-mask
  "Shapes allowed for Group and Mask after the shared variant gates.

   `ids-for-structure-op` drops mains before `clean-loops` so a selected
   variant main does not swallow nested children."
  [objects ids]
  (shapes-for-grouping objects (ctn/ids-for-structure-op objects ids)))

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

                      ;; Create a group needs to reset the constraints to scale/scale
                      (pcb/update-shapes
                       (map :id shapes)
                       (fn [shape]
                         (-> shape
                             (d/assoc-when :constraints-h :scale)
                             (d/assoc-when :constraints-v :scale))))
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

(defn ungroup-ids
  "The shapes of `ids` an ungroup takes apart: not inside a copy, not a
   copy head and not a variant container."
  [objects ids]
  (->> ids
       (remove #(ctn/has-any-copy-parent? objects (get objects %)))
       (remove #(ctk/instance-head? (get objects %)))
       (remove #(ctk/is-variant-container? (get objects %)))))

(defn generate-ungroup-changes
  "The changes that ungroup the groups, bools and boards of `ids`, which
   `ungroup-ids` gives."
  [it page-id objects ids]
  (let [prepare
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

        changes-list (sequence (keep prepare) ids)]
    {:redo-changes (vec (mapcat :redo-changes changes-list))
     :undo-changes (vec (mapcat :undo-changes changes-list))
     :origin it}))

(defn generate-mask-changes
  "The group that masks `shapes`, which `shapes-for-group-or-mask` gives,
   and the changes that make it: a lone group becomes the mask, other
   shapes are grouped into a new one with id `id`."
  [it id page-id objects shapes]
  (let [first-shape (first shapes)
        [group changes]
        (if (and (= (count shapes) 1)
                 (= (:type (first shapes)) :group))
          [first-shape (-> (pcb/empty-changes it page-id)
                           (pcb/with-objects objects))]
          (prepare-create-group (pcb/empty-changes it) id objects page-id shapes "Mask" true))]
    [group
     (-> changes
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
         (pcb/resize-parents [(:id group)]))]))

(defn generate-unmask-changes
  "The changes that stop the groups and bools of `ids` masking."
  [it page-id objects ids]
  (let [masked-groups (->> ids
                           (map #(get objects %))
                           (filter #(or (= :bool (:type %)) (= :group (:type %)))))]
    (reduce (fn [changes mask]
              (-> changes
                  (pcb/update-shapes [(:id mask)]
                                     (fn [shape]
                                       (dissoc shape :masked-group)))
                  (pcb/resize-parents [(:id mask)])))
            (-> (pcb/empty-changes it page-id)
                (pcb/with-objects objects))
            masked-groups)))

(defn ungrouped-children
  "The children the ungroup of `ids` leaves in place of them."
  [objects ids]
  (into (d/ordered-set)
        (mapcat #(dm/get-in objects [% :shapes]))
        ids))

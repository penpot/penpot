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
  ([it page-id group objects]
   (remove-group-changes it page-id group objects nil))
  ([it page-id group objects options]
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
         (pcb/change-parent parent-id children index-in-parent options)
         (pcb/remove-objects [(:id group)] options)
         ;; a group or bool around takes the shapes as they now are
         (pcb/resize-parents [parent-id])))))

(defn remove-frame-changes
  ([it page-id frame objects]
   (remove-frame-changes it page-id frame objects nil))
  ([it page-id frame objects options]
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
           (pcb/update-shapes (:shapes frame) ctl/remove-layout-item-data (select-keys options [:ignore-touched])))
         (pcb/change-parent parent-id children idx-in-parent options)
         (pcb/remove-objects [(:id frame)] options)
         ;; a group or bool around takes the shapes as they now are
         (pcb/resize-parents [parent-id])))))

(defn ungroup-ids
  "The shapes of `ids` an ungroup takes apart: not inside a copy, not a
   copy head and not a variant container."
  [objects ids]
  (->> ids
       (remove #(ctn/has-any-copy-parent? objects (get objects %)))
       (remove #(ctk/instance-head? (get objects %)))
       (remove #(ctk/is-variant-container? (get objects %)))))

(defn- copies-of
  "The `[page-id id]` of the shapes of `pages-index` that refer to a shape
   of `ids`, directly or through other copies."
  [pages-index ids]
  (let [ref-index (reduce-kv (fn [index page-id page]
                               (reduce (fn [index shape]
                                         (cond-> index
                                           (:shape-ref shape)
                                           (update (:shape-ref shape) (fnil conj []) [page-id (:id shape)])))
                                       index
                                       (vals (:objects page))))
                             {}
                             pages-index)]
    (loop [pending (seq ids) result []]
      (if-let [id (first pending)]
        (let [hits (get ref-index id)]
          (recur (concat (rest pending) (map second hits)) (into result hits)))
        result))))

(defn- with-ref-tracks
  "`grid` with the tracks of `ref-grid` on the axes it does not override,
   adding the cells of the tracks it lacks."
  [grid ref-grid]
  (letfn [(follow [grid attr add-track]
            (let [tracks (get ref-grid attr)
                  own    (get grid attr)]
              (if (or (ctk/touched-group? grid attr) (< (count tracks) (count own)))
                grid
                (-> (reduce add-track grid (drop (count own) tracks))
                    (assoc attr tracks)))))]
    (-> grid
        (follow :layout-grid-rows ctl/add-grid-row)
        (follow :layout-grid-columns ctl/add-grid-column))))

(defn- assign-ungrouped-cells
  "`grid` with the cells of the children an ungroup brings into it assigned.
   A copy with its own cells first takes the tracks of `ref-grid`, the grid
   it refers to as the ungroup leaves it, on the axes it does not override;
   then it gives its free cells to them in the layer order of its main, as
   sync does, which is the reverse of the order the main's grid flows them
   in."
  [grid objects ref-grid]
  (if (ctk/touched-group? grid :layout-grid-cells)
    (-> grid
        (cond-> (some? ref-grid) (with-ref-tracks ref-grid))
        (update :shapes (comp vec reverse))
        (ctl/assign-cells objects)
        (assoc :shapes (:shapes grid)))
    (ctl/assign-cells grid objects)))

(defn- ungroup-shape-changes
  "The changes that ungroup the group, bool or board `shape-id` of the
   page `page-id`, whose shapes are `objects`; `options` of the moves and
   removals. `ref-grids` holds the grids the ungroups before left, by id."
  [it page-id objects shape-id options ref-grids]
  (let [shape (get objects shape-id)
        changes
        (cond
          (or (cfh/group-shape? shape) (cfh/bool-shape? shape))
          (remove-group-changes it page-id shape objects options)

          (cfh/frame-shape? shape)
          (remove-frame-changes it page-id shape objects options))]

    (cond-> changes
      (and changes (ctl/grid-layout? objects (:parent-id shape)))
      (pcb/update-shapes [(:parent-id shape)]
                         (fn [grid objects]
                           (assign-ungrouped-cells grid objects (get ref-grids (:shape-ref grid))))
                         (merge {:with-objects? true} (select-keys options [:ignore-touched]))))))

(defn- ungrouped-grid
  "The grid board that held the shape `shape-id` of `objects`, as `changes`
   leave it, or nil."
  [changes objects shape-id]
  (let [parent-id (:parent-id (get objects shape-id))]
    (when (and changes (ctl/grid-layout? objects parent-id))
      (get (pcb/get-objects (pcb/apply-changes-local changes)) parent-id))))

(defn generate-ungroup-changes
  "The changes that ungroup the groups, bools and boards of `ids`, which
   `ungroup-ids` gives. With the file's `pages-index`, the copies of each
   one in the file are ungrouped alike: their children keep their
   overrides and nothing is left referring to a removed shape."
  ([it page-id objects ids]
   (generate-ungroup-changes it page-id objects ids nil))
  ([it page-id objects ids pages-index]
   (let [copies       (copies-of pages-index ids)
         ;; mains first, then each copy after the shape it refers to, so a
         ;; copy's grid finds the one it refers to as its ungroup left it
         targets      (concat (map (fn [id] [page-id id nil]) ids)
                              (map (fn [[copy-page-id id]]
                                     [copy-page-id id {:allow-altering-copies true :ignore-touched true}])
                                   copies))
         changes-list (->> targets
                           (reduce (fn [[changes-list ref-grids] [target-page-id id options]]
                                     (let [target-objects (if (= target-page-id page-id)
                                                            objects
                                                            (get-in pages-index [target-page-id :objects]))
                                           changes        (ungroup-shape-changes it target-page-id target-objects
                                                                                 id options ref-grids)
                                           grid           (ungrouped-grid changes target-objects id)]
                                       [(cond-> changes-list changes (conj changes))
                                        (cond-> ref-grids grid (assoc (:id grid) grid))]))
                                   [[] {}])
                           first)]
     {:redo-changes (vec (mapcat :redo-changes changes-list))
      :undo-changes (vec (mapcat :undo-changes changes-list))
      :origin it})))

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

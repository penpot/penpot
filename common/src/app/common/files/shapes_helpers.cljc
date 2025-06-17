;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.shapes-helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]))

;; FIXME: move to logic?

(defn prepare-add-shape
  [changes shape objects]
  (let [index   (:index (meta shape))
        id      (:id shape)

        mod? (:mod? (meta shape))
        [row column :as cell]  (when-not mod? (:cell (meta shape)))

        changes (-> changes
                    (pcb/with-objects objects)
                    (cond-> (some? index)
                      (pcb/add-object shape {:index index}))
                    (cond-> (nil? index)
                      (pcb/add-object shape))
                    (cond-> (some? (:parent-id shape))
                      (pcb/change-parent (:parent-id shape) [shape] index))
                    (cond-> (some? cell)
                      (pcb/update-shapes [(:parent-id shape)] #(ctl/push-into-cell % [id] row column)))
                    (cond-> (ctl/grid-layout? objects (:parent-id shape))
                      (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells {:with-objects? true})))]

    [shape changes]))

(defn prepare-move-shapes-into-frame
  [changes frame-id shapes objects remove-layout-data?]
  (let [parent-id  (dm/get-in objects [frame-id :parent-id])
        shapes     (remove #(= % parent-id) shapes)
        to-move    (->> shapes
                        (map (d/getf objects))
                        (not-empty))]

    (if to-move
      (-> changes
          (cond-> (and remove-layout-data?
                       (not (ctl/any-layout? objects frame-id)))
            (pcb/update-shapes shapes ctl/remove-layout-item-data))
          (pcb/update-shapes shapes #(cond-> % (cfh/frame-shape? %) (assoc :hide-in-viewer true)))
          (pcb/change-parent frame-id to-move 0)
          (cond-> (ctl/grid-layout? objects frame-id)
            (-> (pcb/update-shapes [frame-id] ctl/assign-cells {:with-objects? true})
                (pcb/reorder-grid-children [frame-id]))))
      changes)))

(defn prepare-create-artboard-from-selection
  ([changes id parent-id objects selected index frame-name without-fill?]
   (prepare-create-artboard-from-selection
    changes id parent-id objects selected index frame-name without-fill? nil))

  ([changes id parent-id objects selected index frame-name without-fill? target-cell-id]
   (prepare-create-artboard-from-selection
    changes id parent-id objects selected index frame-name without-fill? target-cell-id nil))

  ([changes id parent-id objects selected index frame-name without-fill? target-cell-id delta]
   (when-let [selected-objs (->> selected
                                 (map (d/getf objects))
                                 (not-empty))]

     (let [;; We calculate here the ordered selection because it is used
           ;; multiple times and this avoid the need of creating the index
           ;; manytimes for single operation.
           selected'  (cfh/order-by-indexed-shapes objects selected)
           new-index  (or index
                          (->> (first selected')
                               (cfh/get-position-on-parent objects)
                               (inc)))

           srect        (gsh/shapes->rect selected-objs)
           selected-id  (first selected)
           selected-obj (get objects selected-id)

           frame-id     (get selected-obj :frame-id)
           parent-id    (or parent-id (get selected-obj :parent-id))
           base-parent  (get objects parent-id)

           layout-attrs
           (when (and (= 1 (count selected))
                      (ctl/any-layout? base-parent))
             (select-keys selected-obj ctl/layout-child-attrs))

           target-cell-id
           (if (and (nil? target-cell-id)
                    (ctl/grid-layout? objects parent-id))
             ;; Find the top-left grid cell of the selected elements
             (let [ncols (count (:layout-grid-columns base-parent))]
               (->> selected
                    (map #(ctl/get-cell-by-shape-id base-parent %))
                    (apply min-key (fn [{:keys [row column]}] (+ (* ncols row) column)))
                    :id))
             target-cell-id)


           attrs
           {:type :frame
            :x (cond-> (:x srect) delta (+ (:x delta)))
            :y (cond-> (:y srect) delta (+ (:y delta)))
            :width (:width srect)
            :height (:height srect)}

           shape
           (cts/setup-shape
            (cond-> attrs
              (some? id)
              (assoc :id id)

              (some? frame-name)
              (assoc :name frame-name)

              :always
              (assoc :frame-id frame-id
                     :parent-id parent-id
                     :shapes (into [] selected))

              (some? layout-attrs)
              (d/patch-object layout-attrs)

              ;; Frames from shapes will not be displayed in viewer and no clipped
              (or (not= frame-id uuid/zero) without-fill?)
              (assoc :fills [] :hide-in-viewer true :show-content true)))

           shape
           (with-meta shape {:index new-index})

           [shape changes]
           (prepare-add-shape changes shape objects)

           changes
           (prepare-move-shapes-into-frame changes (:id shape) selected' objects false)

           changes
           (cond-> changes
             (ctl/grid-layout? objects (:parent-id shape))
             (-> (pcb/update-shapes
                  [(:parent-id shape)]
                  (fn [parent objects]
                    ;; This restores the grid layout before adding and moving the shapes
                    ;; this is done because the add+move could have altered the layout and we
                    ;; want to do it after both operations are completed. Also here we could
                    ;; asign the new element to a target-cell
                    (-> parent
                        (assoc :layout-grid-cells (:layout-grid-cells base-parent))
                        (assoc :layout-grid-rows (:layout-grid-rows base-parent))
                        (assoc :layout-grid-columns (:layout-grid-columns base-parent))

                        (cond-> (some? target-cell-id)
                          (assoc-in [:layout-grid-cells target-cell-id :shapes] [(:id shape)]))
                        (ctl/assign-cells objects)))
                  {:with-objects? true})

                 (pcb/reorder-grid-children [(:parent-id shape)])))]

       [shape changes]))))


(defn prepare-create-empty-artboard
  [changes frame-id parent-id objects index frame-name without-fill? target-cell-id]

  (let [base-parent (get objects parent-id)

        attrs       {:type :frame
                     :x 0
                     :y 0
                     :width 0.01
                     :height 0.01}

        shape     (cts/setup-shape
                   (cond-> attrs
                     (some? frame-id)
                     (assoc :id frame-id)

                     (some? frame-name)
                     (assoc :name frame-name)

                     :always
                     (assoc :frame-id frame-id
                            :parent-id parent-id
                            :shapes [])

                     :always
                     (with-meta {:index index})

                     (or (not= frame-id uuid/zero) without-fill?)
                     (assoc :fills [] :hide-in-viewer true)))

        [shape changes]
        (prepare-add-shape changes shape objects)

        changes
        (cond-> changes
          (ctl/grid-layout? objects (:parent-id shape))
          (-> (cond-> (some? target-cell-id)
                (pcb/update-shapes
                 [(:parent-id shape)]
                 (fn [parent]
                   (-> parent
                       (assoc :layout-grid-cells (:layout-grid-cells base-parent))
                       (assoc-in [:layout-grid-cells target-cell-id :shapes] [frame-id])
                       (assoc :position :auto)))))
              (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells {:with-objects? true})
              (pcb/reorder-grid-children [(:parent-id shape)])))]

    [shape changes]))

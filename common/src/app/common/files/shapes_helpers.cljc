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
                      (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells)))]
    [shape changes]))

(defn prepare-move-shapes-into-frame
  [changes frame-id shapes objects]
  (let [ordered-indexes (cfh/order-by-indexed-shapes objects shapes)
        parent-id (get-in objects [frame-id :parent-id])
        ordered-indexes (->> ordered-indexes (remove #(= % parent-id)))
        to-move-shapes (map (d/getf objects) ordered-indexes)]
    (if (d/not-empty? to-move-shapes)
      (-> changes
          (cond-> (not (ctl/any-layout? objects frame-id))
            (pcb/update-shapes ordered-indexes ctl/remove-layout-item-data))
          (pcb/update-shapes ordered-indexes #(cond-> % (cfh/frame-shape? %) (assoc :hide-in-viewer true)))
          (pcb/change-parent frame-id to-move-shapes 0)
          (cond-> (ctl/grid-layout? objects frame-id)
            (pcb/update-shapes [frame-id] ctl/assign-cells))
          (pcb/reorder-grid-children [frame-id]))
      changes)))

(defn prepare-create-artboard-from-selection
  ([changes id parent-id objects selected index frame-name without-fill?]
   (prepare-create-artboard-from-selection
    changes id parent-id objects selected index frame-name without-fill? nil))

  ([changes id parent-id objects selected index frame-name without-fill? target-cell-id]
   (let [selected-objs (map #(get objects %) selected)
         new-index (or index
                       (cfh/get-index-replacement selected objects))]
     (when (d/not-empty? selected)
       (let [srect       (gsh/shapes->rect selected-objs)
             selected-id (first selected)

             frame-id    (dm/get-in objects [selected-id :frame-id])
             parent-id   (or parent-id (dm/get-in objects [selected-id :parent-id]))
             base-parent (get objects parent-id)

             attrs       {:type :frame
                          :x (:x srect)
                          :y (:y srect)
                          :width (:width srect)
                          :height (:height srect)}

             shape     (cts/setup-shape
                        (cond-> attrs
                          (some? id)
                          (assoc :id id)

                          (some? frame-name)
                          (assoc :name frame-name)

                          :always
                          (assoc :frame-id frame-id
                                 :parent-id parent-id
                                 :shapes (into [] selected))

                          :always
                          (with-meta {:index new-index})

                          (or (not= frame-id uuid/zero) without-fill?)
                          (assoc :fills [] :hide-in-viewer true)))

             [shape changes]
             (prepare-add-shape changes shape objects)

             changes
             (prepare-move-shapes-into-frame changes (:id shape) selected objects)

             changes
             (cond-> changes
               (ctl/grid-layout? objects (:parent-id shape))
               (-> (cond-> (some? target-cell-id)
                     (pcb/update-shapes
                      [(:parent-id shape)]
                      (fn [parent]
                        (-> parent
                            (assoc :layout-grid-cells (:layout-grid-cells base-parent))
                            (assoc-in [:layout-grid-cells target-cell-id :shapes] [id])
                            (assoc :position :auto)))))
                   (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells)
                   (pcb/reorder-grid-children [(:parent-id shape)])))]

         [shape changes])))))


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
              (pcb/update-shapes [(:parent-id shape)] ctl/assign-cells)
              (pcb/reorder-grid-children [(:parent-id shape)])))]

    [shape changes]))

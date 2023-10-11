;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.bounds-map
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]))

(defn objects->bounds-map
  [objects]
  (d/lazy-map
   (keys objects)
   #(gco/shape->points (get objects %))))

(defn shape->bounds
  "Retrieve the shape bounds"
  ([shape bounds-map objects]
   (shape->bounds shape bounds-map objects nil))

  ([{:keys [id] :as shape} bounds-map objects modif-tree]
   (let [shape-modifiers
         (if modif-tree
           (-> (dm/get-in modif-tree [id :modifiers])
               (ctm/select-geometry))
           (ctm/empty))

         children (cph/get-immediate-children objects id)]

     (cond
       (and (cph/mask-shape? shape) (seq children))
       (shape->bounds (-> children first) bounds-map objects modif-tree)

       (cph/group-shape? shape)
       (let [;; Transform here to then calculate the bounds relative to the transform
             current-bounds
             (cond-> @(get bounds-map id)
               (not (ctm/empty? shape-modifiers))
               (gtr/transform-bounds shape-modifiers))

             children-bounds
             (->> children
                  (mapv #(shape->bounds % bounds-map objects modif-tree)))]
         (gpo/merge-parent-coords-bounds children-bounds current-bounds))

       :else
       (cond-> @(get bounds-map id)
         (not (ctm/empty? shape-modifiers))
         (gtr/transform-bounds shape-modifiers))))))

(defn transform-bounds-map
  ([bounds-map objects modif-tree]
   (transform-bounds-map bounds-map objects modif-tree (->> (keys modif-tree) (map #(get objects %)))))

  ([bounds-map objects modif-tree tree-seq]
   (->> tree-seq
        reverse
        (reduce
         (fn [bounds-map shape]
           (assoc bounds-map
                  (:id shape)
                  (delay (shape->bounds shape bounds-map objects modif-tree))))
         bounds-map))))

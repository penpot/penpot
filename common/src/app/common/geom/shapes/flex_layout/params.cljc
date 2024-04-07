;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.params
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [app.common.types.shape-tree :as ctt]))

(defn calculate-params
  "Given the shapes calculate its flex parameters (horizontal vs vertical, gaps, etc)"
  ([objects shapes]
   (calculate-params objects shapes nil))

  ([objects shapes parent]
   (when (d/not-empty? shapes)
     (let [shapes (->> shapes (remove :hidden))

           points
           (->> shapes
                (map :id)
                (ctt/sort-z-index objects)
                (map (comp gco/shape->center (d/getf objects))))

           start (first points)
           end (reduce (fn [acc p] (gpt/add acc (gpt/to-vec start p))) points)

           angle (gpt/signed-angle-with-other
                  (gpt/to-vec start end)
                  (gpt/point 1 0))

           angle (mod angle 360)

           t1 (min (abs (-  angle 0)) (abs (-  angle 360)))
           t2 (abs (- angle 90))
           t3 (abs (- angle 180))
           t4 (abs (- angle 270))

           tmin (min t1 t2 t3 t4)

           direction
           (cond
             (mth/close? tmin t1) :row
             (mth/close? tmin t2) :column-reverse
             (mth/close? tmin t3) :row-reverse
             (mth/close? tmin t4) :column)

           selrects (->> shapes
                         (mapv :selrect))
           min-x (->> selrects
                      (mapv #(min (:x1 %) (:x2 %)))
                      (apply min))
           max-x (->> selrects
                      (mapv #(max (:x1 %) (:x2 %)))
                      (apply max))
           all-width (->> selrects
                          (map :width)
                          (reduce +))
           column-gap (if (and (> (count shapes) 1)
                               (or (= direction :row) (= direction :row-reverse)))
                        (/ (- (- max-x min-x) all-width)
                           (dec (count shapes)))
                        0)

           min-y (->> selrects
                      (mapv #(min (:y1 %) (:y2 %)))
                      (apply min))
           max-y (->> selrects
                      (mapv #(max (:y1 %) (:y2 %)))
                      (apply max))
           all-height (->> selrects
                           (map :height)
                           (reduce +))
           row-gap (if (and (> (count shapes) 1)
                            (or (= direction :column) (= direction :column-reverse)))
                     (/ (- (- max-y min-y) all-height)
                        (dec (count shapes)))
                     0)

           layout-gap {:row-gap (max row-gap 0)
                       :column-gap (max column-gap 0)}

           parent-selrect (:selrect parent)

           padding (when (and (not (nil? parent)) (> (count shapes) 0))
                     {:p1 (- min-y (:y1 parent-selrect))
                      :p2 (- min-x (:x1 parent-selrect))})]

       (cond-> {:layout-flex-dir direction :layout-gap layout-gap}
         (not (nil? padding))
         (assoc :layout-padding {:p1 (:p1 padding)
                                 :p2 (:p2 padding)
                                 :p3 (:p1 padding)
                                 :p4 (:p2 padding)}))))))

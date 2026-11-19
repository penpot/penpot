;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.geom.shapes.flex-layout.params
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [app.common.types.shape-tree :as ctt]))

(defn- direction-from-vector
  "The direction closest to `vector`, the sum of the vectors from the
  first shape to the centre of each shape."
  [vector]
  (let [angle (mod (gpt/signed-angle-with-other vector (gpt/point 1 0)) 360)

        t1 (min (abs (-  angle 0)) (abs (-  angle 360)))
        t2 (abs (- angle 90))
        t3 (abs (- angle 180))
        t4 (abs (- angle 270))

        tmin (min t1 t2 t3 t4)]
    (cond
      (mth/close? tmin t1) :row
      (mth/close? tmin t2) :column-reverse
      (mth/close? tmin t3) :row-reverse
      (mth/close? tmin t4) :column)))

(defn- overlaps?
  "Whether the ranges [a1 a2] and [b1 b2] share more than a point."
  [[a1 a2] [b1 b2]]
  (and (< a1 b2) (< b1 a2)))

(defn- axis-range
  [selrect k1 k2]
  [(min (k1 selrect) (k2 selrect))
   (max (k1 selrect) (k2 selrect))])

(defn- pair-axis
  "The axis two selrects are laid out along: :column when they overlap
  horizontally but not vertically, :row when they overlap vertically
  but not horizontally, nil otherwise."
  [a b]
  (let [x-overlap? (overlaps? (axis-range a :x1 :x2) (axis-range b :x1 :x2))
        y-overlap? (overlaps? (axis-range a :y1 :y2) (axis-range b :y1 :y2))]
    (cond
      (and x-overlap? (not y-overlap?)) :column
      (and y-overlap? (not x-overlap?)) :row)))

(defn- selrects-axis
  "The axis every pair of `selrects` that is laid out along one agrees
  on, or nil when no pair is or the pairs disagree."
  [selrects]
  (let [axes (into #{}
                   (keep (fn [[a b]] (pair-axis a b)))
                   (for [[idx a] (map-indexed vector selrects)
                         b       (drop (inc idx) selrects)]
                     [a b]))]
    (when (= 1 (count axes))
      (first axes))))

(defn- direction-along
  "The direction along `axis` that follows `vector`, so the shapes keep
  their layer order: the first shape ends up at the start unless the
  rest are before it."
  [axis vector]
  (case axis
    :column (if (neg? (:y vector)) :column-reverse :column)
    :row    (if (neg? (:x vector)) :row-reverse :row)))

(defn- pick-direction
  "How the shapes are laid out: the axis they are separated along when
  their boxes make it clear, the way the centres are spread otherwise."
  [selrects vector]
  (if-let [axis (selrects-axis selrects)]
    (direction-along axis vector)
    (direction-from-vector vector)))

(defn- coordinate-range
  "The lowest and highest coordinate of `selrects` between `k1` and `k2`."
  [selrects k1 k2]
  (let [ranges (map #(axis-range % k1 k2) selrects)]
    [(apply min (map first ranges))
     (apply max (map second ranges))]))

(defn- spread-gap
  "The space left between `selrects` once they are placed along the
  axis given by its coordinates `k1` `k2` and its size `size-key`."
  [selrects k1 k2 size-key]
  (let [[lo hi] (coordinate-range selrects k1 k2)
        all-size (transduce (map size-key) + selrects)]
    (/ (- hi lo all-size)
       (dec (count selrects)))))

(defn- layout-gap
  [selrects direction]
  (let [several? (> (count selrects) 1)
        row-gap    (if (and several? (#{:column :column-reverse} direction))
                     (spread-gap selrects :y1 :y2 :height)
                     0)
        column-gap (if (and several? (#{:row :row-reverse} direction))
                     (spread-gap selrects :x1 :x2 :width)
                     0)]
    {:row-gap (max row-gap 0)
     :column-gap (max column-gap 0)}))

(defn- layout-padding
  [selrects parent-selrect]
  (let [[min-x _] (coordinate-range selrects :x1 :x2)
        [min-y _] (coordinate-range selrects :y1 :y2)
        p1 (max 0 (- min-y (:y1 parent-selrect)))
        p2 (max 0 (- min-x (:x1 parent-selrect)))]
    {:p1 p1 :p2 p2 :p3 p1 :p4 p2}))

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

           selrects  (mapv :selrect shapes)
           direction (pick-direction selrects (gpt/to-vec start end))]

       (cond-> {:layout-flex-dir direction
                :layout-gap (layout-gap selrects direction)}
         (some? parent)
         (assoc :layout-padding (layout-padding selrects (:selrect parent))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.params
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.types.shape.layout :as ctl]
   [clojure.set :as set]))

;; Small functions to help with ranges
(defn rect->range
  "Creates ranges"
  [axis rect]
  (let [start (get (gpt/point rect) axis)]
    (if (= axis :x)
      [start (+ start (:width rect))]
      [start (+ start (:height rect))])))

(defn overlaps-range?
  "Return true if the ranges overlaps in the given axis"
  [axis [start-a end-a] rect]
  (let [[start-b end-b] (rect->range axis rect)]
    (or (< start-a start-b end-a)
        (< start-b start-a end-b)
        (mth/close? start-a start-b)
        (mth/close? end-a end-b))))

(defn join-range
  "Creates a new range given the rect"
  [axis [start-a end-a :as range] rect]
  (if (not range)
    (rect->range axis rect)
    (let [[start-b end-b] (rect->range axis rect)]
      [(min start-a start-b) (max end-a end-b)])))

(defn size-range
  [[start end]]
  (- end start))

(defn calculate-tracks
  "Given the geometry and the axis calculates the tracks for the given shapes"
  [axis shapes-by-axis]
  (loop [pending (seq shapes-by-axis)
         result []
         index 1
         current-track #{}
         current-range nil]
    (if pending
      (let [[next-shape rect :as next-shape+rects] (first pending)]

        (if (or (not current-range) (overlaps-range? axis current-range rect))
          ;; Add shape to current row
          (let [current-track (conj current-track (:id next-shape))
                current-range (join-range axis current-range rect)]
            (recur (next pending) result index current-track current-range))

          ;; New row
          (recur (next pending)
                 (conj result {:index index
                               :shapes current-track
                               :size (size-range current-range)})
                 (inc index)
                 #{(:id next-shape)}
                 (rect->range axis rect))))

      ;; Add the still ongoing row
      (conj result {:index index
                    :shapes current-track
                    :size (size-range current-range)}))))

(defn assign-shape-cells
  "Create cells for the defined tracks and assign the shapes to these cells"
  [params rows cols]

  (let [assign-cell
        (fn [[params auto?] row column]
          (let [row-num    (:index row)
                column-num (:index column)
                cell       (ctl/cell-by-row-column params row-num column-num)
                shape      (first (set/intersection (:shapes row) (:shapes column)))
                auto?      (and auto? (some? shape))]

            [(cond-> params
               (some? shape)
               (assoc-in [:layout-grid-cells (:id cell) :shapes] [shape])

               (not auto?)
               (assoc-in [:layout-grid-cells (:id cell) :position] :manual))
             auto?]))

        [params _]
        (->> rows
             (reduce
              (fn [result row]
                (->> cols
                     (reduce
                      #(assign-cell %1 row %2)
                      result)))
              [params true]))]
    params))

(defn calculate-params
  "Given the shapes calculate its grid parameters (horizontal vs vertical, gaps, etc)"
  ([objects shapes]
   (calculate-params objects shapes nil))

  ([_objects shapes parent]
   (if (empty? shapes)
     (-> {:layout-grid-columns [ctl/default-track-value ctl/default-track-value]
          :layout-grid-rows [ctl/default-track-value ctl/default-track-value]}
         (ctl/create-cells [1 1 2 2]))

     (let [shapes (->> shapes (remove :hidden))
           all-shapes-rect (gco/shapes->rect shapes)
           shapes+bounds
           (->> shapes
                (map #(vector % (grc/points->rect (get % :points)))))

           shapes-by-x
           (->> shapes+bounds
                (sort-by (comp :x second)))

           shapes-by-y
           (->> shapes+bounds
                (sort-by (comp :y second)))

           cols (calculate-tracks :x shapes-by-x)
           rows (calculate-tracks :y shapes-by-y)

           num-cols (count cols)
           num-rows (count rows)

           total-cols-width (->> cols (reduce #(+ %1 (:size %2)) 0))
           total-rows-height (->> rows (reduce #(+ %1 (:size %2)) 0))

           column-gap
           (if (= num-cols 1)
             0
             (/ (- (:width all-shapes-rect) total-cols-width) (dec num-cols)))

           row-gap
           (if (= num-rows 1)
             0
             (/ (- (:height all-shapes-rect) total-rows-height) (dec num-rows)))

           layout-grid-rows (mapv (constantly ctl/default-track-value) rows)
           layout-grid-columns (mapv (constantly ctl/default-track-value) cols)

           parent-childs-vector (gpt/to-vec (gpo/origin (:points parent)) (gpt/point all-shapes-rect))
           p-left (:x parent-childs-vector)
           p-top  (:y parent-childs-vector)]

       (-> {:layout-grid-columns layout-grid-columns
            :layout-grid-rows layout-grid-rows
            :layout-gap {:row-gap row-gap
                         :column-gap column-gap}
            :layout-padding {:p1 p-top :p2 p-left :p3 p-top :p4 p-left}
            :layout-grid-dir (if (> num-cols num-rows) :row :column)}
           (ctl/create-cells [1 1 num-cols num-rows])
           (assign-shape-cells rows cols))))))

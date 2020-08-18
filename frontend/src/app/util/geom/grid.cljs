;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.geom.grid
  (:require
   [app.common.math :as mth]
   [app.common.geom.point :as gpt]))

(def ^:private default-items 12)

(defn calculate-default-item-length
  "Calculates the item-length so the default number of items fits inside the frame-length"
  [frame-length margin gutter]
  (/ (- frame-length (+ margin (- margin gutter)) (* gutter default-items)) default-items))

(defn calculate-size
  "Calculates the number of rows/columns given the other grid parameters"
  [frame-length item-length margin gutter]
  (let [item-length (or item-length (calculate-default-item-length frame-length margin gutter))
        frame-length-no-margins (- frame-length (+ margin (- margin gutter)))]
    (mth/floor (/ frame-length-no-margins (+ item-length gutter)))))

(defn- calculate-column-grid
  [{:keys [width height x y] :as frame} {:keys [size gutter margin item-length type] :as params}]
  (let [size (if (number? size) size (calculate-size width item-length margin gutter))
        parts (/ width size)
        item-width (min (or item-length ##Inf) (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        item-height height
        initial-offset (case type
                         :right (- width (* item-width size) (* gutter (dec size)) margin)
                         :center (/ (- width (* item-width size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch type) (/ (- width (* item-width size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] (+ initial-offset x (* (+ item-width gutter) cur-val)))
        next-y (fn [cur-val] y)]
    [size item-width item-height next-x next-y]))

(defn- calculate-row-grid
  [{:keys [width height x y] :as frame} {:keys [size gutter margin item-length type] :as params}]
  (let [size (if (number? size) size (calculate-size height item-length margin gutter))
        parts (/ height size)
        item-width width
        item-height (min (or item-length ##Inf) (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        initial-offset (case type
                         :right (- height (* item-height size) (* gutter (dec size)) margin)
                         :center (/ (- height (* item-height size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch type) (/ (- height (* item-height size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] x)
        next-y (fn [cur-val] (+ initial-offset y (* (+ item-height gutter) cur-val)))]
    [size item-width item-height next-x next-y]))

(defn- calculate-square-grid
  [{:keys [width height x y] :as frame} {:keys [size] :as params}]
  (let [col-size (quot width size)
        row-size (quot height size)
        as-row-col (fn [value] [(quot value col-size) (rem value col-size)])
        next-x (fn [cur-val]
                 (let [[_ col] (as-row-col cur-val)] (+ x (* col size))))
        next-y (fn [cur-val]
                 (let [[row _] (as-row-col cur-val)] (+ y (* row size))))]
    [(* col-size row-size) size size next-x next-y]))

(defn grid-areas
  "Given a frame and the grid parameters returns the areas defined on the grid"
  [frame grid]
  (let [grid-fn (case (-> grid :type)
                    :column calculate-column-grid
                    :row calculate-row-grid
                    :square calculate-square-grid)
        [num-items item-width item-height next-x next-y] (grid-fn frame (-> grid :params))]
    (->>
     (range 0 num-items)
     (map #(hash-map :x (next-x %)
                     :y (next-y %)
                     :width item-width
                     :height item-height)))))

(defn grid-area-points
  [{:keys [x y width height]}]
  [(gpt/point x y)
   (gpt/point (+ x width) y)
   (gpt/point (+ x width) (+ y height))
   (gpt/point x (+ y height))])

(defn grid-snap-points
  "Returns the snap points for a given grid"
  ([shape coord] (mapcat #(grid-snap-points shape % coord) (:grids shape)))
  ([shape {:keys [type display params] :as grid} coord]
   (when (:display grid)
     (case type
       :square
       (let [{:keys [x y width height]} shape
             size (-> params :size)]
         (when (> size 0)
           (if (= coord :x)
             (mapcat #(vector (gpt/point (+ x %) y)
                              (gpt/point (+ x %) (+ y height))) (range size width size))
             (mapcat #(vector (gpt/point x (+ y %))
                              (gpt/point (+ x width) (+ y %))) (range size height size)))))

       :column
       (when (= coord :x)
         (->> (grid-areas shape grid)
              (mapcat grid-area-points)))

       :row
       (when (= coord :y)
         (->> (grid-areas shape grid)
              (mapcat grid-area-points)))))))

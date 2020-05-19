;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.geom.layout
  (:require
   [uxbox.util.math :as mth]
   [uxbox.util.geom.point :as gpt]))

(def ^:private default-items 12)

(defn calculate-default-item-length [frame-length margin gutter]
  (/ (- frame-length (+ margin (- margin gutter)) (* gutter default-items)) default-items))

(defn calculate-size
  "Calculates the number of rows/columns given the other layout parameters"
  [frame-length item-length margin gutter]
  (let [item-length (or item-length (calculate-default-item-length frame-length margin gutter))
        frame-length-no-margins (- frame-length (+ margin (- margin gutter)))]
    (mth/floor (/ frame-length-no-margins (+ item-length gutter)))))

(defn calculate-column-layout [{:keys [width height x y] :as frame} {:keys [size gutter margin item-width type] :as params}]
  (let [size (if (number? size) size (calculate-size width item-width margin gutter))
        parts (/ width size)
        item-width (or item-width (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        item-height height
        initial-offset (case type
                         :right (- width (* item-width size) (* gutter (dec size)) margin)
                         :center (/ (- width (* item-width size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch type) (/ (- width (* item-width size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] (+ initial-offset x (* (+ item-width gutter) cur-val)))
        next-y (fn [cur-val] y)]
    [size item-width item-height next-x next-y]))

(defn calculate-row-layout [{:keys [width height x y] :as frame} {:keys [size gutter margin item-height type] :as params}]
  (let [size (if (number? size) size (calculate-size height item-height margin gutter))
        parts (/ height size)
        item-width width
        item-height (or item-height (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        initial-offset (case type
                         :right (- height (* item-height size) (* gutter (dec size)) margin)
                         :center (/ (- height (* item-height size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch type) (/ (- height (* item-height size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] x)
        next-y (fn [cur-val] (+ initial-offset y (* (+ item-height gutter) cur-val)))]
    [size item-width item-height next-x next-y]))

(defn calculate-grid-layout [{:keys [width height x y] :as frame} {:keys [size] :as params}]
  (let [col-size (quot width size)
        row-size (quot height size)
        as-row-col (fn [value] [(quot value col-size) (rem value col-size)])
        next-x (fn [cur-val]
                 (let [[_ col] (as-row-col cur-val)] (+ x (* col size))))
        next-y (fn [cur-val]
                 (let [[row _] (as-row-col cur-val)] (+ y (* row size))))]
    [(* col-size row-size) size size next-x next-y]))

(defn layout-rects [frame layout]
  (let [layout-fn (case (-> layout :type)
                    :column calculate-column-layout
                    :row calculate-row-layout
                    :square calculate-grid-layout)
        [num-items item-width item-height next-x next-y] (layout-fn frame (-> layout :params))]
    (->>
     (range 0 num-items)
     (map #(hash-map :x (next-x %)
                     :y (next-y %)
                     :width item-width
                     :height item-height)))))

(defn- layout-rect-points [{:keys [x y width height]}]
  [(gpt/point x y)
   (gpt/point (+ x width) y)
   (gpt/point (+ x width) (+ y height))
   (gpt/point x (+ y height))])

(defn- layout-snap-points
  ([shape coord] (mapcat #(layout-snap-points shape % coord) (:layouts shape)))
  ([shape {:keys [type display params] :as layout} coord]

   (case type
     :square (let [{:keys [x y width height]} shape
                   size (-> params :size)]
               (when (> size 0)
                 (if (= coord :x)
                   (mapcat #(vector (gpt/point (+ x %) y)
                                    (gpt/point (+ x %) (+ y height))) (range size width size))
                   (mapcat #(vector (gpt/point x (+ y %))
                                    (gpt/point (+ x width) (+ y %))) (range size height size)))))
     :column (when (= coord :x) (->> (layout-rects shape layout)
                                     (mapcat layout-rect-points)))

     :row (when (= coord :y) (->> (layout-rects shape layout)
                                  (mapcat layout-rect-points))))))

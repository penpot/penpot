;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.grid
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]))

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

(defn- calculate-generic-grid
  [v width {:keys [size gutter margin item-length type]}]
  (let [size   (if (number? size)
                 size
                 (calculate-size width item-length margin gutter))
        parts  (/ width size)

        width' (min (or item-length ##Inf) (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))

        offset (case type
                 :right (- width (* width' size) (* gutter (dec size)) margin)
                 :center (/ (- width (* width' size) (* gutter (dec size))) 2)
                 margin)

        gutter (if (= :stretch type)
                 (let [gutter (/ (- width (* width' size) (* margin 2)) (dec size))]
                   (if (d/num? gutter) gutter 0))
                 gutter)

        next-v (fn [cur-val]
                 (+ offset v (* (+ width' gutter) cur-val)))]

    [size width' next-v gutter]))

(defn- calculate-column-grid
  [{:keys [width height x y] :as frame} params]
  (let [[size width next-x] (calculate-generic-grid x width params)]
    [size width height next-x (constantly y)]))

(defn- calculate-row-grid
  [{:keys [width height x y] :as frame} params]
  (let [[size height next-y] (calculate-generic-grid y height params)]
    [size width height (constantly x) next-y]))

(defn- calculate-square-grid
  [{:keys [width height x y] :as frame} {:keys [size] :as params}]
  (let [col-size   (quot width size)
        row-size   (quot height size)
        as-row-col (fn [value] [(quot value col-size) (rem value col-size)])
        next-x     (fn [cur-val]
                     (let [[_ col] (as-row-col cur-val)] (+ x (* col size))))
        next-y     (fn [cur-val]
                     (let [[row _] (as-row-col cur-val)] (+ y (* row size))))]

    [(* col-size row-size) size size next-x next-y]))

(defn grid-gutter
  [{:keys [x y width height]} {:keys [type params] :as grid}]

  (case type
    :column
    (let [[_ _ _ gutter] (calculate-generic-grid x width params)]
      gutter)

    :row
    (let [[_ _ _ gutter] (calculate-generic-grid y height params)]
      gutter)

    nil))

(defn grid-areas
  "Given a frame and the grid parameters returns the areas defined on the grid"
  [frame grid]
  (let [grid-fn (case (-> grid :type)
                  :column calculate-column-grid
                  :row    calculate-row-grid
                  :square calculate-square-grid)
        [num-items item-width item-height next-x next-y] (grid-fn frame (-> grid :params))]
    (->> (range 0 num-items)
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
  [shape {:keys [type params] :as grid} coord]
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
             (mapcat grid-area-points))))))

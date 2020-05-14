;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.geom.layout)

(defn calculate-column-layout [{:keys [width height x y] :as frame} {:keys [size gutter margin item-width type] :as params}]
  (let [parts (/ width size)
        item-width (or item-width (+ parts (- gutter) (/ gutter size) (- (/ (* margin 2) size))))
        item-height height
        initial-offset (case type
                         :right (- width (* item-width size) (* gutter (dec size)) margin)
                         :center (/ (- width (* item-width size) (* gutter (dec size))) 2)
                         margin)
        gutter (if (= :stretch type) (/ (- width (* item-width size) (* margin 2)) (dec size)) gutter)
        next-x (fn [cur-val] (+ initial-offset x (* (+ item-width gutter) cur-val)))
        next-y (fn [cur-val] y)]
    [item-width item-height next-x next-y]))

(defn calculate-row-layout [{:keys [width height x y] :as frame} {:keys [size gutter margin item-height type] :as params}]
  (let [{:keys [width height x y]} frame
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
    [item-width item-height next-x next-y]))

(defn layout-rects [frame layout]
  (let [[item-width item-height next-x next-y]
        (case (-> layout :type)
          :column (calculate-column-layout frame (-> layout :params))
          :row (calculate-row-layout frame (-> layout :params)))]
    (->>
     (range 0 (-> layout :params :size))
     (map #(hash-map :x (next-x %)
                     :y (next-y %)
                     :width item-width
                     :height item-height)))))

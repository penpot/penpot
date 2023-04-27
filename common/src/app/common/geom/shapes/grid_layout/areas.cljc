;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; Based on the code in:
;; https://en.wikibooks.org/wiki/Algorithm_Implementation/Geometry/Rectangle_difference
(ns app.common.geom.shapes.grid-layout.areas
  (:refer-clojure :exclude [contains?]))

(defn area->cell-props [[column row column-span row-span]]
  {:row row
   :column column
   :row-span row-span
   :column-span column-span})

(defn make-area
  ([{:keys [column row column-span row-span]}]
   (make-area column row column-span row-span))
  ([x y width height]
   [x y width height]))

(defn contains?
  [[a-x a-y a-width a-height :as a]
   [b-x b-y b-width b-height :as b]]
  (and (>= b-x a-x)
       (>= b-y a-y)
       (<= (+ b-x b-width) (+ a-x a-width))
       (<= (+ b-y b-height) (+ a-y a-height))))

(defn intersects?
  [[a-x a-y a-width a-height ]
   [b-x b-y b-width b-height]]
  (not (or (<= (+ b-x b-width) a-x)
           (<= (+ b-y b-height) a-y)
           (>= b-x (+ a-x a-width))
           (>= b-y (+ a-y a-height)))))

(defn top-rect
  [[a-x a-y a-width _]
   [_ b-y _ _]]
  (let [height (- b-y a-y)]
    (when (> height 0)
      (make-area a-x a-y a-width height))))

(defn bottom-rect
  [[a-x a-y a-width a-height]
   [_ b-y _ b-height]]

  (let [y (+ b-y b-height)
        height (- a-height (- y a-y))]
    (when (and (> height 0) (< y (+ a-y a-height)))
      (make-area a-x y a-width height))))

(defn left-rect
  [[a-x a-y _ a-height]
   [b-x b-y _ b-height]]

  (let [rb-y (+ b-y b-height)
        ra-y (+ a-y a-height)
        y1 (max a-y b-y)
        y2 (min ra-y rb-y)
        height (- y2 y1)
        width (- b-x a-x)]
    (when (and (> width 0) (> height 0))
      (make-area a-x y1 width height))))

(defn right-rect
  [[a-x a-y a-width a-height]
   [b-x b-y b-width b-height]]

  (let [rb-y (+ b-y b-height)
        ra-y (+ a-y a-height)
        y1 (max a-y b-y)
        y2 (min ra-y rb-y)
        height (- y2 y1)
        rb-x (+ b-x b-width)
        width (- a-width (- rb-x a-x))
        ]
    (when (and (> width 0) (> height 0))
      (make-area rb-x y1 width height)))
  )

(defn difference
  [area-a area-b]
  (if (or (nil? area-b)
          (not (intersects? area-a area-b))
          (contains? area-b area-a))
    []

    (into []
          (keep #(% area-a area-b))
          [top-rect left-rect right-rect bottom-rect])))
 

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.corners :as gsc]
   [app.common.geom.shapes.fit-frame :as gsff]
   [app.common.geom.shapes.intersect :as gsi]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]))

;; --- Outer Rect

(defn translate-to-frame
  [shape frame]
  (->> (gpt/point (- (dm/get-prop frame :x))
                  (- (dm/get-prop frame :y)))
       (gtr/move shape)))

(defn translate-from-frame
  [shape frame]
  (gtr/move shape (gpt/point (dm/get-prop frame :x)
                             (dm/get-prop frame :y))))

(defn shape->rect
  [shape]
  (let [x (dm/get-prop shape :x)
        y (dm/get-prop shape :y)
        w (dm/get-prop shape :width)
        h (dm/get-prop shape :height)]
    (when (d/num? x y w h)
      (grc/make-rect x y w h))))

;; --- Helpers

(defn bounding-box
  "Returns a rect that wraps the shape after all transformations applied."
  [shape]
  ;; TODO: perhaps we need to store this calculation in a shape attribute
  (grc/points->rect (:points shape)))

(defn left-bound
  "Returns the lowest x coord of the shape BEFORE applying transformations."
  ;; TODO: perhaps some day we want after transformations, but for the
  ;;       moment it's enough as is now.
  [shape]
  (or (:x shape) (:x (:selrect shape)))) ; Paths don't have :x attribute

(defn top-bound
  "Returns the lowest y coord of the shape BEFORE applying transformations."
  [shape]
  (or (:y shape) (:y (:selrect shape)))) ; Paths don't have :y attribute

(defn fully-contained?
  "Checks if one rect is fully inside the other"
  [rect other]
  (and (<= (:x1 rect) (:x1 other))
       (>= (:x2 rect) (:x2 other))
       (<= (:y1 rect) (:y1 other))
       (>= (:y2 rect) (:y2 other))))

(defn pad-selrec
  ([selrect] (pad-selrec selrect 1))
  ([selrect size]
   (let [inc #(+ % size)
         dec #(- % size)]
     (-> selrect
         (update :x dec)
         (update :y dec)
         (update :x1 dec)
         (update :y1 dec)
         (update :x2 inc)
         (update :y2 inc)
         (update :width (comp inc inc))
         (update :height (comp inc inc))))))

(defn get-areas
  [bounds selrect]
  (let [bound-x1 (dm/get-prop bounds :x1)
        bound-x2 (dm/get-prop bounds :x2)
        bound-y1 (dm/get-prop bounds :y1)
        bound-y2 (dm/get-prop bounds :y2)
        sr-x1    (dm/get-prop selrect :x1)
        sr-x2    (dm/get-prop selrect :x2)
        sr-y1    (dm/get-prop selrect :y1)
        sr-y2    (dm/get-prop selrect :y2)]
    {:left   (grc/corners->rect bound-x1 sr-y1 sr-x1 sr-y2)
     :top    (grc/corners->rect sr-x1 bound-y1 sr-x2 sr-y1)
     :right  (grc/corners->rect sr-x2 sr-y1 bound-x2 sr-y2)
     :bottom (grc/corners->rect sr-x1 sr-y2 sr-x2 bound-y2)}))

(defn distance-selrect
  [selrect other]

  (dm/assert!
   (and (grc/rect? selrect)
        (grc/rect? other)))

  (let [x1 (dm/get-prop other :x1)
        y1 (dm/get-prop other :y1)
        x2 (dm/get-prop selrect :x2)
        y2 (dm/get-prop selrect :y2)]
    (gpt/point (- x1 x2) (- y1 y2))))

(defn distance-shapes [shape other]
  (distance-selrect
   (dm/get-prop shape :selrect)
   (dm/get-prop other :selrect)))

(defn close-attrs?
  "Compares two shapes attributes to see if they are equal or almost
  equal (in case of numeric). Takes into account attributes that are
  data structures with numbers inside."
  ([attr val1 val2]
   (close-attrs? attr val1 val2 mth/float-equal-precision))

  ([attr val1 val2 precision]
   (let [close-val? (fn [num1 num2]
                      (when (and (number? num1) (number? num2))
                        (< (mth/abs (- num1 num2)) precision)))]
     (cond
       (and (number? val1) (number? val2))
       (close-val? val1 val2)

       (= attr :selrect)
       (every? #(close-val? (get val1 %) (get val2 %))
               [:x :y :x1 :y1 :x2 :y2 :width :height])

       (= attr :points)
       (every? #(and (close-val? (:x (first %)) (:x (second %)))
                     (close-val? (:y (first %)) (:y (second %))))
               (d/zip val1 val2))

       (= attr :position-data)
       (every? #(and (close-val? (:x (first %)) (:x (second %)))
                     (close-val? (:y (first %)) (:y (second %))))
               (d/zip val1 val2))

       :else
       (= val1 val2)))))

;; EXPORTS
(dm/export gco/shape->center)
(dm/export gco/shapes->rect)
(dm/export gco/points->center)
(dm/export gco/transform-points)
(dm/export gco/shape->points)

(dm/export gtr/move)
(dm/export gtr/absolute-move)
(dm/export gtr/transform-matrix)
(dm/export gtr/transform-str)
(dm/export gtr/inverse-transform-matrix)
(dm/export gtr/transform-rect)
(dm/export gtr/calculate-geometry)
(dm/export gtr/calculate-selrect)
(dm/export gtr/update-group-selrect)
(dm/export gtr/update-mask-selrect)
(dm/export gtr/apply-transform)
(dm/export gtr/transform-shape)
(dm/export gtr/transform-selrect)
(dm/export gtr/transform-selrect-matrix)
(dm/export gtr/transform-bounds)
(dm/export gtr/move-position-data)
(dm/export gtr/apply-objects-modifiers)
(dm/export gtr/apply-children-modifiers)
(dm/export gtr/update-shapes-geometry)

;; Constratins
(dm/export gct/calc-child-modifiers)

;; Intersection
(dm/export gsi/overlaps?)
(dm/export gsi/overlaps-path?)
(dm/export gsi/has-point?)
(dm/export gsi/has-point-rect?)
(dm/export gsi/rect-contains-shape?)

;; Constraints
(dm/export gct/default-constraints-h)
(dm/export gct/default-constraints-v)

;; Corners
(dm/export gsc/shape-corners-1)
(dm/export gsc/shape-corners-4)

;; Rect
(dm/export grc/rect->points)
(dm/export grc/center->rect)

;;
(dm/export gsff/fit-frame-modifiers)

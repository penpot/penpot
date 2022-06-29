;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bool :as gsb]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.corners :as gsc]
   [app.common.geom.shapes.intersect :as gin]
   [app.common.geom.shapes.layout :as gcl]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]))

;; --- Outer Rect

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (->> shapes
       (map (comp gpr/points->selrect :points gtr/transform-shape))
       (gpr/join-selrects)))

(defn translate-to-frame
  [shape {:keys [x y]}]
  (gtr/move shape (gpt/negate (gpt/point x y)))  )

(defn translate-from-frame
  [shape {:keys [x y]}]
  (gtr/move shape (gpt/point x y))  )

;; --- Helpers

(defn left-bound
  [shape]
  (get shape :x (:x (:selrect shape)))) ; Paths don't have :x attribute

(defn top-bound
  [shape]
  (get shape :y (:y (:selrect shape)))) ; Paths don't have :y attribute

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

(defn selrect->areas [bounds selrect]
  (let [make-selrect
        (fn [x1 y1 x2 y2]
          (let [x1 (min x1 x2)
                x2 (max x1 x2)
                y1 (min y1 y2)
                y2 (max y1 y2)]
            {:x1 x1 :y1 y1
             :x2 x2 :y2 y2
             :x x1  :y y1
             :width (- x2 x1)
             :height (- y2 y1)
             :type :rect}))
        {bound-x1 :x1 bound-x2 :x2 bound-y1 :y1 bound-y2 :y2} bounds
        {sr-x1 :x1 sr-x2 :x2 sr-y1 :y1 sr-y2 :y2} selrect]
    {:left   (make-selrect bound-x1 sr-y1 sr-x1 sr-y2)
     :top    (make-selrect sr-x1 bound-y1 sr-x2 sr-y1)
     :right  (make-selrect sr-x2 sr-y1 bound-x2 sr-y2)
     :bottom (make-selrect sr-x1 sr-y2 sr-x2 bound-y2)}))

(defn distance-selrect [selrect other]
  (let [{:keys [x1 y1]} other
        {:keys [x2 y2]} selrect]
    (gpt/point (- x1 x2) (- y1 y2))))

(defn distance-shapes [shape other]
  (distance-selrect (:selrect shape) (:selrect other)))

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
(dm/export gco/center-shape)
(dm/export gco/center-selrect)
(dm/export gco/center-rect)
(dm/export gco/center-points)
(dm/export gco/transform-points)

(dm/export gpr/make-rect)
(dm/export gpr/make-selrect)
(dm/export gpr/rect->selrect)
(dm/export gpr/rect->points)
(dm/export gpr/points->selrect)
(dm/export gpr/points->rect)
(dm/export gpr/center->rect)
(dm/export gpr/center->selrect)
(dm/export gpr/join-rects)
(dm/export gpr/join-selrects)
(dm/export gpr/contains-selrect?)

(dm/export gtr/move)
(dm/export gtr/absolute-move)
(dm/export gtr/transform-matrix)
(dm/export gtr/transform-str)
(dm/export gtr/inverse-transform-matrix)
(dm/export gtr/transform-point-center)
(dm/export gtr/transform-rect)
(dm/export gtr/calculate-adjust-matrix)
(dm/export gtr/update-group-selrect)
(dm/export gtr/update-mask-selrect)
(dm/export gtr/resize-modifiers)
(dm/export gtr/change-orientation-modifiers)
(dm/export gtr/rotation-modifiers)
(dm/export gtr/merge-modifiers)
(dm/export gtr/transform-shape)
(dm/export gtr/transform-selrect)
(dm/export gtr/transform-selrect-matrix)
(dm/export gtr/transform-bounds)
(dm/export gtr/modifiers->transform)
(dm/export gtr/empty-modifiers?)
(dm/export gtr/move-position-data)

;; Constratins
(dm/export gct/calc-child-modifiers)

;; Layout
(dm/export gcl/calc-layout-data)
(dm/export gcl/calc-layout-modifiers)

;; PATHS
(dm/export gsp/content->selrect)
(dm/export gsp/transform-content)
(dm/export gsp/open-path?)

;; Intersection
(dm/export gin/overlaps?)
(dm/export gin/has-point?)
(dm/export gin/has-point-rect?)
(dm/export gin/rect-contains-shape?)

;; Bool
(dm/export gsb/update-bool-selrect)
(dm/export gsb/calc-bool-content)

;; Constraints
(dm/export gct/default-constraints-h)
(dm/export gct/default-constraints-v)

;; Corners
(dm/export gsc/shape-corners-1)
(dm/export gsc/shape-corners-4)

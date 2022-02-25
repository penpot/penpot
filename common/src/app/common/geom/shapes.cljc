;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bool :as gsb]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.constraints :as gct]
   [app.common.geom.shapes.intersect :as gin]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]))

;; --- Setup (Initialize)
;; FIXME: Is this the correct place for these functions?

(defn- setup-rect
  "A specialized function for setup rect-like shapes."
  [shape {:keys [x y width height]}]
  (let [rect    {:x x :y y :width width :height height}
        points  (gpr/rect->points rect)
        selrect (gpr/points->selrect points)]
    (assoc shape
           :x x
           :y y
           :width width
           :height height
           :points points
           :selrect selrect)))

(defn- setup-image
  [{:keys [metadata] :as shape} props]
  (-> (setup-rect shape props)
      (assoc
       :proportion (/ (:width metadata)
                      (:height metadata))
       :proportion-lock true)))

(defn setup
  "A function that initializes the first coordinates for
  the shape. Used mainly for draw operations."
  [shape props]
  (case (:type shape)
    :image (setup-image shape props)
    (setup-rect shape props)))

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
        {frame-x1 :x1 frame-x2 :x2 frame-y1 :y1 frame-y2 :y2} bounds
        {sr-x1 :x1 sr-x2 :x2 sr-y1 :y1 sr-y2 :y2} selrect]
    {:left   (make-selrect frame-x1 sr-y1 (- sr-x1 2) sr-y2)
     :top    (make-selrect sr-x1 frame-y1 sr-x2 (- sr-y1 2))
     :right  (make-selrect (+ sr-x2 2) sr-y1 frame-x2 sr-y2)
     :bottom (make-selrect sr-x1 (+ sr-y2 2) sr-x2 frame-y2)}))

(defn distance-selrect [selrect other]
  (let [{:keys [x1 y1]} other
        {:keys [x2 y2]} selrect]
    (gpt/point (- x1 x2) (- y1 y2))))

(defn distance-shapes [shape other]
  (distance-selrect (:selrect shape) (:selrect other)))

(defn setup-selrect [shape]
  (let [selrect (gpr/rect->selrect shape)
        points  (gpr/rect->points shape)]
    (-> shape
        (assoc :selrect selrect
               :points points))))

(defn shape-stroke-margin
  [shape stroke-width]
  (if (= (:type shape) :path)
    ;; TODO: Calculate with the stroke offset (not implemented yet
    (mth/sqrt (* 2 stroke-width stroke-width))
    (- (mth/sqrt (* 2 stroke-width stroke-width)) stroke-width)))


;; EXPORTS
(dm/export gco/center-shape)
(dm/export gco/center-selrect)
(dm/export gco/center-rect)
(dm/export gco/center-points)
(dm/export gco/make-centered-rect)
(dm/export gco/transform-points)

(dm/export gpr/rect->selrect)
(dm/export gpr/rect->points)
(dm/export gpr/points->selrect)
(dm/export gpr/points->rect)
(dm/export gpr/center->rect)
(dm/export gpr/join-rects)
(dm/export gpr/contains-selrect?)

(dm/export gtr/move)
(dm/export gtr/absolute-move)
(dm/export gtr/transform-matrix)
(dm/export gtr/inverse-transform-matrix)
(dm/export gtr/transform-point-center)
(dm/export gtr/transform-rect)
(dm/export gtr/calculate-adjust-matrix)
(dm/export gtr/update-group-selrect)
(dm/export gtr/resize-modifiers)
(dm/export gtr/rotation-modifiers)
(dm/export gtr/merge-modifiers)
(dm/export gtr/transform-shape)
(dm/export gtr/transform-selrect)
(dm/export gtr/modifiers->transform)
(dm/export gtr/empty-modifiers?)

;; Constratins
(dm/export gct/calc-child-modifiers)

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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.geom.shapes.intersect :as gin]
   [app.common.spec :as us]))


;; --- Resize (Dimensions)
(defn resize-modifiers
  [shape attr value]
  (us/assert map? shape)
  (us/assert #{:width :height} attr)
  (us/assert number? value)
  (let [{:keys [proportion proportion-lock]} shape
        size (select-keys (:selrect shape) [:width :height])
        new-size (if-not proportion-lock
                   (assoc size attr value)
                   (if (= attr :width)
                     (-> size
                         (assoc :width value)
                         (assoc :height (/ value proportion)))
                     (-> size
                         (assoc :height value)
                         (assoc :width (* value proportion)))))
        width (:width new-size)
        height (:height new-size)

        shape-transform (:transform shape (gmt/matrix))
        shape-transform-inv (:transform-inverse shape (gmt/matrix))
        shape-center (gco/center-shape shape)
        {sr-width :width sr-height :height} (:selrect shape)

        origin (-> (gpt/point (:selrect shape))
                   (gtr/transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point width height)
                           (gpt/point sr-width sr-height))]
    {:resize-vector scalev
     :resize-origin origin
     :resize-transform shape-transform
     :resize-transform-inverse shape-transform-inv}))

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
       (gtr/transform-shape)
       (map (comp gpr/points->selrect :points))
       (gpr/join-selrects)))

(defn translate-to-frame
  [shape {:keys [x y]}]
  (gtr/move shape (gpt/negate (gpt/point x y)))  )

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

(defn rotation-modifiers
  [center shape angle]
  (let [displacement (let [shape-center (gco/center-shape shape)]
                       (-> (gmt/matrix)
                           (gmt/rotate angle center)
                           (gmt/rotate (- angle) shape-center)))]
    {:rotation angle
     :displacement displacement}))


;; EXPORTS
(d/export gco/center-shape)
(d/export gco/center-selrect)
(d/export gco/center-rect)
(d/export gco/center-points)
(d/export gco/make-centered-rect)

(d/export gpr/rect->selrect)
(d/export gpr/rect->points)
(d/export gpr/points->selrect)
(d/export gpr/points->rect)
(d/export gpr/center->rect)

(d/export gtr/transform-shape)
(d/export gtr/transform-matrix)
(d/export gtr/inverse-transform-matrix)
(d/export gtr/transform-point-center)
(d/export gtr/transform-rect)
(d/export gtr/update-group-selrect)
(d/export gtr/transform-points)
(d/export gtr/calculate-adjust-matrix)
(d/export gtr/move)
(d/export gtr/absolute-move)

;; PATHS
(d/export gsp/content->points)
(d/export gsp/content->selrect)
(d/export gsp/transform-content)

;; Intersection
(d/export gin/overlaps?)
(d/export gin/has-point?)
(d/export gin/has-point-rect?)

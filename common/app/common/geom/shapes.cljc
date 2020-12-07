;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.shapes
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gsp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.spec :as us]))

;; --- Relative Movement

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape {dx :x dy :y}]
  (let [dx (d/check-num dx)
        dy (d/check-num dy)]
    (-> shape
        (assoc-in [:modifiers :displacement] (gmt/translate-matrix (gpt/point dx dy)))
        (gtr/transform-shape))))

;; --- Absolute Movement

(declare absolute-move-rect)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape {:keys [x y]}]
  (let [dx (- (d/check-num x) (-> shape :selrect :x))
        dy (- (d/check-num y) (-> shape :selrect :y))]
    (move shape (gpt/point dx dy))))

;; --- Resize (Dimensions)
(defn resize
  [shape width height]
  (us/assert map? shape)
  (us/assert number? width)
  (us/assert number? height)

  (let [shape-transform (:transform shape (gmt/matrix))
        shape-transform-inv (:transform-inverse shape (gmt/matrix))
        shape-center (gco/center-shape shape)
        {sr-width :width sr-height :height} (:selrect shape)
        origin (-> (gpt/point (:selrect shape))
                   (gtr/transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point width height)
                           (gpt/point sr-width sr-height))]

    (-> shape
        (update :modifiers assoc
                :resize-vector scalev
                :resize-origin origin
                :resize-transform shape-transform
                :resize-transform-inverse shape-transform-inv)
        (gtr/transform-shape))))

(defn resize-rect
  [shape attr value]
  (us/assert map? shape)
  (us/assert #{:width :height} attr)
  (us/assert number? value)
  (let [{:keys [proportion proportion-lock]} shape
        size (select-keys shape [:width :height])
        new-size (if-not proportion-lock
                   (assoc size attr value)
                   (if (= attr :width)
                     (-> size
                         (assoc :width value)
                         (assoc :height (/ value proportion)))
                     (-> size
                         (assoc :height value)
                         (assoc :width (* value proportion)))))]
    (resize shape (:width new-size) (:height new-size))))

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
  (move shape (gpt/point (- x) (- y))))

(defn translate-from-frame
  [shape {:keys [x y]}]
  (move shape (gpt/point x y)))

;; --- Helpers

(defn contained-in?
  "Check if a shape is contained in the
  provided selection rect."
  [shape selrect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} selrect
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} (:selrect shape)]
    (and (neg? (- sy1 ry1))
         (neg? (- sx1 rx1))
         (pos? (- sy2 ry2))
         (pos? (- sx2 rx2)))))

;; TODO: This not will work for rotated shapes
(defn overlaps?
  "Check if a shape overlaps with provided selection rect."
  [shape rect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} (gpr/rect->selrect rect)
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} (gpr/points->selrect (:points shape))]

    (and (< rx1 sx2)
         (> rx2 sx1)
         (< ry1 sy2)
         (> ry2 sy1))))

(defn fully-contained?
  "Checks if one rect is fully inside the other"
  [rect other]
  (and (<= (:x1 rect) (:x1 other))
       (>= (:x2 rect) (:x2 other))
       (<= (:y1 rect) (:y1 other))
       (>= (:y2 rect) (:y2 other))))

(defn has-point?
  [shape position]
  (let [{:keys [x y]} position
        selrect {:x1 (- x 5)
                 :y1 (- y 5)
                 :x2 (+ x 5)
                 :y2 (+ y 5)
                 :x (- x 5)
                 :y (- y 5)
                 :width 10
                 :height 10
                 :type :rect}]
    (overlaps? shape selrect)))

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

(defn overlap-coord?
  "Checks if two shapes overlap in one axis"
  [coord shape other]
  (let [[s1c1 s1c2 s2c1 s2c2]
        ;; If checking if overlaps in x-axis we need to check the y
        ;; coordinates, and the other way around
        (if (= coord :x)
          [(get-in shape [:selrect :y1])
           (get-in shape [:selrect :y2])
           (get-in other [:selrect :y1])
           (get-in other [:selrect :y2])]
          [(get-in shape [:selrect :x1])
           (get-in shape [:selrect :x2])
           (get-in other [:selrect :x1])
           (get-in other [:selrect :x2])])]
    (or (and (>= s2c1 s1c1) (<= s2c1 s1c2))
        (and (>= s2c2 s1c1) (<= s2c2 s1c2))
        (and (>= s1c1 s2c1) (<= s1c1 s2c2))
        (and (>= s1c2 s2c1) (<= s1c2 s2c2)))))


(defn setup-selrect [shape]
  (let [selrect (gpr/rect->selrect shape)
        points  (gpr/rect->points shape)]
    (-> shape
        (assoc :selrect selrect
               :points points))))


;; EXPORTS
(d/export gco/center-shape)
(d/export gco/center-selrect)
(d/export gco/center-rect)
(d/export gpr/rect->selrect)
(d/export gpr/rect->points)
(d/export gpr/points->selrect)
(d/export gtr/transform-shape)
(d/export gtr/transform-matrix)
(d/export gtr/transform-point-center)
(d/export gtr/transform-rect)
(d/export gtr/update-group-selrect)

;; PATHS
(d/export gsp/content->points)
(d/export gsp/content->selrect)

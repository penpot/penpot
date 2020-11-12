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
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.geom.shapes.path :as gsp]
   [app.common.math :as mth]
   [app.common.data :as d]))

;; --- Relative Movement

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape {dx :x dy :y}]
  (let [dx (d/check-num dx)
        dy (d/check-num dy)]
    (-> shape
        (assoc-in [:modifiers :displacement] (gmt/translate-matrix (gpt/point dx dy)))
        (gtr/transform-shape)))

  #_(let [inc-x (nilf (fn [x] (+ (-chk x) (-chk dx))))
        inc-y (nilf (fn [y] (+ (-chk y) (-chk dy))))
        inc-point (nilf (fn [p] (-> p
                                    (update :x inc-x)
                                    (update :y inc-y))))]
    (-> shape
        (update :x inc-x)
        (update :y inc-y)
        (update-in [:selrect :x] inc-x)
        (update-in [:selrect :x1] inc-x)
        (update-in [:selrect :x2] inc-x)
        (update-in [:selrect :y] inc-y)
        (update-in [:selrect :y1] inc-y)
        (update-in [:selrect :y2] inc-y)
        (update :points #(mapv inc-point %))
        (update :segments #(mapv inc-point %)))))

;; --- Absolute Movement

(declare absolute-move-rect)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape {:keys [x y]}]
  (let [dx (- (d/check-num x) (-> shape :selrect :x))
        dy (- (d/check-num y) (-> shape :selrect :y))]
    (move shape (gpt/point dx dy))))

;; --- Paths

#_(defn update-path-point
  "Update a concrete point in the path.

  The point should exists before, this function
  does not adds it automatically."
  [shape index point]
  (assoc-in shape [:segments index] point))


;; --- Resize (Dimensions)
;;; TODO: CHANGE TO USE THE MODIFIERS
(defn resize
  [shape width height]
  (us/assert map? shape)
  (us/assert number? width)
  (us/assert number? height)
  (-> shape
      (assoc :width width :height height)
      (update :selrect (fn [selrect]
                         (assoc selrect
                                :x2 (+ (:x1 selrect) width)
                                :y2 (+ (:y1 selrect) height))))))

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
  [{:keys [metadata] :as shape} {:keys [x y width height] :as props}]
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

;; --- Resolve Shape

;; (declare resolve-rect-shape)
;; (declare translate-from-frame)
;; (declare translate-to-frame)
;; 
;; (defn resolve-shape
;;   [objects shape]
;;   (loop [pobj (get objects parent)]
;;     (if (= :frame (:type pobj))
;;       (translate-from-frame shape pobj)
;;       (recur (get objects (:parent pobj))))))


;; --- Outer Rect

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (let [points (->> shapes (mapcat :points))]
    (gpr/points->selrect points)))

(defn translate-to-frame
  [shape {:keys [x y] :as frame}]
  (move shape (gpt/point (- x) (- y))))

(defn translate-from-frame
  [shape {:keys [x y] :as frame}]
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
          {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :x x1 :y y1
           :width (- x2 x1) :height (- y2 y1) :type :rect})
        {frame-x1 :x1 frame-x2 :x2 frame-y1 :y1 frame-y2 :y2
         frame-width :width frame-height :height} bounds
        {sr-x1 :x1 sr-x2 :x2 sr-y1 :y1 sr-y2 :y2
         sr-width :width sr-height :height} selrect]
    {:left   (make-selrect frame-x1 sr-y1 sr-x1 sr-y2)
     :top    (make-selrect sr-x1 frame-y1 sr-x2 sr-y1)
     :right  (make-selrect sr-x2 sr-y1 frame-x2 sr-y2)
     :bottom (make-selrect sr-x1 sr-y2 sr-x2 frame-y2)}))

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


(defn setup-selrect [{:keys [x y width height] :as shape}]
  (-> shape
      (assoc :selrect
             {:x x :y y
              :width width :height height
              :x1 x :y1 y
              :x2 (+ x width) :y2 (+ y height)})))


;; EXPORTS
(defn center-shape [shape] (gco/center-shape shape))
(defn center-selrect [selrect] (gco/center-selrect selrect))
(defn center-rect [rect] (gco/center-rect rect))

(defn rect->selrect [rect] (gpr/rect->selrect rect))

#_(def shape->rect-shape gpr/shape->rect-shape)
#_(def fix-invalid-rect-values gtr/fix-invalid-rect-values)
#_(def rect->rect-shape gpr/rect->rect-shape)
(defn points->selrect [points] (gpr/points->selrect points))

#_(def transform-shape-point gtr/transform-shape-point)
#_(def update-path-selrect gtr/update-path-selrect)
#_(def transform gtr/transform)
(defn transform-shape [shape] (gtr/transform-shape shape))
(defn transform-matrix [shape] (gtr/transform-matrix shape))
(defn transform-point-center [point center transform] (gtr/transform-point-center point center transform))
(defn transform-rect [rect mtx] (gtr/transform-rect rect mtx))

;; PATHS
(defn content->points [content] (gsp/content->points content))
(defn content->selrect [content] (gsp/content->selrect content))

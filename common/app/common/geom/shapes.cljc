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
   [app.common.math :as mth]
   [app.common.data :as d]))

(defn- nilf
  "Returns a new function that if you pass nil as any argument will
  return nil"
  [f]
  (fn [& args]
    (if (some nil? args)
      nil
      (apply f args))))

;; --- Relative Movement

(declare move-rect)
(declare move-path)

(defn -chk
  "Function that checks if a number is nil or nan. Will return 0 when not
  valid and the number otherwise."
  [v]
  (if (or (not v) (mth/nan? v)) 0 v))

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape {dx :x dy :y}]
  (let [inc-x (nilf (fn [x] (+ (-chk x) (-chk dx))))
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

;; Duplicated from pages-helpers to remove cyclic dependencies
(defn get-children [id objects]
  (let [shapes (vec (get-in objects [id :shapes]))]
    (if shapes
      (d/concat shapes (mapcat #(get-children % objects) shapes))
      [])))

(defn recursive-move
  "Move the shape and all its recursive children."
  [shape dpoint objects]
  (let [children-ids (get-children (:id shape) objects)
        children (map #(get objects %) children-ids)]
    (map #(move % dpoint) (cons shape children))))

;; --- Absolute Movement

(declare absolute-move-rect)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape position]
  (case (:type shape)
    (:curve :path) shape
    (absolute-move-rect shape position)))

(defn- absolute-move-rect
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- (-chk x) (-chk (:x shape))) 0)
        dy (if y (- (-chk y) (-chk (:y shape))) 0)]
    (move shape (gpt/point dx dy))))

;; --- Proportions

(declare assign-proportions-path)
(declare assign-proportions-rect)

(defn assign-proportions
  [{:keys [type] :as shape}]
  (case type
    :path (assign-proportions-path shape)
    (assign-proportions-rect shape)))

(defn- assign-proportions-rect
  [{:keys [width height] :as shape}]
  (assoc shape :proportion (/ width height)))

;; --- Paths

(defn update-path-point
  "Update a concrete point in the path.

  The point should exists before, this function
  does not adds it automatically."
  [shape index point]
  (assoc-in shape [:segments index] point))

;; --- Setup Proportions

(declare setup-proportions-const)
(declare setup-proportions-image)

(defn setup-proportions
  [shape]
  (case (:type shape)
    :icon (setup-proportions-image shape)
    :image (setup-proportions-image shape)
    :text shape
    (setup-proportions-const shape)))

(defn setup-proportions-image
  [{:keys [metadata] :as shape}]
  (let [{:keys [width height]} metadata]
    (assoc shape
           :proportion (/ width height)
           :proportion-lock false)))

(defn setup-proportions-const
  [shape]
  (assoc shape
         :proportion 1
         :proportion-lock false))

;; --- Resize (Dimensions)

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

(declare setup-rect)
(declare setup-image)

(defn setup
  "A function that initializes the first coordinates for
  the shape. Used mainly for draw operations."
  [shape props]
  (case (:type shape)
    :image (setup-image shape props)
    (setup-rect shape props)))

(defn- setup-rect
  "A specialized function for setup rect-like shapes."
  [shape {:keys [x y width height]}]
  (as-> shape $
    (assoc $ :x x
             :y y
             :width width
             :height height)
    (assoc $ :points (gtr/shape->points $))
    (assoc $ :selrect (gpr/points->selrect (:points $)))))

(defn- setup-image
  [{:keys [metadata] :as shape} {:keys [x y width height] :as props}]
  (-> (setup-rect shape props)
      (assoc
       :proportion (/ (:width metadata)
                      (:height metadata))
       :proportion-lock true)))


;; --- Resolve Shape

(declare resolve-rect-shape)
(declare translate-from-frame)
(declare translate-to-frame)

(defn resolve-shape
  [objects shape]
  (case (:type shape)
    :rect (resolve-rect-shape objects shape)
    :group (resolve-rect-shape objects shape)
    :frame (resolve-rect-shape objects shape)))

(defn- resolve-rect-shape
  [objects {:keys [parent] :as shape}]
  (loop [pobj (get objects parent)]
    (if (= :frame (:type pobj))
      (translate-from-frame shape pobj)
      (recur (get objects (:parent pobj))))))


;; --- Outer Rect

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (let [shapes (map :selrect shapes)
        minx   (transduce (map :x1) min ##Inf shapes)
        miny   (transduce (map :y1) min ##Inf shapes)
        maxx   (transduce (map :x2) max ##-Inf shapes)
        maxy   (transduce (map :y2) max ##-Inf shapes)]
    {:x1 minx
     :y1 miny
     :x2 maxx
     :y2 maxy
     :x minx
     :y miny
     :width (- maxx minx)
     :height (- maxy miny)
     :points [(gpt/point minx miny)
              (gpt/point maxx miny)
              (gpt/point maxx maxy)
              (gpt/point minx maxy)]
     :type :rect}))

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
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} (gpr/shape->rect-shape selrect)
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} (gpr/shape->rect-shape shape)]
    (and (neg? (- sy1 ry1))
         (neg? (- sx1 rx1))
         (pos? (- sy2 ry2))
         (pos? (- sx2 rx2)))))

(defn overlaps?
  "Check if a shape overlaps with provided selection rect."
  [shape selrect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} (gpr/shape->rect-shape selrect)
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} (gpr/shape->rect-shape shape)]
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
      (assoc :selrect {:x x :y y
                       :width width :height height
                       :x1 x :y1 y
                       :x2 (+ x width) :y2 (+ y height)})))


;; EXPORTS
(def center gco/center)

(def shape->rect-shape gpr/shape->rect-shape)
(def fix-invalid-rect-values gtr/fix-invalid-rect-values)
(def rect->rect-shape gpr/rect->rect-shape)
(def points->selrect gpr/points->selrect)

(def transform-shape-point gtr/transform-shape-point)
(def update-path-selrect gtr/update-path-selrect)
(def transform gtr/transform)
(defn transform-shape [shape] (gtr/transform-shape shape))
(def transform-matrix gtr/transform-matrix)


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.geom
  (:require [cljs.pprint :refer [pprint]]
            [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.math :as mth]
            [uxbox.main.store :as st]))

;; --- Relative Movement

(declare move-rect)
(declare move-path)
(declare move-circle)
(declare move-group)

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape dpoint]
  (case (:type shape)
    :icon (move-rect shape dpoint)
    :image (move-rect shape dpoint)
    :rect (move-rect shape dpoint)
    :text (move-rect shape dpoint)
    :path (move-path shape dpoint)
    :circle (move-circle shape dpoint)
    :group (move-group shape dpoint)))

(defn- move-rect
  "A specialized function for relative movement
  for rect-like shapes."
  [shape {dx :x dy :y}]
  (assoc shape
         :x1 (mth/round (+ (:x1 shape) dx))
         :y1 (mth/round (+ (:y1 shape) dy))
         :x2 (mth/round (+ (:x2 shape) dx))
         :y2 (mth/round (+ (:y2 shape) dy))))

(defn- move-circle
  "A specialized function for relative movement
  for circle shapes."
  [shape {dx :x dy :y}]
  (assoc shape
         :cx (mth/round (+ (:cx shape) dx))
         :cy (mth/round (+ (:cy shape) dy))))

(defn- move-group
  "A specialized function for relative movement
  for group shapes."
  [shape {dx :x dy :y}]
  (assoc shape
         :dx (mth/round (+ (:dx shape 0) dx))
         :dy (mth/round (+ (:dy shape 0) dy))))

(defn- move-path
  "A specialized function for relative movement
  for path shapes."
  [shape {dx :x dy :y}]
  (let [points (:points shape)
        xf (comp
            (map #(update % :x + dx))
            (map #(update % :y + dy)))]
    (assoc shape :points (into [] xf points))))

;; --- Absolute Movement

(declare absolute-move-rect)
(declare absolute-move-circle)
(declare absolute-move-group)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape point]
  (case (:type shape)
    :icon (absolute-move-rect shape point)
    :image (absolute-move-rect shape point)
    :rect (absolute-move-rect shape point)
    :circle (absolute-move-circle shape point)
    :group (absolute-move-group shape point)))

(defn- absolute-move-rect
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- x (:x1 shape)) 0)
        dy (if y (- y (:y1 shape)) 0)]
    (move shape (gpt/point dx dy))))

(defn- absolute-move-circle
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- x(:cx shape)) 0)
        dy (if y (- y (:cy shape)) 0)]
    (move shape (gpt/point dx dy))))

(defn- absolute-move-group
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (throw (ex-info "Not implemented (TODO)" {})))

;; --- Rotation

;; TODO: maybe we can consider apply the rotation
;;       directly to the shape coordinates?

(defn rotate
  "Apply the rotation to the shape."
  [shape rotation]
  (assoc shape :rotation rotation))

;; --- Size

(declare size-rect)
(declare size-circle)
(declare size-path)

(defn size
  "Calculate the size of the shape."
  [shape]
  (case (:type shape)
    :group (assoc shape :width 100 :height 100)
    :circle (size-circle shape)
    :text (size-rect shape)
    :rect (size-rect shape)
    :icon (size-rect shape)
    :image (size-rect shape)
    :path (size-path shape)))

(defn- size-path
  [{:keys [points x1 y1 x2 y2] :as shape}]
  (if (and x1 y1 x2 y2)
    (assoc shape
           :width (- x2 x1)
           :height (- y2 y1))
    (let [minx (apply min (map :x points))
          miny (apply min (map :y points))
          maxx (apply max (map :x points))
          maxy (apply max (map :y points))]
      (assoc shape
             :width (- maxx minx)
             :height (- maxy miny)))))

(defn- size-rect
  "A specialized function for calculate size
  for rect-like shapes."
  [{:keys [x1 y1 x2 y2] :as shape}]
  (merge shape {:width (- x2 x1)
                :height (- y2 y1)}))

(defn- size-circle
  "A specialized function for calculate size
  for circle shape."
  [{:keys [rx ry] :as shape}]
  (merge shape {:width (* rx 2)
                :height (* ry 2)}))

;; --- Paths

(defn update-path-point
  "Update a concrete point in the path.

  The point should exists before, this function
  does not adds it automatically."
  [shape index point]
  (assoc-in shape [:points index] point))

;; --- Setup Proportions

(declare setup-proportions-rect)
(declare setup-proportions-image)

(defn setup-proportions
  [shape]
  (case (:type shape)
    :rect (setup-proportions-rect shape)
    :circle (setup-proportions-rect shape)
    :icon (setup-proportions-image shape)
    :image (setup-proportions-image shape)
    :text shape
    :path (setup-proportions-rect shape)))

(defn setup-proportions-image
  [{:keys [metadata] :as shape}]
  (let [{:keys [width height]} metadata]
    (assoc shape
           :proportion (/ width height)
           :proportion-lock false)))

(defn setup-proportions-rect
  [shape]
  (let [{:keys [width height]} (size shape)]
    (assoc shape
           :proportion (/ width height)
           :proportion-lock false)))

;; --- Resize (Dimentsions)

(declare resize-dim-rect)
(declare resize-dim-circle)

(defn resize-dim
  "Resize using calculated dimensions (eg, `width` and `height`)
  instead of absolute positions."
  [shape opts]
  (case (:type shape)
    :rect (resize-dim-rect shape opts)
    :icon (resize-dim-rect shape opts)
    :image (resize-dim-rect shape opts)
    :circle (resize-dim-circle shape opts)))

(defn- resize-dim-rect
  [{:keys [proportion proportion-lock x1 y1] :as shape}
   {:keys [width height]}]
  {:pre [(not (and width height))]}
  (if-not proportion-lock
    (if width
      (assoc shape :x2 (+ x1 width))
      (assoc shape :y2 (+ y1 height)))
    (if width
      (-> shape
          (assoc :x2 (+ x1 width))
          (assoc :y2 (+ y1 (/ width proportion))))
      (-> shape
          (assoc :y2 (+ y1 height))
          (assoc :x2 (+ x1 (* height proportion)))))))

(defn- resize-dim-circle
  [{:keys [proportion proportion-lock] :as shape}
   {:keys [rx ry]}]
  {:pre [(not (and rx ry))]}
  (if-not proportion-lock
    (if rx
      (assoc shape :rx rx)
      (assoc shape :ry ry))
    (if rx
      (-> shape
          (assoc :rx rx)
          (assoc :ry (/ rx proportion)))
      (-> shape
          (assoc :ry ry)
          (assoc :rx (* ry proportion))))))

;; --- Resize (Absolute)

(declare resize-rect)
(declare resize-circle)
(declare normalize-shape)
(declare equalize-sides)

(defn resize
  "Resize the shape using absolute position.
  NOTE: used in draw operation."
  [shape point]
  (case (:type shape)
    :rect (resize-rect shape point)
    :icon (resize-rect shape point)
    :image (resize-rect shape point)
    :text (resize-rect shape point)
    :path (resize-rect shape point)
    :circle (resize-circle shape point)))

(defn- resize-rect
  "A specialized function for absolute resize
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (-> (assoc shape :x2 x :y2 y)
      (normalize-shape)))

(defn- resize-circle
  "A specialized function for absolute resize
  for circle shapes."
  [shape {:keys [x y lock] :as pos}]
  (let [cx (:cx shape)
        cy (:cy shape)

        rx (mth/abs (- x cx))
        ry (mth/abs (- y cy))]
    (if lock
      (assoc shape :rx rx :ry rx)
      (assoc shape :rx rx :ry ry))))

(defn- normalize-shape
  "Normalize shape coordinates."
  [shape]
  (let [x1 (min (:x1 shape) (:x2 shape))
        y1 (min (:y1 shape) (:y2 shape))
        x2 (max (:x1 shape) (:x2 shape))
        y2 (max (:y1 shape) (:y2 shape))]
    (assoc shape :x1 x1 :x2 x2 :y1 y1 :y2 y2)))

(defn- equalize-sides
  "Fix shape sides to be equal according to the lock mode."
  [shape]
  (let [{:keys [x1 x2 y1 y2]} shape
        x-side (mth/abs (- x2 x1))
        y-side (mth/abs (- y2 y1))
        max-side (max x-side y-side)]
    (cond
      (and (> x1 x2) (> y1 y2))
      (assoc shape :x2 (- x1 max-side) :y2 (- y1 max-side))

      (and (< x1 x2) (< y1 y2))
      (assoc shape :x2 (+ x1 max-side) :y2 (+ y1 max-side))

      (and (> x1 x2) (< y1 y2))
      (assoc shape :x2 (- x1 max-side) :y2 (+ y1 max-side))

      (and (< x1 x2) (> y1 y2))
      (assoc shape :x2 (+ x1 max-side) :y2 (- y1 max-side)))))

;; --- Setup (Initialize)

(declare setup-rect)
(declare setup-image)
(declare setup-circle)
(declare setup-group)

(defn setup
  "A function that initializes the first coordinates for
  the shape. Used mainly for draw operations."
  [shape props]
  (case (:type shape)
    :rect (setup-rect shape props)
    :icon (setup-rect shape props)
    :image (setup-image shape props)
    :text (setup-rect shape props)
    :circle (setup-circle shape props)
    :group (setup-group shape props)))

(defn- setup-rect
  "A specialized function for setup rect-like shapes."
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :x1 x1
         :y1 y1
         :x2 x2
         :y2 y2))

(defn- setup-group
  "A specialized function for setup group shapes."
  [shape {:keys [x1 y1 x2 y2] :as props}]
  (assoc shape :initial props))

(defn- setup-circle
  "A specialized function for setup circle shapes."
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :cx x1
         :cy y1
         :rx (mth/abs (- x2 x1))
         :ry (mth/abs (- y2 y1))))

(defn- setup-image
  [{:keys [view-box] :as shape} {:keys [x1 y1 x2 y2] :as props}]
  (let [[_ _ width height] view-box]
    (assoc shape
           :x1 x1
           :y1 y1
           :x2 x2
           :y2 y2
           :proportion (/ width height)
           :proportion-lock true)))

;; --- Coerce to Rect-like shape.

(declare circle->rect-shape)
(declare path->rect-shape)
(declare group->rect-shape)

(defn shape->rect-shape
  "Coerce shape to rect like shape."
  ([shape] (shape->rect-shape @st/state shape))
  ([state {:keys [type] :as shape}]
   (case type
     :circle (circle->rect-shape state shape)
     :path (path->rect-shape state shape)
     :group (group->rect-shape state shape)
     shape)))

(defn shapes->rect-shape
  ([shapes] (shapes->rect-shape @st/state shapes))
  ([state [shape :as shapes]]
   {:pre [(seq shapes)]}
   (let [shapes (map shape->rect-shape shapes)
         minx (apply min (map :x1 shapes))
         miny (apply min (map :y1 shapes))
         maxx (apply max (map :x2 shapes))
         maxy (apply max (map :y2 shapes))]
     {:x1 minx
      :y1 miny
      :x2 maxx
      :y2 maxy
      :type :rect})))

(defn- group->rect-shape
  [state {:keys [id items rotation] :as group}]
  (let [shapes (map #(get-in state [:shapes %]) items)]
    (-> (shapes->rect-shape state shapes)
        (assoc :rotation rotation)
        (assoc :id id))))

(defn- path->rect-shape
  [state {:keys [points] :as shape}]
  (let [minx (apply min (map :x points))
        miny (apply min (map :y points))
        maxx (apply max (map :x points))
        maxy (apply max (map :y points))]
    (assoc shape
           :x1 minx
           :y1 miny
           :x2 maxx
           :y2 maxy)))

(defn- circle->rect-shape
  [state {:keys [cx cy rx ry] :as shape}]
  (let [width (* rx 2)
        height (* ry 2)
        x1 (- cx rx)
        y1 (- cy ry)]
    (assoc shape
           :x1 x1
           :y1 y1
           :x2 (+ x1 width)
           :y2 (+ y1 height))))

;; --- Transform Shape

(declare transform-rect)
(declare transform-circle)
(declare transform-path)

(defn transform
  "Apply the matrix transformation to shape."
  [{:keys [type] :as shape} xfmt]
  (case type
    :rect (transform-rect shape xfmt)
    :icon (transform-rect shape xfmt)
    :text (transform-rect shape xfmt)
    :image (transform-rect shape xfmt)
    :path (transform-path shape xfmt)
    :circle (transform-circle shape xfmt)))

(defn- transform-rect
  [{:keys [x1 y1] :as shape} mx]
  (let [{:keys [width height]} (size shape)
        tl (gpt/transform [x1 y1] mx)
        tr (gpt/transform [(+ x1 width) y1] mx)
        bl (gpt/transform [x1 (+ y1 height)] mx)
        br (gpt/transform [(+ x1 width) (+ y1 height)] mx)
        minx (apply min (map :x [tl tr bl br]))
        maxx (apply max (map :x [tl tr bl br]))
        miny (apply min (map :y [tl tr bl br]))
        maxy (apply max (map :y [tl tr bl br]))]
    (assoc shape
           :x1 minx
           :y1 miny
           :x2 (+ minx (- maxx minx))
           :y2 (+ miny (- maxy miny)))))

(defn- transform-circle
  [{:keys [cx cy rx ry] :as shape} xfmt]
  (let [{:keys [x1 y1 x2 y2]} (shape->rect-shape shape)
        tl (gpt/transform [x1 y1] xfmt)
        tr (gpt/transform [x2 y1] xfmt)
        bl (gpt/transform [x1 y2] xfmt)
        br (gpt/transform [x2 y2] xfmt)

        x (apply min (map :x [tl tr bl br]))
        y (apply min (map :y [tl tr bl br]))
        maxx (apply max (map :x [tl tr bl br]))
        maxy (apply max (map :y [tl tr bl br]))
        width (- maxx x)
        height (- maxy y)
        cx (+ x (/ width 2))
        cy (+ y (/ height 2))
        rx (/ width 2)
        ry (/ height 2)]
    (assoc shape :cx cx :cy cy :rx rx :ry ry)))

(defn- transform-path
  [{:keys [points] :as shape} xfmt]
  (let [points (mapv #(gpt/transform % xfmt) points)]
    (assoc shape :points points)))

;; --- Outer Rect

(declare selection-rect-generic)
(declare selection-rect-group)

(defn rotation-matrix
  "Generate a rotation matrix from shape."
  [{:keys [x1 y1 rotation] :as shape}]
  (let [{:keys [width height]} (size shape)
        x-center (+ x1 (/ width 2))
        y-center (+ y1 (/ height 2))]
    (-> (gmt/matrix)
        ;; (gmt/rotate* rotation (gpt/point x-center y-center)))))
        (gmt/translate  x-center y-center)
        (gmt/rotate rotation)
        (gmt/translate (- x-center) (- y-center)))))

(defn rotate-shape
  "Apply the transformation matrix to the shape."
  [shape]
  (let [mtx (rotation-matrix (size shape))]
    (transform shape mtx)))

(defn selection-rect
  "Return the selection rect for the shape."
  ([shape]
   (selection-rect @st/state shape))
  ([state shape]
   (case (:type shape)
     :group (selection-rect-group state shape)
     (selection-rect-generic state shape))))

(defn- selection-rect-generic
  [state {:keys [id x1 y1 x2 y2] :as shape}]
  (let [{:keys [displacement resize]} (get-in state [:workspace :modifiers id])]
    (-> (shape->rect-shape shape)
        (assoc :type :rect :id id)
        (transform (or resize (gmt/matrix)))
        (transform (or displacement (gmt/matrix)))
        (rotate-shape)
        (size))))

(defn- selection-rect-group
  [state {:keys [id group items] :as shape}]
  (let [{:keys [displacement resize]} (get-in state [:workspace :modifiers id])
        shapes (->> items
                    (map #(get-in state [:shapes %]))
                    (map #(selection-rect state %)))]
    (-> (shapes->rect-shape shapes)
        (assoc :id id)
        (transform (or resize (gmt/matrix)))
        (transform (or displacement (gmt/matrix)))
        (rotate-shape)
        (size))))

;; --- Helpers

(defn resolve-parent
  "Recursively resolve the real shape parent."
  ([shape]
   (resolve-parent @st/state shape))
  ([state {:keys [group] :as shape}]
   (if group
     (resolve-parent state (get-in state [:shapes group]))
     shape)))

(defn contained-in?
  "Check if a shape is contained in the
  provided selection rect."
  [shape selrect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} selrect
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} shape]
    (and (neg? (- sy1 ry1))
         (neg? (- sx1 rx1))
         (pos? (- sy2 ry2))
         (pos? (- sx2 rx2)))))

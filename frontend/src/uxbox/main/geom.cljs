;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.geom
  (:require [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.math :as mth]
            [uxbox.main.store :as st]))

;; --- Relative Movement

(declare move-rect)
(declare move-path)
(declare move-circle)

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape dpoint]
  (case (:type shape)
    :icon (move-rect shape dpoint)
    :image (move-rect shape dpoint)
    :rect (move-rect shape dpoint)
    :text (move-rect shape dpoint)
    :curve (move-path shape dpoint)
    :path (move-path shape dpoint)
    :circle (move-circle shape dpoint)))

(defn- move-rect
  "A specialized function for relative movement
  for rect-like shapes."
  [shape {dx :x dy :y}]
  (assoc shape
         :x (mth/round (+ (:x shape) dx))
         :y (mth/round (+ (:y shape) dy))))

(defn- move-circle
  "A specialized function for relative movement
  for circle shapes."
  [shape {dx :x dy :y}]
  (assoc shape
         :cx (mth/round (+ (:cx shape) dx))
         :cy (mth/round (+ (:cy shape) dy))))

(defn- move-path
  "A specialized function for relative movement
  for path shapes."
  [shape {dx :x dy :y}]
  (let [segments (:segments shape)
        xf (comp
            (map #(update % :x + dx))
            (map #(update % :y + dy)))]
    (assoc shape :segments (into [] xf segments))))

;; --- Absolute Movement

(declare absolute-move-rect)
(declare absolute-move-circle)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape position]
  (case (:type shape)
    :icon (absolute-move-rect shape position)
    :image (absolute-move-rect shape position)
    :rect (absolute-move-rect shape position)
    :circle (absolute-move-circle shape position)))

(defn- absolute-move-rect
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- x (:x shape)) 0)
        dy (if y (- y (:y shape)) 0)]
    (move shape (gpt/point dx dy))))

(defn- absolute-move-circle
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- x (:cx shape)) 0)
        dy (if y (- y (:cy shape)) 0)]
    (move shape (gpt/point dx dy))))

;; --- Rotation

;; TODO: maybe we can consider apply the rotation
;;       directly to the shape coordinates?
;; FIXME: deprecated, should be removed

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
    :circle (size-circle shape)
    :curve (size-path shape)
    :path (size-path shape)
    shape))

(defn- size-path
  [{:keys [segments x1 y1 x2 y2] :as shape}]
  (if (and x1 y1 x2 y2)
    (assoc shape
           :width (- x2 x1)
           :height (- y2 y1))
    (let [minx (apply min (map :x segments))
          miny (apply min (map :y segments))
          maxx (apply max (map :x segments))
          maxy (apply max (map :y segments))]
      (assoc shape
             :width (- maxx minx)
             :height (- maxy miny)))))

(defn- size-circle
  "A specialized function for calculate size
  for circle shape."
  [{:keys [rx ry] :as shape}]
  (merge shape {:width (* rx 2)
                :height (* ry 2)}))

;; --- Proportions

(declare assign-proportions-path)
(declare assign-proportions-circle)
(declare assign-proportions-rect)

(defn assign-proportions
  [{:keys [type] :as shape}]
  (case type
    :circle (assign-proportions-circle shape)
    :path (assign-proportions-path shape)
    (assign-proportions-rect shape)))

(defn- assign-proportions-rect
  [{:keys [width height] :as shape}]
  (assoc shape :proportion (/ width height)))

(defn- assign-proportions-circle
  [{:as shape}]
  (assoc shape :proportion 1))

;; TODO: implement the rest of shapes

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

;; --- Resize (Dimentsions)

(declare resize-dim-rect)
(declare resize-dim-circle)

(defn resize-dim
  "Resize using calculated dimensions (eg, `width` and `height`)
  instead of absolute positions."
  [shape opts]
  (case (:type shape)
    :cirle (resize-dim-circle shape opts)
    (resize-dim-rect shape opts)))

(defn- resize-dim-rect
  [{:keys [proportion proportion-lock x y] :as shape}
   {:keys [width height] :as dimensions}]
  (if-not proportion-lock
    (if width
      (assoc shape :width width)
      (assoc shape :height height))
    (if width
      (-> shape
          (assoc :width width)
          (assoc :height (/ width proportion)))
      (-> shape
          (assoc :height height)
          (assoc :width (* height proportion))))))

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

;; --- Resize

(defn calculate-scale-ratio
  "Calculate the scale factor from one shape to an other.

  The shapes should be of rect-like type because width
  and height are used for calculate the ratio."
  [origin final]
  [(/ (:width final) (:width origin))
   (/ (:height final) (:height origin))])

(defn generate-resize-matrix
  "Generate the resize transformation matrix given a corner-id, shape
  and the scale factor vector. The shape should be of rect-like type.

  Mainly used by drawarea and shape resize on workspace."
  [vid shape [scalex scaley]]
  (case vid
    :top-left
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x2 shape))
                              (+ (:y2 shape)))))
    :top-right
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x1 shape))
                              (+ (:y2 shape)))))
    :top
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x1 shape))
                              (+ (:y2 shape)))))
    :bottom-left
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x2 shape))
                              (+ (:y1 shape)))))
    :bottom-right
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x shape))
                              (+ (:y shape)))))
    :bottom
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x1 shape))
                              (+ (:y1 shape)))))
    :right
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x1 shape))
                              (+ (:y1 shape)))))
    :left
    (-> (gmt/matrix)
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (+ (:x2 shape))
                              (+ (:y1 shape)))))))


(defn resize-shape
  "Apply a resize transformation to a rect-like shape. The shape
  should have the `width` and `height` attrs, because these attrs
  are used for the resize transformation.

  Mainly used in drawarea and interactive resize on workspace
  with the main objective that on the end of resize have a way
  a calculte the resize ratio with `calculate-scale-ratio`."
  [vid shape {:keys [x y] :as point} lock?]
  (case vid
    :top-left
    (let [width (- (:x2 shape) x)
          height (- (:y2 shape) y)
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :top-right
    (let [width (- x (:x1 shape))
          height (- (:y2 shape) y)
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :top
    (let [width (- (:x2 shape) (:x1 shape))
          height (- (:y2 shape) y)
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :bottom-left
    (let [width (- (:x2 shape) x)
          height (- y (:y1 shape))
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :bottom-right
    (let [width (- x (:x shape))
          height (- y (:y shape))
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :bottom
    (let [width (- (:x2 shape) (:x1 shape))
          height (- y (:y1 shape))
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :left
    (let [width (- (:x2 shape) x)
          height (- (:y2 shape) (:y1 shape))
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))

    :right
    (let [width (- x (:x1 shape))
          height (- (:y2 shape) (:y1 shape))
          proportion (:proportion shape 1)]
      (assoc shape
             :width width
             :height (if lock? (/ width proportion) height)))))

;; --- Setup (Initialize)

(declare setup-rect)
(declare setup-image)
(declare setup-circle)

(defn setup
  "A function that initializes the first coordinates for
  the shape. Used mainly for draw operations."
  [shape props]
  (case (:type shape)
    :image (setup-image shape props)
    :circle (setup-circle shape props)
    (setup-rect shape props)))

(defn- setup-rect
  "A specialized function for setup rect-like shapes."
  [shape {:keys [x y width height]}]
  (assoc shape
         :x x
         :y y
         :width width
         :height height))

(defn- setup-circle
  "A specialized function for setup circle shapes."
  [shape {:keys [x y width height]}]
  (assoc shape
         :cx x
         :cy y
         :rx (mth/abs width)
         :ry (mth/abs height)))

(defn- setup-image
  [{:keys [metadata] :as shape} {:keys [x y width height] :as props}]
  (assoc shape
         :x x
         :y y
         :width width
         :height height
         :proportion (/ (:width metadata)
                        (:height metadata))
         :proportion-lock true))

;; --- Coerce to Rect-like shape.

(declare circle->rect-shape)
(declare path->rect-shape)
(declare group->rect-shape)
(declare rect->rect-shape)

(defn shape->rect-shape
  "Coerce shape to rect like shape."
  [{:keys [type] :as shape}]
  (case type
    :circle (circle->rect-shape shape)
    :path (path->rect-shape shape)
    :curve (path->rect-shape shape)
    (rect->rect-shape shape)))

(defn shapes->rect-shape
  [shapes]
  (let [shapes (mapv shape->rect-shape shapes)
        minx (transduce (map :x1) min shapes)
        miny (transduce (map :y1) min shapes)
        maxx (transduce (map :x2) max shapes)
        maxy (transduce (map :y2) max shapes)]
    {:x1 minx
     :y1 miny
     :x2 maxx
     :y2 maxy
     :x minx
     :y miny
     :width (- maxx minx)
     :height (- maxy miny)
     :type :rect}))

(defn- rect->rect-shape
  [{:keys [x y width height] :as shape}]
  (assoc shape
         :x1 x
         :y1 y
         :x2 (+ x width)
         :y2 (+ y height)))

(defn- path->rect-shape
  [{:keys [segments] :as shape}]
  (let [minx (transduce (map :x) min segments)
        miny (transduce (map :y) min segments)
        maxx (transduce (map :x) max segments)
        maxy (transduce (map :y) max segments)]
    (assoc shape
           :x1 minx
           :y1 miny
           :x2 maxx
           :y2 maxy
           :x minx
           :y miny
           :width (- maxx minx)
           :height (- maxy miny))))

(defn- circle->rect-shape
  [{:keys [cx cy rx ry] :as shape}]
  (let [width (* rx 2)
        height (* ry 2)
        x1 (- cx rx)
        y1 (- cy ry)]
    (assoc shape
           :x1 x1
           :y1 y1
           :x2 (+ x1 width)
           :y2 (+ y1 height)
           :x x1
           :y y1
           :width width
           :height height)))

;; --- Transform Shape

(declare transform-rect)
(declare transform-circle)
(declare transform-path)

(defn transform
  "Apply the matrix transformation to shape."
  [{:keys [type] :as shape} xfmt]
  (case type
    :canvas (transform-rect shape xfmt)
    :rect (transform-rect shape xfmt)
    :icon (transform-rect shape xfmt)
    :text (transform-rect shape xfmt)
    :image (transform-rect shape xfmt)
    :path (transform-path shape xfmt)
    :curve (transform-path shape xfmt)
    :circle (transform-circle shape xfmt)))

(defn- transform-rect
  [{:keys [x y width height] :as shape} mx]
  (let [tl (gpt/transform (gpt/point x y) mx)
        tr (gpt/transform (gpt/point (+ x width) y) mx)
        bl (gpt/transform (gpt/point x (+ y height)) mx)
        br (gpt/transform (gpt/point (+ x width) (+ y height)) mx)
        ;; TODO: replace apply with transduce (performance)
        minx (apply min (map :x [tl tr bl br]))
        maxx (apply max (map :x [tl tr bl br]))
        miny (apply min (map :y [tl tr bl br]))
        maxy (apply max (map :y [tl tr bl br]))]
    (assoc shape
           :x minx
           :y miny
           :width (- maxx minx)
           :height (- maxy miny))))

(defn- transform-circle
  [{:keys [cx cy rx ry] :as shape} xfmt]
  (let [{:keys [x1 y1 x2 y2]} (shape->rect-shape shape)
        tl (gpt/transform (gpt/point x1 y1) xfmt)
        tr (gpt/transform (gpt/point x2 y1) xfmt)
        bl (gpt/transform (gpt/point x1 y2) xfmt)
        br (gpt/transform (gpt/point x2 y2) xfmt)

        ;; TODO: replace apply with transduce (performance)
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
  [{:keys [segments] :as shape} xfmt]
  (let [segments (mapv #(gpt/transform % xfmt) segments)]
    (assoc shape :segments segments)))

;; --- Outer Rect

(defn rotation-matrix
  "Generate a rotation matrix from shape."
  [{:keys [x y width height rotation] :as shape}]
  (let [cx (+ x (/ width 2))
        cy (+ y (/ height 2))]
    (cond-> (gmt/matrix)
      (and rotation (pos? rotation))
      (gmt/rotate rotation (gpt/point cx cy)))))

(defn resolve-rotation
  [shape]
  (transform shape (rotation-matrix shape)))

(defn resolve-modifier
  [{:keys [modifier-mtx] :as shape}]
  (cond-> shape
    (gmt/matrix? modifier-mtx)
    (transform modifier-mtx)))

(def ^:private
  xf-resolve-shapes
  (comp (map shape->rect-shape)
        (map resolve-modifier)
        (map resolve-rotation)
        (map shape->rect-shape)))

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (let [shapes (into [] xf-resolve-shapes shapes)
        minx (transduce (map :x1) min shapes)
        miny (transduce (map :y1) min shapes)
        maxx (transduce (map :x2) max shapes)
        maxy (transduce (map :y2) max shapes)]
    {:x1 minx
     :y1 miny
     :x2 maxx
     :y2 maxy
     :x minx
     :y miny
     :width (- maxx minx)
     :height (- maxy miny)
     :type :rect}))

;; --- Helpers

(defn contained-in?
  "Check if a shape is contained in the
  provided selection rect."
  [shape selrect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} (shape->rect-shape selrect)
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} (shape->rect-shape shape)]
    (and (neg? (- sy1 ry1))
         (neg? (- sx1 rx1))
         (pos? (- sy2 ry2))
         (pos? (- sx2 rx2)))))

(defn overlaps?
  "Check if a shape overlaps with provided selection rect."
  [shape selrect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} (shape->rect-shape selrect)
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} (shape->rect-shape shape)]
    (and (< rx1 sx2)
         (> rx2 sx1)
         (< ry1 sy2)
         (> ry2 sy1))))

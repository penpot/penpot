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
    :curve (move-path shape dpoint)
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
  (let [segments (:segments shape)
        xf (comp
            (map #(update % :x + dx))
            (map #(update % :y + dy)))]
    (assoc shape :segments (into [] xf segments))))

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
    (size-rect shape)))

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
  (assoc-in shape [:segments index] point))

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
    :curve (setup-proportions-rect shape)
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
        (gmt/translate (+ (:x2 shape))
                       (+ (:y2 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x2 shape))
                       (- (:y2 shape))))

    :top-right
    (-> (gmt/matrix)
        (gmt/translate (+ (:x1 shape))
                       (+ (:y2 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x1 shape))
                       (- (:y2 shape))))

    :top
    (-> (gmt/matrix)
        (gmt/translate (+ (:x1 shape))
                       (+ (:y2 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x1 shape))
                       (- (:y2 shape))))

    :bottom-left
    (-> (gmt/matrix)
        (gmt/translate (+ (:x2 shape))
                       (+ (:y1 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x2 shape))
                       (- (:y1 shape))))

    :bottom-right
    (-> (gmt/matrix)
        (gmt/translate (+ (:x1 shape))
                       (+ (:y1 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x1 shape))
                       (- (:y1 shape))))

    :bottom
    (-> (gmt/matrix)
        (gmt/translate (+ (:x1 shape))
                       (+ (:y1 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x1 shape))
                       (- (:y1 shape))))

    :right
    (-> (gmt/matrix)
        (gmt/translate (+ (:x1 shape))
                       (+ (:y1 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x1 shape))
                       (- (:y1 shape))))

    :left
    (-> (gmt/matrix)
        (gmt/translate (+ (:x2 shape))
                       (+ (:y1 shape)))
        (gmt/scale scalex scaley)
        (gmt/translate (- (:x2 shape))
                       (- (:y1 shape))))))


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
    (let [width (- x (:x1 shape))
          height (- y (:y1 shape))
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
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :x1 x1
         :y1 y1
         :x2 x2
         :y2 y2))

(defn- setup-circle
  "A specialized function for setup circle shapes."
  [shape {:keys [x1 y1 x2 y2]}]
  (assoc shape
         :cx x1
         :cy y1
         :rx (mth/abs (- x2 x1))
         :ry (mth/abs (- y2 y1))))

(defn- setup-image
  [{:keys [metadata] :as shape} {:keys [x1 y1 x2 y2] :as props}]
  (let [{:keys [width height]} metadata]
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
     :curve (path->rect-shape state shape)
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

(defn- path->rect-shape
  [state {:keys [segments] :as shape}]
  (let [minx (apply min (map :x segments))
        miny (apply min (map :y segments))
        maxx (apply max (map :x segments))
        maxy (apply max (map :y segments))]
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
    :curve (transform-path shape xfmt)
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
  [{:keys [segments] :as shape} xfmt]
  (let [segments (mapv #(gpt/transform % xfmt) segments)]
    (assoc shape :segments segments)))

;; --- Outer Rect

(declare selection-rect-generic)

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
   (let [modifier (:modifier-mtx shape)]
     (-> (shape->rect-shape shape)
         (assoc :type :rect :id (:id shape))
         (transform (or modifier (gmt/matrix)))
         (rotate-shape)
         (size)))))

;; --- Helpers

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

(defn overlaps?
  "Check if a shape overlaps with provided selection rect."
  [shape selrect]
  (let [{sx1 :x1 sx2 :x2 sy1 :y1 sy2 :y2} selrect
        {rx1 :x1 rx2 :x2 ry1 :y1 ry2 :y2} shape]
    (and (< rx1 sx2)
         (> rx2 sx1)
         (< ry1 sy2)
         (> ry2 sy1))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.geom
  (:require [uxbox.util.geom.matrix :as gmt]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.math :as mth]
            [uxbox.store :as st]))

;; --- Relative Movement

;; TODO: revisit, maybe dead code
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
  (let [dx (if x (- (:x1 shape) x) 0)
        dy (if y (- (:y1 shape) y) 0)]
    (move shape (gpt/point dx dy))))

(defn- absolute-move-circle
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- (:cx shape) x) 0)
        dy (if y (- (:cy shape) y) 0)]
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

;; --- Vertex Access

(declare get-rect-vertext-point)
(declare get-circle-vertext-point)

(defn get-vertex-point
  [shape id]
  (case (:type shape)
    :icon (get-rect-vertext-point shape id)
    :image (get-rect-vertext-point shape id)
    :rect (get-rect-vertext-point shape id)
    :circle (get-circle-vertext-point shape id)
    :text (get-rect-vertext-point shape id)))

(defn- get-rect-vertext-point
  [{:keys [x1 y1 x2 y2]} id]
  (case id
    :top-left (gpt/point x1 y1)
    :top-right (gpt/point x2 y1)
    :bottom-left (gpt/point x1 y2)
    :bottom-right (gpt/point x2 y2)
    :top (gpt/point (/ (+ x1 x2) 2) y1)
    :right (gpt/point x2 (/ (+ y1 y2) 2))
    :left (gpt/point x1 (/ (+ y1 y2) 2))
    :bottom (gpt/point (/ (+ x1 x2) 2)
                       (/ (+ y1 y2) 2))))

(defn- get-circle-vertext-point
  [{:keys [rx ry]} id]
  (gpt/point rx ry))

;; --- Vertex Movement (Relative)

(declare move-rect-vertex)
(declare move-circle-vertex)

(defn move-vertex
  "Resize the shape moving one of its vertex using
  relative delta."
  [shape vid dpoint]
  (case (:type shape)
    :rect (move-rect-vertex shape vid dpoint)
    :text (move-rect-vertex shape vid dpoint)
    :icon (move-rect-vertex shape vid dpoint)
    :image (move-rect-vertex shape vid dpoint)
    :path (move-rect-vertex shape vid dpoint)
    :circle (move-circle-vertex shape vid dpoint)))

(defn- move-rect-vertex
  "A specialized function for vertex movement
  for rect-like shapes."
  [shape vid {dx :x dy :y lock? :lock}]
  (letfn [(handle-positioning [{:keys [x1 x2 y1 y2] :as shape}]
            (case vid
              :top-left (assoc shape
                               :x1 (min x2 (+ x1 dx))
                               :y1 (min y2 (+ y1 dy)))
              :top-right (assoc shape
                                :x2 (max x1 (+ x2 dx))
                                :y1 (min y2 (+ y1 dy)))
              :bottom-left (assoc shape
                                  :x1 (min x2 (+ x1 dx))
                                  :y2 (max y1 (+ y2 dy)))
              :bottom-right (assoc shape
                                   :x2 (max x1 (+ x2 dx))
                                   :y2 (max y1 (+ y2 dy)))
              :top (assoc shape :y1 (min y2 (+ y1 dy)))
              :right (assoc shape :x2 (max x1 (+ x2 dx)))
              :bottom (assoc shape :y2 (max y1 (+ y2 dy)))
              :left (assoc shape :x1 (min x2 (+ x1 dx)))))

          (handle-proportion [{:keys [y1 proportion proportion-lock] :as shape}]
            (let [{:keys [width height]} (size shape)]
              (if (or lock? proportion-lock)
                (assoc shape :y2 (+ y1 (/ width proportion)))
                shape)))]
    (-> shape
        (handle-positioning)
        (handle-proportion))))

(defn- move-circle-vertex
  "A specialized function for vertex movement
  for circle shapes."
  [shape vid {dx :x dy :y lock? :lock}]
  (letfn [(handle-positioning [shape]
            (case vid
              :top-left (assoc shape
                               :rx (max 0 (- (:rx shape) dx))
                               :ry (max 0 (- (:ry shape) dy)))
              :top-right (assoc shape
                                :rx (max 0 (+ (:rx shape) dx))
                                :ry (max 0 (- (:ry shape) dy)))
              :bottom-left (assoc shape
                                  :rx (max 0 (- (:rx shape) dx))
                                  :ry (max 0 (+ (:ry shape) dy)))
              :bottom-right (assoc shape
                                   :rx (max 0 (+ (:rx shape) dx))
                                   :ry (max 0 (+ (:ry shape) dy)))
              :top (assoc shape :ry (max 0 (- (:ry shape) dy)))
              :right (assoc shape :rx (max 0 (+ (:rx shape) dx)))
              :bottom (assoc shape :ry (max 0 (+ (:ry shape) dy)))
              :left (assoc shape :rx (max 0 (+ (:rx shape) dx)))))
          (handle-proportion [{:keys [rx proportion proportion-lock] :as shape}]
            (let [{:keys [width height]} (size shape)]
              (if (or lock? proportion-lock)
                (assoc shape :ry (/ rx proportion))
                shape)))]
    (-> shape
        (handle-positioning)
        (handle-proportion))))

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
  [shape {:keys [x y lock] :as pos}]
  (if lock
    (-> (assoc shape :x2 x :y2 y)
        (equalize-sides)
        (normalize-shape))
    (normalize-shape (assoc shape :x2 x :y2 y))))

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
  ([state shapes]
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
      ::shapes shapes})))

(defn- group->rect-shape
  [state {:keys [items] :as group}]
  (let [shapes (map #(get-in state [:shapes %]) items)]
    (shapes->rect-shape state shapes)))

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
  ([shape xfmt] (transform @st/state shape xfmt))
  ([state {:keys [type] :as shape} xfmt]
   (case type
     :rect (transform-rect shape xfmt)
     :icon (transform-rect shape xfmt)
     :image (transform-rect shape xfmt)
     :path (transform-path shape xfmt)
     :circle (transform-circle shape xfmt))))

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
  (let [points (map #(gpt/transform % xfmt) points)]
    (assoc shape :points points)))

;; --- Outer Rect

(declare outer-rect-generic)
(declare outer-rect-circle)
(declare outer-rect-path)
(declare outer-rect-group)
(declare apply-rotation-transformation)
(declare apply-parent-deltas)

(defn outer-rect
  ([shape]
   (outer-rect @st/state shape))
  ([state shape]
   (let [shape (case (:type shape)
                 :path (outer-rect-path state shape)
                 :circle (outer-rect-circle state shape)
                 :group (outer-rect-group state shape)
                 (outer-rect-generic state shape))]
     (if (:group shape)
       (let [group (get-in state [:shapes (:group shape)])]
         (apply-parent-deltas state shape (:group group)))
       shape))))

(defn- apply-parent-deltas
  [state {:keys [x y] :as shape} id]
  (if-let [group (get-in state [:shapes id])]
    (let [props {:x (+ x (:dx group 0))
                 :y (+ y (:dy group 0))}]
      (apply-parent-deltas state (merge shape props) (:group group)))
    shape))

(defn- outer-rect-generic
  [state {:keys [x1 y1 x2 y2 group] :as shape}]
  (let [group (get-in state [:shapes group])
        props {:x (+ x1 (:dx group 0))
               :y (+ y1 (:dy group 0))
               :width (- x2 x1)
               :height (- y2 y1)}]
    (-> (merge shape props)
        (apply-rotation-transformation))))

(defn- outer-rect-circle
  [state {:keys [cx cy rx ry group] :as shape}]
  (let [group (get-in state [:shapes group])
        props {:x (+ (- cx rx) (:dx group 0))
               :y (+ (- cy ry) (:dy group 0))
               :width (* rx 2)
               :height (* ry 2)}]
    (-> (merge shape props)
        (apply-rotation-transformation))))

(defn- outer-rect-path
  [state {:keys [points group] :as shape}]
  (let [group (get-in state [:shapes group])
        minx (apply min (map :x points))
        miny (apply min (map :y points))
        maxx (apply max (map :x points))
        maxy (apply max (map :y points))

        props {:x (+ minx (:dx group 0))
               :y (+ miny (:dy group 0))
               :width (- maxx minx)
               :height (- maxy miny)}]
    (-> (merge shape props)
        (apply-rotation-transformation))))


(defn- outer-rect-group
  [state {:keys [id group rotation dx dy] :as shape}]
  (let [shapes (->> (:items shape)
                    (map #(get-in state [:shapes %]))
                    (map #(outer-rect state %)))
        x (apply min (map :x shapes))
        y (apply min (map :y shapes))
        x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
        y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
        width (- x' x)
        height (- y' y)]
    (-> (merge shape {:width width :height height :x x :y y})
        (apply-rotation-transformation))))

(declare apply-rotation)

(defn- apply-rotation-transformation
  [{:keys [x y width height rotation] :as shape}]
  (let [center-x (+ x (/ width 2))
        center-y (+ y (/ height 2))

        angle (mth/radians (or rotation 0))
        x1 (- x center-x)
        y1 (- y center-y)

        x2 (- (+ x width) center-x)
        y2 (- y center-y)

        [rx1 ry1] (apply-rotation [x1 y1] rotation)
        [rx2 ry2] (apply-rotation [x2 y2] rotation)

        [d1 d2] (cond
                  (and (>= rotation 0)
                       (< rotation 90))
                  [(mth/abs ry1)
                   (mth/abs rx2)]

                  (and (>= rotation 90)
                       (< rotation 180))
                  [(mth/abs ry2)
                   (mth/abs rx1)]

                  (and (>= rotation 180)
                       (< rotation 270))
                  [(mth/abs ry1)
                   (mth/abs rx2)]

                  (and (>= rotation 270)
                       (<= rotation 360))
                  [(mth/abs ry2)
                   (mth/abs rx1)])
        final-x (- center-x d2)
        final-y (- center-y d1)
        final-width (* d2 2)
        final-height (* d1 2)]
    (merge shape
           {:x final-x
            :y final-y
            :width final-width
            :height final-height})))

;; --- Outer Rect Coll

(defn outer-rect-coll
  [shapes]
  {:pre [(seq shapes)]}
  (let [shapes (map outer-rect shapes)
        x (apply min (map :x shapes))
        y (apply min (map :y shapes))
        x' (apply max (map (fn [{:keys [x width]}] (+ x width)) shapes))
        y' (apply max (map (fn [{:keys [y height]}] (+ y height)) shapes))
        width (- x' x)
        height (- y' y)]
    {:width width
     :height height
     :x x
     :y y}))

;; --- Helpers

(defn apply-rotation
  [[x y :as v] rotation]
  (let [angle (mth/radians rotation)
        rx (- (* x (mth/cos angle))
              (* y (mth/sin angle)))
        ry (+ (* x (mth/sin angle))
              (* y (mth/cos angle)))]
    (let [r [(mth/precision rx 6)
             (mth/precision ry 6)]]
      r)))

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
  (let [sx1 (:x selrect)
        sx2 (+ sx1 (:width selrect))
        sy1 (:y selrect)
        sy2 (+ sy1 (:height selrect))
        rx1 (:x shape)
        rx2 (+ rx1 (:width shape))
        ry1 (:y shape)
        ry2 (+ ry1 (:height shape))]
    (and (neg? (- (:y selrect) (:y shape)))
         (neg? (- (:x selrect) (:x shape)))
         (pos? (- (+ (:y selrect)
                     (:height selrect))
                  (+ (:y shape)
                     (:height shape))))
         (pos? (- (+ (:x selrect)
                     (:width selrect))
                  (+ (:x shape)
                     (:width shape)))))))

;; TODO: maybe remove, seems it not used anymore.

(defn translate-coords
  "Given a shape and initial coords, transform
  it mapping its coords to new provided initial coords."
  ([shape x y]
   (translate-coords shape x y -))
  ([shape x y op]
   (let [x' (:x shape)
         y' (:y shape)]
     (assoc shape :x (op x' x) :y (op y' y)))))

;; This function will be deleted when selrect is implemented properly

(defn parent-satisfies?
  "Resolve the first parent that satisfies a condition."
  [{:keys [group] :as shape} pred]
  (let [shapes-by-id (:shapes @st/state)]
    (if group
      (loop [parent (get shapes-by-id group)]
        (cond
          (pred parent) true
          (:group parent) (recur (get shapes-by-id (:group parent)))
          :else false))
      false)))

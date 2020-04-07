;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.geom
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.spec :as us]
   [uxbox.util.geom.matrix :as gmt]
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
    :frame (move-rect shape dpoint)
    :text (move-rect shape dpoint)
    :curve (move-path shape dpoint)
    :path (move-path shape dpoint)
    :circle (move-circle shape dpoint)
    :group (move-rect shape dpoint)
    shape))

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
    :frame (absolute-move-rect shape position)
    :image (absolute-move-rect shape position)
    :rect (absolute-move-rect shape position)
    :group (absolute-move-rect shape position)
    :circle (absolute-move-circle shape position)
    shape))

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

(defn resize-rect
  [shape attr value]
  (us/assert map? shape)
  (us/assert #{:width :height} attr)
  (us/assert number? value)

  (let [{:keys [proportion proportion-lock]} shape]
    (if-not proportion-lock
      (assoc shape attr value)
      (if (= attr :width)
        (-> shape
            (assoc :width value)
            (assoc :height (/ value proportion)))
        (-> shape
            (assoc :height value)
            (assoc :width (* value proportion)))))))

(defn resize-circle
  [shape attr value]
  (us/assert map? shape)
  (us/assert #{:rx :ry} attr)
  (us/assert number? value)
  (let [{:keys [proportion proportion-lock]} shape]
    (if-not proportion-lock
      (assoc shape attr value)
      (if (= attr :rx)
        (-> shape
            (assoc :rx value)
            (assoc :ry (/ value proportion)))
        (-> shape
            (assoc :ry value)
            (assoc :rx (* value proportion)))))))

;; --- Resize

(defn calculate-scale-ratio
  "Calculate the scale factor from one shape to an other.

  The shapes should be of rect-like type because width
  and height are used for calculate the ratio."
  [origin final]
  [(/ (:width final) (:width origin))
   (/ (:height final) (:height origin))])

(defn- get-vid-coords [vid]
  (case vid
    :top-left     [:x2 :y2]
    :top-right    [:x1 :y2]
    :top          [:x1 :y2]
    :bottom-left  [:x2 :y1]
    :bottom-right [:x  :y ]
    :bottom       [:x1 :y1]
    :right        [:x1 :y1]
    :left         [:x2 :y1]))

(defn generate-resize-matrix
  "Generate the resize transformation matrix given a corner-id, shape
  and the scale factor vector. The shape should be of rect-like type.

  Mainly used by drawarea and shape resize on workspace."
  [vid shape [scalex scaley]]
  (let [[cor-x cor-y] (get-vid-coords vid)
        {:keys [x y width height rotation]} shape
        cx (+ x (/ width 2))
        cy (+ y (/ height 2))
        center (gpt/point cx cy)
        ]
    (-> (gmt/matrix)
        ;; Correction first otherwise the scale is going to deform the correction
        (gmt/translate (gmt/correct-rotation
                        vid width height scalex scaley rotation))
        (gmt/scale (gpt/point scalex scaley)
                   (gpt/point (cor-x shape)
                              (cor-y shape)))
        )))

(defn resize-shape
  "Apply a resize transformation to a rect-like shape. The shape
  should have the `width` and `height` attrs, because these attrs
  are used for the resize transformation.

  Mainly used in drawarea and interactive resize on workspace
  with the main objective that on the end of resize have a way
  a calculte the resize ratio with `calculate-scale-ratio`."
  [vid shape {:keys [x y] :as point} lock?]

  (let [[cor-x cor-y] (get-vid-coords vid)]
    (let [final-x (if (#{:top :bottom} vid) (:x2 shape) x)
          final-y (if (#{:right :left} vid) (:y2 shape) y)
          width (Math/abs (- final-x (cor-x shape)))
          height (Math/abs (- final-y (cor-y shape)))
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

;; --- Transform Shape

(declare transform-rect)
(declare transform-circle)
(declare transform-path)

(defn transform
  "Apply the matrix transformation to shape."
  [{:keys [type] :as shape} xfmt]
  (if (gmt/matrix? xfmt)
   (case type
     :frame (transform-rect shape xfmt)
     :group (transform-rect shape xfmt)
     :rect (transform-rect shape xfmt)
     :icon (transform-rect shape xfmt)
     :text (transform-rect shape xfmt)
     :image (transform-rect shape xfmt)
     :path (transform-path shape xfmt)
     :curve (transform-path shape xfmt)
     :circle (transform-circle shape xfmt)
     shape)
   shape))

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
  [{:keys [resize-modifier displacement-modifier] :as shape}]
  (cond-> shape
    (gmt/matrix? resize-modifier)
    (transform resize-modifier)

    (gmt/matrix? displacement-modifier)
    (transform displacement-modifier)))

;; NOTE: we need apply `shape->rect-shape` 3 times because we need to
;; update the x1 x2 y1 y2 attributes on each step; this is because
;; some transform functions still uses that attributes. WE NEED TO
;; REFACTOR this, and remove any usage of the old xN yN attributes.

(def ^:private xf-resolve-shape
  (comp (map shape->rect-shape)
        (map resolve-modifier)
        (map shape->rect-shape)
        (map resolve-rotation)
        (map shape->rect-shape)))

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (let [shapes (into [] xf-resolve-shape shapes)
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

(defn translate-to-frame
  [shape {:keys [x y] :as frame}]
  (move shape (gpt/point (- x) (- y))))

(defn translate-from-frame
  [shape {:keys [x y] :as frame}]
  (move shape (gpt/point (+ x) (+ y))))


;; --- Alignment

(s/def ::axis #{:hleft :hcenter :hright :vtop :vcenter :vbottom})

(declare calc-align-pos)

(defn align-to-rect
  "Move the shape so that it is aligned with the given rectangle
  in the given axis. Take account the form of the shape and the
  possible rotation. What is aligned is the rectangle that wraps
  the shape with the given rectangle."
  [shape rect axis]
  (let [wrapper-rect (selection-rect [shape])
        align-pos (calc-align-pos wrapper-rect rect axis)
        delta {:x (- (:x align-pos) (:x wrapper-rect))
               :y (- (:y align-pos) (:y wrapper-rect))}]
    (move shape delta)))

(defn calc-align-pos
  [wrapper-rect rect axis]
  (case axis
    :hleft (let [left (:x rect)]
             {:x left
              :y (:y wrapper-rect)})

    :hcenter (let [center (+ (:x rect) (/ (:width rect) 2))]
               {:x (- center (/ (:width wrapper-rect) 2))
                :y (:y wrapper-rect)})

    :hright (let [right (+ (:x rect) (:width rect))]
              {:x (- right (:width wrapper-rect))
               :y (:y wrapper-rect)})

    :vtop (let [top (:y rect)]
             {:x (:x wrapper-rect)
              :y top})

    :vcenter (let [center (+ (:y rect) (/ (:height rect) 2))]
               {:x (:x wrapper-rect)
                :y (- center (/ (:height wrapper-rect) 2))})

    :vbottom (let [bottom (+ (:y rect) (:height rect))]
               {:x (:x wrapper-rect)
                :y (- bottom (:height wrapper-rect))})))

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

(defn transform-shape
  ([shape] (transform-shape nil shape))
  ([frame shape]
   (let [ds-modifier (:displacement-modifier shape)
         rz-modifier (:resize-modifier shape)
         frame-ds-modifier (:displacement-modifier frame)]
     (cond-> shape
       (gmt/matrix? rz-modifier) (transform rz-modifier)
       frame (move (gpt/point (- (:x frame)) (- (:y frame))))
       (gmt/matrix? frame-ds-modifier) (transform frame-ds-modifier)
       (gmt/matrix? ds-modifier) (transform ds-modifier)))))

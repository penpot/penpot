;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.geom.shapes
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.pages :as cp]
   [uxbox.common.spec :as us]
   [uxbox.util.geom.matrix :as gmt]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.math :as mth]
   [uxbox.util.data :as d]
   [uxbox.util.debug :as debug]))

;; --- Relative Movement

(declare move-rect)
(declare move-path)

(defn- _chk
  "Function that checks if a number is nil or nan. Will return 0 when not
  valid and the number otherwise."
  [v]
  (if (or (not v) (mth/nan? v)) 0 v))

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape dpoint]
  (case (:type shape)
    :curve (move-path shape dpoint)
    :path (move-path shape dpoint)
    (move-rect shape dpoint)))

(defn- move-rect
  "A specialized function for relative movement
  for rect-like shapes."
  [shape {dx :x dy :y}]
  (assoc shape
         :x (mth/round (+ (_chk (:x shape)) (_chk dx)))
         :y (mth/round (+ (_chk (:y shape)) (_chk dy)))))

(defn- move-path
  "A specialized function for relative movement
  for path shapes."
  [shape {dx :x dy :y}]
  (let [segments (:segments shape)
        xf (comp
            (map #(update % :x + dx))
            (map #(update % :y + dy)))]
    (assoc shape :segments (into [] xf segments))))

(defn recursive-move
  "Move the shape and all its recursive children."
  [shape dpoint objects]
  (let [children-ids (cp/get-children (:id shape) objects)
        children (map #(get objects %) children-ids)]
    (map #(move % dpoint) (cons shape children))))

;; --- Absolute Movement

(declare absolute-move-rect)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape position]
  (case (:type shape)
    :path shape
    :curve shape
    (absolute-move-rect shape position)))

(defn- absolute-move-rect
  "A specialized function for absolute moviment
  for rect-like shapes."
  [shape {:keys [x y] :as pos}]
  (let [dx (if x (- (_chk x) (_chk (:x shape))) 0)
        dy (if y (- (_chk y) (_chk (:y shape))) 0)]
    (move shape (gpt/point dx dy))))

;; --- Rotation

;; TODO: maybe we can consider apply the rotation
;;       directly to the shape coordinates?
;; FIXME: deprecated, should be removed

(defn rotate
  "Apply the rotation to the shape."
  [shape rotation]
  (assoc shape :rotation rotation))

;; --- Corner points

(defn corner-points [points]
  (let [minx (apply min (map :x points))
        miny (apply min (map :y points))
        maxx (apply max (map :x points))
        maxy (apply max (map :y points))]
    {:x1 minx :y1 miny :x2 maxx :y2 maxy}))

;; --- Size

(declare size-path)

(defn size
  "Calculate the size of the shape."
  [shape]
  (case (:type shape)
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

;; --- Center

(declare center-rect)
(declare center-path)

(defn center
  "Calculate the center of the shape."
  [shape]
  (case (:type shape)
    :curve (center-path shape)
    :path (center-path shape)
    (center-rect shape)))

(defn- center-rect
  [{:keys [x y width height] :as shape}]
  (gpt/point (+ x (/ width 2)) (+ y (/ height 2))))

(defn- center-path
  [{:keys [segments] :as shape}]
  (let [minx (apply min (map :x segments))
        miny (apply min (map :y segments))
        maxx (apply max (map :x segments))
        maxy (apply max (map :y segments))]
    (gpt/point (/ (+ minx maxx) 2) (/ (+ miny maxy) 2))))

(defn center->rect
  "Creates a rect given a center and a width and height"
  [center width height]
  {:x (- (:x center) (/ width 2))
   :y (- (:y center) (/ height 2))
   :width width
   :height height})

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

(defn resize-shape
  "Apply a resize transformation to a rect-like shape. The shape
  should have the `width` and `height` attrs, because these attrs
  are used for the resize transformation.

  Mainly used in drawarea and interactive resize on workspace
  with the main objective that on the end of resize have a way
  a calculte the resize ratio with `calculate-scale-ratio`."
  [vid shape initial target lock?]

  (let [{:keys [x y]} (gpt/subtract target initial)
        [cor-x cor-y] (get-vid-coords vid)]
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
  (assoc shape
         :x x
         :y y
         :width width
         :height height))

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

(declare path->rect-shape)
(declare group->rect-shape)
(declare rect->rect-shape)

(defn shape->rect-shape
  "Coerce shape to rect like shape."
  [{:keys [type] :as shape}]
  (case type
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

(declare rect->path)

(defn shape->path
  [shape]
  (case (:type shape)
    :path shape
    :curve shape
    (rect->path shape)))

(defn rect->path
  [{:keys [x y width height] :as shape}]

  (let [points [(gpt/point x y)
                (gpt/point (+ x width) y)
                (gpt/point (+ x width) (+ y height))
                (gpt/point x (+ y height))
                (gpt/point x y)]]
    (-> shape
        (assoc :type :path)
        (assoc :segments points))))

;; --- SHAPE -> RECT

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
           :type :rect
           :x1 minx
           :y1 miny
           :x2 maxx
           :y2 maxy
           :x minx
           :y miny
           :width (- maxx minx)
           :height (- maxy miny))))

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
(declare transform-path)

(defn transform
  "Apply the matrix transformation to shape."
  [{:keys [type] :as shape} xfmt]
  (if (gmt/matrix? xfmt)
   (case type
     :path (transform-path shape xfmt)
     :curve (transform-path shape xfmt)
     (transform-rect shape xfmt))
   shape))

(defn center-transform [shape matrix]
  (let [shape-center (center shape)]
    (-> shape
        (transform
         (-> (gmt/matrix)
             (gmt/translate shape-center)
             (gmt/multiply matrix)
             (gmt/translate (gpt/negate shape-center)))))))

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

(declare transform-apply-modifiers)

(defn selection-rect-shape
  [shape]
  (-> shape
      (transform-apply-modifiers)
      (shape->rect-shape)))

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (let [xf-resolve-shape (map selection-rect-shape)
        shapes (into [] xf-resolve-shape shapes)
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
  (move shape (gpt/point x y)))

;; --- Alignment

(s/def ::align-axis #{:hleft :hcenter :hright :vtop :vcenter :vbottom})

(declare calc-align-pos)

(defn align-to-rect
  "Move the shape so that it is aligned with the given rectangle
  in the given axis. Take account the form of the shape and the
  possible rotation. What is aligned is the rectangle that wraps
  the shape with the given rectangle. If the shape is a group,
  move also all of its recursive children."
  [shape rect axis objects]
  (let [wrapper-rect (selection-rect [shape])
        align-pos (calc-align-pos wrapper-rect rect axis)
        delta {:x (- (:x align-pos) (:x wrapper-rect))
               :y (- (:y align-pos) (:y wrapper-rect))}]
    (recursive-move shape delta objects)))

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

;; --- Distribute

(s/def ::dist-axis #{:horizontal :vertical})

(defn distribute-space
  "Distribute equally the space between shapes in the given axis. If
  there is no space enough, it does nothing. It takes into account
  the form of the shape and the rotation, what is distributed is
  the wrapping recangles of the shapes. If any shape is a group,
  move also all of its recursive children."
  [shapes axis objects]
  (let [coord (if (= axis :horizontal) :x :y)
        other-coord (if (= axis :horizontal) :y :x)
        size (if (= axis :horizontal) :width :height)
        ; The rectangle that wraps the whole selection
        wrapper-rect (selection-rect shapes)
        ; Sort shapes by the center point in the given axis
        sorted-shapes (sort-by #(coord (center %)) shapes)
        ; Each shape wrapped in its own rectangle
        wrapped-shapes (map #(selection-rect [%]) sorted-shapes)
        ; The total space between shapes
        space (reduce - (size wrapper-rect) (map size wrapped-shapes))]

    (if (<= space 0)
      shapes
      (let [unit-space (/ space (- (count wrapped-shapes) 1))
            ; Calculate the distance we need to move each shape.
            ; The new position of each one is the position of the
            ; previous one plus its size plus the unit space.
            deltas (loop [shapes' wrapped-shapes
                          start-pos (coord wrapper-rect)
                          deltas []]

                     (let [first-shape (first shapes')
                           delta (- start-pos (coord first-shape))
                           new-pos (+ start-pos (size first-shape) unit-space)]

                       (if (= (count shapes') 1)
                         (conj deltas delta)
                         (recur (rest shapes')
                                new-pos
                                (conj deltas delta)))))]

        (mapcat #(recursive-move %1 {coord %2 other-coord 0} objects)
                sorted-shapes deltas)))))


;; --- Helpers

(defn apply-zoom
  [selrect zoom]
  (assoc selrect
         :x (/ (:x selrect) (:x zoom))
         :y (/ (:y selrect) (:y zoom))
         :width (/ (:width selrect) (:x zoom))
         :height (/ (:height selrect) (:y zoom))))

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


(defn calculate-rec-path-skew-angle
  [path-shape]
  (let [p1 (get-in path-shape [:segments 2])
        p2 (get-in path-shape [:segments 3])
        p3 (get-in path-shape [:segments 4])
        v1 (gpt/to-vec p1 p2)
        v2 (gpt/to-vec p2 p3)]
    (- 90 (gpt/angle-with-other v1 v2))))

(defn calculate-rec-path-height
  "Calculates the height of a paralelogram given by the path"
  [path-shape]

  (let [p1 (get-in path-shape [:segments 2])
        p2 (get-in path-shape [:segments 3])
        p3 (get-in path-shape [:segments 4])
        v1 (gpt/to-vec p1 p2)
        v2 (gpt/to-vec p2 p3)
        angle (gpt/angle-with-other v1 v2)]
    (* (gpt/length v2) (mth/sin (mth/radians angle)))))

(defn calculate-rec-path-rotation
  [path-shape1 path-shape2 resize-vector]

  (let [idx-1 0
        idx-2 (cond (and (neg? (:x resize-vector)) (pos? (:y resize-vector))) 1
                    (and (neg? (:x resize-vector)) (neg? (:y resize-vector))) 2
                    (and (pos? (:x resize-vector)) (neg? (:y resize-vector))) 3
                    :else 0)
        p1 (get-in path-shape1 [:segments idx-1])
        p2 (get-in path-shape2 [:segments idx-2])
        v1 (gpt/to-vec (center path-shape1) p1)
        v2 (gpt/to-vec (center path-shape2) p2)

        rot-angle (gpt/angle-with-other v1 v2)
        rot-sign (if (> (* (:y v1) (:x v2)) (* (:x v1) (:y v2))) -1 1)]
    (* rot-sign rot-angle)))

(defn transform-shape-point
  "Transform a point around the shape center"
  [point shape transform]
  (let [shape-center (center shape)]
    (gpt/transform
     point
     (-> (gmt/multiply
          (gmt/translate-matrix shape-center)
          transform
          (gmt/translate-matrix (gpt/negate shape-center)))))))

(defn transform-apply-modifiers
  [shape]
  (let [modifiers (:modifiers shape)
        ds-modifier (:displacement modifiers (gmt/matrix))
        resize (:resize-vector modifiers (gpt/point 1 1))
        origin (:resize-origin modifiers (gpt/point 0 0))
        resize-transform (:resize-transform modifiers (gmt/matrix))
        resize-transform-inverse (:resize-transform-inverse modifiers (gmt/matrix))
        rt-modif (:rotation modifiers 0)

        shape (-> shape
                  (transform ds-modifier))

        shape-center (center shape)]

    (-> (shape->path shape)
        (transform (-> (gmt/matrix)

                       ;; Applies the current resize transformation
                       (gmt/translate origin)
                       (gmt/multiply resize-transform)
                       (gmt/scale resize)
                       (gmt/multiply resize-transform-inverse)
                       (gmt/translate (gpt/negate origin))

                       ;; Applies the stacked transformations
                       (gmt/translate shape-center)
                       (gmt/multiply (gmt/rotate-matrix rt-modif))
                       (gmt/multiply (:transform shape (gmt/matrix)))
                       (gmt/translate (gpt/negate shape-center)))))))

(defn rect-path-dimensions [rect-path]
  (let [seg (:segments rect-path)
        [width height] (mapv (fn [[c1 c2]] (gpt/distance c1 c2)) (take 2 (d/zip seg (rest seg))))]
    {:width width
     :height height}))

(defn calculate-stretch [shape-path transform-inverse]
  (let [shape-center (center shape-path)
        shape-path-temp (transform
                         shape-path
                         (-> (gmt/matrix)
                             (gmt/translate shape-center)
                             (gmt/multiply transform-inverse)
                             (gmt/translate (gpt/negate shape-center))))

        shape-path-temp-rec (shape->rect-shape shape-path-temp)
        shape-path-temp-dim (rect-path-dimensions shape-path-temp)]
    (gpt/divide (gpt/point (:width shape-path-temp-rec) (:height shape-path-temp-rec))
                (gpt/point (:width shape-path-temp-dim) (:height shape-path-temp-dim)))))

(defn- fix-invalid-rect-values [rect-shape]
  (letfn [(check [num] (if (or (nil? num) (mth/nan? num)) 0 num))
          (to-positive [num] (if (< num 1) 1 num))]
    (-> rect-shape
        (update :x check)
        (update :y check)
        (update :width (comp to-positive check))
        (update :height (comp to-positive check)))))

(defn transform-rect-shape
  [shape]
  (let [;; Apply modifiers to the rect as a path so we have the end shape expected
        shape-path (transform-apply-modifiers shape)
        shape-center (center shape-path)
        resize-vector (get-in shape [:modifiers :resize-vector] (gpt/point 1 1))

        ;; Reverse the current transformation stack to get the base rectangle
        shape-path-temp (center-transform shape-path (:transform-inverse shape (gmt/matrix)))
        shape-path-temp-dim (rect-path-dimensions shape-path-temp)
        shape-path-temp-rec (shape->rect-shape shape-path-temp)

        ;; This rectangle is the new data for the current rectangle. We want to change our rectangle
        ;; to have this width, height, x, y
        rec (center->rect shape-center (:width shape-path-temp-dim) (:height shape-path-temp-dim))
        rec-path (rect->path rec)

        ;; The next matrix is a series of transformations we have to do to the previous rec so that
        ;; after applying them the end result is the `shape-path-temp`
        ;; This is compose of three transformations: skew, resize and rotation
        stretch-matrix (gmt/matrix)

        skew-angle (calculate-rec-path-skew-angle shape-path-temp)

        ;; When one of the axis is flipped we have to reverse the skew
        skew-angle (if (neg? (* (:x resize-vector) (:y resize-vector))) (- skew-angle) skew-angle )

        stretch-matrix (gmt/multiply stretch-matrix (gmt/skew-matrix skew-angle 0))

        h1 (calculate-rec-path-height shape-path-temp)
        h2 (calculate-rec-path-height (center-transform rec-path stretch-matrix))
        stretch-matrix (gmt/multiply stretch-matrix (gmt/scale-matrix (gpt/point 1 (/ h1 h2))))

        rotation-angle (calculate-rec-path-rotation (center-transform rec-path stretch-matrix) shape-path-temp resize-vector)

        stretch-matrix (gmt/multiply (gmt/rotate-matrix rotation-angle) stretch-matrix)

        ;; This is the inverse to be able to remove the transformation
        stretch-matrix-inverse (-> (gmt/matrix)
                                   (gmt/scale (gpt/point 1 (/ h2 h1)))
                                   (gmt/skew (- skew-angle) 0)
                                   (gmt/rotate (- rotation-angle)))

        new-shape (-> shape
                      (merge rec)
                      (update :x #(mth/precision % 2))
                      (update :y #(mth/precision % 2))
                      (fix-invalid-rect-values)
                      (update :transform #(gmt/multiply (or % (gmt/matrix)) stretch-matrix))
                      (update :transform-inverse #(gmt/multiply stretch-matrix-inverse (or % (gmt/matrix)))))]

    new-shape))

(defn transform-path-shape
  [shape]
  (transform-apply-modifiers shape)
  ;; TODO: Addapt for paths is not working
  #_(let [shape-path (transform-apply-modifiers shape)
          shape-path-center (center shape-path)

          shape-transform-inverse' (-> (gmt/matrix)
                                       (gmt/translate shape-path-center)
                                       (gmt/multiply (:transform-inverse shape (gmt/matrix)))
                                       (gmt/multiply (gmt/rotate-matrix (- (:rotation-modifier shape 0))))
                                       (gmt/translate (gpt/negate shape-path-center)))]
      (-> shape-path
          (transform shape-transform-inverse')
          (add-rotate-transform (:rotation-modifier shape 0)))))

(defn transform-shape
  "Transform the shape properties given the modifiers"
  ([shape] (transform-shape nil shape))
  ([frame shape]
   (let [new-shape (case (:type shape)
                     :path (transform-path-shape shape)
                     :curve (transform-path-shape shape)
                     (transform-rect-shape shape))]
     (-> new-shape
         (translate-to-frame frame)
         (update :rotation #(mod (+ % (get-in shape [:modifiers :rotation] 0)) 360))
         (dissoc :modifiers)))))


(defn transform-matrix
  "Returns a transformation matrix without changing the shape properties.
  The result should be used in a `transform` attribute in svg"
  ([{:keys [x y] :as shape}]
   (let [shape-center (center shape)]
     (-> (gmt/matrix)
         (gmt/translate shape-center)
         (gmt/multiply (:transform shape (gmt/matrix)))
         (gmt/translate (gpt/negate shape-center))))))

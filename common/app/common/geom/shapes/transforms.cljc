;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.transforms
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.data :as d]))

;; --- Relative Movement

(defn move-selrect [selrect {dx :x dy :y}]
  (-> selrect
      (d/update-when :x + dx)
      (d/update-when :y + dy)
      (d/update-when :x1 + dx)
      (d/update-when :y1 + dy)
      (d/update-when :x2 + dx)
      (d/update-when :y2 + dy)))

(defn move-points [points move-vec]
  (->> points
       (mapv #(gpt/add % move-vec))))

(defn move
  "Move the shape relativelly to its current
  position applying the provided delta."
  [shape {dx :x dy :y}]
  (let [dx (d/check-num dx)
        dy (d/check-num dy)
        move-vec (gpt/point dx dy)]

    (-> shape
        (update :selrect move-selrect move-vec)
        (update :points move-points move-vec)
        (d/update-when :x + dx)
        (d/update-when :y + dy)
        (cond-> (= :path (:type shape))
          (update :content gpa/move-content move-vec)))))

;; --- Absolute Movement

(declare absolute-move-rect)

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape {:keys [x y]}]
  (let [dx (- (d/check-num x) (-> shape :selrect :x))
        dy (- (d/check-num y) (-> shape :selrect :y))]
    (move shape (gpt/point dx dy))))


(defn- modif-rotation [shape]
  (let [cur-rotation (d/check-num (:rotation shape))
        delta-angle  (d/check-num (get-in shape [:modifiers :rotation]))]
    (mod (+ cur-rotation delta-angle) 360)))

(defn transform-matrix
  "Returns a transformation matrix without changing the shape properties.
  The result should be used in a `transform` attribute in svg"
  ([shape] (transform-matrix shape nil))
  ([shape params] (transform-matrix shape params (or (gco/center-shape shape)
                                                     (gpt/point 0 0))))
  ([{:keys [flip-x flip-y] :as shape} {:keys [no-flip]} shape-center]
   (-> (gmt/matrix)
       (gmt/translate shape-center)

       (gmt/multiply (:transform shape (gmt/matrix)))
       (cond->
           (and (not no-flip) flip-x) (gmt/scale (gpt/point -1 1))
           (and (not no-flip) flip-y) (gmt/scale (gpt/point 1 -1)))
       (gmt/translate (gpt/negate shape-center)))))

(defn inverse-transform-matrix
  ([shape]
   (let [shape-center (or (gco/center-shape shape)
                          (gpt/point 0 0))]
     (inverse-transform-matrix shape shape-center)))
  ([{:keys [flip-x flip-y] :as shape} center]
   (let []
     (-> (gmt/matrix)
         (gmt/translate center)
         (cond->
             flip-x (gmt/scale (gpt/point -1 1))
             flip-y (gmt/scale (gpt/point 1 -1)))
         (gmt/multiply (:transform-inverse shape (gmt/matrix)))
         (gmt/translate (gpt/negate center))))))

(defn transform-point-center
  "Transform a point around the shape center"
  [point center matrix]
  (gpt/transform
   point
   (gmt/multiply (gmt/translate-matrix center)
                 matrix
                 (gmt/translate-matrix (gpt/negate center)))))

(defn transform-points
  ([points matrix]
   (transform-points points nil matrix))
  ([points center matrix]
   (let [prev (if center (gmt/translate-matrix center) (gmt/matrix))
         post (if center (gmt/translate-matrix (gpt/negate center)) (gmt/matrix))

         tr-point (fn [point]
                    (gpt/transform point (gmt/multiply prev matrix post)))]
     (mapv tr-point points))))

(defn transform-rect
  "Transform a rectangles and changes its attributes"
  [rect matrix]

  (let [points (-> (gpr/rect->points rect)
                   (transform-points matrix))]
    (gpr/points->rect points)))

(defn normalize-scale
  "We normalize the scale so it's not too close to 0"
  [scale]
  (cond
    (and (<  scale 0) (> scale -0.01)) -0.01
    (and (>= scale 0) (< scale  0.01))  0.01
    :else scale))

(defn modifiers->transform
  [center modifiers]
  (let [ds-modifier (:displacement modifiers (gmt/matrix))
        {res-x :x res-y :y} (:resize-vector modifiers (gpt/point 1 1))

        ;; Normalize x/y vector coordinates because scale by 0 is infinite
        res-x (normalize-scale res-x)
        res-y (normalize-scale res-y)
        resize (gpt/point res-x res-y)

        origin (:resize-origin modifiers (gpt/point 0 0))

        resize-transform (:resize-transform modifiers (gmt/matrix))
        resize-transform-inverse (:resize-transform-inverse modifiers (gmt/matrix))
        rt-modif (or (:rotation modifiers) 0)

        center (gpt/transform center ds-modifier)

        transform (-> (gmt/matrix)

                      ;; Applies the current resize transformation
                      (gmt/translate origin)
                      (gmt/multiply resize-transform)
                      (gmt/scale resize)
                      (gmt/multiply resize-transform-inverse)
                      (gmt/translate (gpt/negate origin))

                      ;; Applies the stacked transformations
                      (gmt/translate center)
                      (gmt/multiply (gmt/rotate-matrix rt-modif))
                      (gmt/translate (gpt/negate center))

                      ;; Displacement
                      (gmt/multiply ds-modifier))]
    transform))

(defn- calculate-skew-angle
  "Calculates the skew angle of the paralelogram given by the points"
  [[p1 _ p3 p4]]
  (let [v1 (gpt/to-vec p3 p4)
        v2 (gpt/to-vec p4 p1)]
    ;; If one of the vectors is zero it's a rectangle with 0 height or width
    ;; We don't skew these
    (if (or (gpt/almost-zero? v1)
            (gpt/almost-zero? v2))
      0
      (- 90 (gpt/angle-with-other v1 v2)))))

(defn- calculate-height
  "Calculates the height of a paralelogram given by the points"
  [[p1 _ _ p4]]
  (-> (gpt/to-vec p4 p1)
      (gpt/length)))

(defn- calculate-width
  "Calculates the width of a paralelogram given by the points"
  [[p1 p2 _ _]]
  (-> (gpt/to-vec p1 p2)
      (gpt/length)))

(defn- calculate-rotation
  "Calculates the rotation between two shapes given the resize vector direction"
  [center points-shape1 points-shape2 flip-x flip-y]

  (let [idx-1 0
        idx-2 (cond (and flip-x       (not flip-y)) 1
                    (and flip-x       flip-y) 2
                    (and (not flip-x) flip-y) 3
                    :else 0)
        p1 (nth points-shape1 idx-1)
        p2 (nth points-shape2 idx-2)
        v1 (gpt/to-vec center p1)
        v2 (gpt/to-vec center p2)

        rot-angle (gpt/angle-with-other v1 v2)
        rot-sign (gpt/angle-sign v1 v2)]
    (* rot-sign rot-angle)))

(defn- calculate-dimensions
  [[p1 p2 p3 _]]
  (let [width  (gpt/distance p1 p2)
        height (gpt/distance p2 p3)]
    {:width width :height height}))

(defn calculate-adjust-matrix
  "Calculates a matrix that is a series of transformations we have to do to the transformed rectangle so that
  after applying them the end result is the `shape-pathn-temp`.
  This is compose of three transformations: skew, resize and rotation"
  ([points-temp points-rec] (calculate-adjust-matrix points-temp points-rec false false))
  ([points-temp points-rec flip-x flip-y]
   (let [center (gco/center-points points-temp)

         stretch-matrix (gmt/matrix)

         skew-angle (calculate-skew-angle points-temp)

         ;; When one of the axis is flipped we have to reverse the skew
         ;; skew-angle (if (neg? (* (:x resize-vector) (:y resize-vector))) (- skew-angle) skew-angle )
         skew-angle (if (and (or flip-x flip-y)
                             (not (and flip-x flip-y))) (- skew-angle) skew-angle )
         skew-angle (if (mth/nan? skew-angle) 0 skew-angle)

         stretch-matrix (gmt/multiply stretch-matrix (gmt/skew-matrix skew-angle 0))

         h1 (max 1 (calculate-height points-temp))
         h2 (max 1 (calculate-height (transform-points points-rec center stretch-matrix)))
         h3 (if-not (mth/almost-zero? h2) (/ h1 h2) 1)
         h3 (if (mth/nan? h3) 1 h3)

         w1 (max 1 (calculate-width points-temp))
         w2 (max 1 (calculate-width (transform-points points-rec center stretch-matrix)))
         w3 (if-not (mth/almost-zero? w2) (/ w1 w2) 1)
         w3 (if (mth/nan? w3) 1 w3)

         stretch-matrix (gmt/multiply stretch-matrix (gmt/scale-matrix (gpt/point w3 h3)))

         rotation-angle (calculate-rotation
                         center
                         (transform-points points-rec (gco/center-points points-rec) stretch-matrix)
                         points-temp
                         flip-x
                         flip-y)

         stretch-matrix (gmt/multiply (gmt/rotate-matrix rotation-angle) stretch-matrix)

         ;; This is the inverse to be able to remove the transformation
         stretch-matrix-inverse (-> (gmt/matrix)
                                    (gmt/scale (gpt/point (/ 1 w3) (/ 1 h3)))
                                    (gmt/skew (- skew-angle) 0)
                                    (gmt/rotate (- rotation-angle)))]
     [stretch-matrix stretch-matrix-inverse rotation-angle])))

(defn apply-transform
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform]
  ;;
  (let [points (-> shape :points (transform-points transform))
        center (gco/center-points points)

        ;; Reverse the current transformation stack to get the base rectangle
        tr-inverse      (:transform-inverse shape (gmt/matrix))

        points-temp     (transform-points points center tr-inverse)
        points-temp-dim (calculate-dimensions points-temp)

        ;; This rectangle is the new data for the current rectangle. We want to change our rectangle
        ;; to have this width, height, x, y
        rect-shape      (-> (gco/make-centered-rect
                             center
                             (:width points-temp-dim)
                             (:height points-temp-dim))
                            (update :width max 1)
                            (update :height max 1))

        rect-points     (gpr/rect->points rect-shape)

        [matrix matrix-inverse] (calculate-adjust-matrix points-temp rect-points (:flip-x shape) (:flip-y shape))

        shape (cond
                (= :path (:type shape))
                (-> shape
                    (update :content #(gpa/transform-content % transform)))

                :else
                (-> shape
                    (merge  rect-shape)
                    (update :x #(mth/precision % 0))
                    (update :y #(mth/precision % 0))
                    (update :width #(mth/precision % 0))
                    (update :height #(mth/precision % 0))))]
    (as-> shape $
      (update $ :transform #(gmt/multiply (or % (gmt/matrix)) matrix))
      (update $ :transform-inverse #(gmt/multiply matrix-inverse (or % (gmt/matrix))))
      (assoc  $ :points (into [] points))
      (assoc  $ :selrect (gpr/rect->selrect rect-shape))
      (update $ :rotation #(mod (+ (or % 0)
                                   (or (get-in $ [:modifiers :rotation]) 0)) 360)))))

(defn set-flip [shape modifiers]
  (let [rx (get-in modifiers [:resize-vector :x])
        ry (get-in modifiers [:resize-vector :y])]
    (cond-> shape
      (and rx (< rx 0)) (update :flip-x not)
      (and ry (< ry 0)) (update :flip-y not))))

(defn apply-displacement [shape]
  (let [modifiers (:modifiers shape)]
    (if (contains? modifiers :displacement)
      (let [mov-vec (-> (gpt/point 0 0)
                        (gpt/transform (:displacement modifiers)))
            shape (move shape mov-vec)
            modifiers (dissoc modifiers :displacement)]
        (-> shape
            (assoc :modifiers modifiers)
            (cond-> (empty? modifiers)
              (dissoc :modifiers))))
      shape)))

(defn transform-shape [shape]
  (let [shape (apply-displacement shape)
        center (gco/center-shape shape)
        modifiers (:modifiers shape)]
    (if (and modifiers center)
      (let [transform (modifiers->transform center modifiers)]
        (-> shape
            (set-flip modifiers)
            (apply-transform transform)
            (dissoc :modifiers)))
      shape)))

(defn update-group-viewbox
  "Updates the viewbox for groups imported from SVG's"
  [{:keys [selrect svg-viewbox] :as group} new-selrect]
  (let [;; Gets deltas for the selrect to update the svg-viewbox (for svg-imports)
        deltas {:x      (- (:x new-selrect 0)      (:x selrect 0))
                :y      (- (:y new-selrect 0)      (:y selrect 0))
                :width  (- (:width new-selrect 1)  (:width selrect 1))
                :height (- (:height new-selrect 1) (:height selrect 1))}]
    
    (cond-> group
      (and (some? svg-viewbox) (some? selrect) (some? new-selrect))
      (update :svg-viewbox
              #(-> %
                   (update :x + (:x deltas))
                   (update :y + (:y deltas))
                   (update :width + (:width deltas))
                   (update :height + (:height deltas)))))))

(defn update-group-selrect [group children]
  (let [shape-center (gco/center-shape group)
        transform (:transform group (gmt/matrix))
        transform-inverse (:transform-inverse group (gmt/matrix))

        ;; Points for every shape inside the group
        points (->> children (mapcat :points))

        ;; Invert to get the points minus the transforms applied to the group
        base-points (transform-points points shape-center (:transform-inverse group (gmt/matrix)))

        ;; Defines the new selection rect with its transformations
        new-points (-> (gpr/points->selrect base-points)
                       (gpr/rect->points)
                       (transform-points shape-center (:transform group (gmt/matrix))))

        ;; Calculte the new selrect
        new-selrect (gpr/points->selrect base-points)]

    ;; Updates the shape and the applytransform-rect will update the other properties
    (-> group
        (update-group-viewbox new-selrect)
        (assoc :selrect new-selrect)
        (assoc :points new-points)

        ;; We're regenerating the selrect from its children so we
        ;; need to remove the flip flags
        (assoc :flip-x false)
        (assoc :flip-y false)
        (apply-transform (gmt/matrix)))))


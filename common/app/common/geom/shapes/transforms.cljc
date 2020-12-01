;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.shapes.transforms
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]))

(defn transform-matrix
  "Returns a transformation matrix without changing the shape properties.
  The result should be used in a `transform` attribute in svg"
  ([shape] (transform-matrix shape nil))
  ([{:keys [flip-x flip-y] :as shape} {:keys [no-flip]}]
   (let [shape-center (or (gco/center-shape shape)
                          (gpt/point 0 0))]
     (-> (gmt/matrix)
         (gmt/translate shape-center)

         (gmt/multiply (:transform shape (gmt/matrix)))
         (cond->
             (and (not no-flip) flip-x) (gmt/scale (gpt/point -1 1))
             (and (not no-flip) flip-y) (gmt/scale (gpt/point 1 -1)))
         (gmt/translate (gpt/negate shape-center))))))

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
    (- 90 (gpt/angle-with-other v1 v2))))

(defn- calculate-height
  "Calculates the height of a paralelogram given by the points"
  [[p1 _ p3 p4]]
  (let [v1 (gpt/to-vec p3 p4)
        v2 (gpt/to-vec p4 p1)
        angle (gpt/angle-with-other v1 v2)]
    (* (gpt/length v2) (mth/sin (mth/radians angle)))))

(defn- calculate-rotation
  "Calculates the rotation between two shapes given the resize vector direction"
  [points-shape1 points-shape2 flip-x flip-y]

  (let [idx-1 0
        idx-2 (cond (and flip-x       (not flip-y)) 1
                    (and flip-x       flip-y) 2
                    (and (not flip-x) flip-y) 3
                    :else 0)
        p1 (nth points-shape1 idx-1)
        p2 (nth points-shape2 idx-2)
        v1 (gpt/to-vec (gco/center-points points-shape1) p1)
        v2 (gpt/to-vec (gco/center-points points-shape2) p2)

        rot-angle (gpt/angle-with-other v1 v2)
        rot-sign (if (> (* (:y v1) (:x v2)) (* (:x v1) (:y v2))) -1 1)]
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
  [points-temp points-rec flip-x flip-y]
  (let [center (gco/center-points points-temp)

        stretch-matrix (gmt/matrix)

        skew-angle (calculate-skew-angle points-temp)

        ;; When one of the axis is flipped we have to reverse the skew
        ;; skew-angle (if (neg? (* (:x resize-vector) (:y resize-vector))) (- skew-angle) skew-angle )
        skew-angle (if (and (or flip-x flip-y)
                            (not (and flip-x flip-y))) (- skew-angle) skew-angle )
        skew-angle (if (mth/nan? skew-angle) 0 skew-angle)

        stretch-matrix (gmt/multiply stretch-matrix (gmt/skew-matrix skew-angle 0))

        h1 (calculate-height points-temp)
        h2 (calculate-height (transform-points points-rec center stretch-matrix))
        h3 (if-not (mth/almost-zero? h2) (/ h1 h2) 1)
        h3 (if (mth/nan? h3) 1 h3)

        stretch-matrix (gmt/multiply stretch-matrix (gmt/scale-matrix (gpt/point 1 h3)))

        rotation-angle (calculate-rotation
                        (transform-points points-rec (gco/center-points points-rec) stretch-matrix)
                        points-temp
                        flip-x
                        flip-y)

        stretch-matrix (gmt/multiply (gmt/rotate-matrix rotation-angle) stretch-matrix)


        ;; This is the inverse to be able to remove the transformation
        stretch-matrix-inverse (-> (gmt/matrix)
                                   (gmt/scale (gpt/point 1 (/ 1 h3)))
                                   (gmt/skew (- skew-angle) 0)
                                   (gmt/rotate (- rotation-angle)))]
    [stretch-matrix stretch-matrix-inverse]))


(defn apply-transform-path
  [shape transform]
  (let [content (gpa/transform-content (:content shape) transform)
        selrect (gpa/content->selrect content)
        points (gpr/rect->points selrect)
        ;;rotation (mod (+ (:rotation shape 0)
        ;;                 (or (get-in shape [:modifiers :rotation]) 0))
        ;;              360)
        ]
    (assoc shape
           :content content
           :points points
           :selrect selrect
           ;;:rotation rotation
           )))

(defn apply-transform-rect
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
        rect-shape      (gco/make-centered-rect center
                                                (:width points-temp-dim)
                                                (:height points-temp-dim))
        rect-points     (gpr/rect->points rect-shape)

        [matrix matrix-inverse] (calculate-adjust-matrix points-temp rect-points (:flip-x shape) (:flip-y shape))]
    (as-> shape $
      (merge  $ rect-shape)
      (update $ :x #(mth/precision % 0))
      (update $ :y #(mth/precision % 0))
      (update $ :width #(mth/precision % 0))
      (update $ :height #(mth/precision % 0))
      (update $ :transform #(gmt/multiply (or % (gmt/matrix)) matrix))
      (update $ :transform-inverse #(gmt/multiply matrix-inverse (or % (gmt/matrix))))
      (assoc  $ :points (into [] points))
      (assoc  $ :selrect (gpr/rect->selrect rect-shape))
      (update $ :rotation #(mod (+ (or % 0)
                                   (or (get-in $ [:modifiers :rotation]) 0)) 360)))))

(defn apply-transform [shape transform]
  (let [apply-transform-fn
        (case (:type shape)
          :path  apply-transform-path
          apply-transform-rect)]
    (apply-transform-fn shape transform)))

(defn set-flip [shape modifiers]
  (let [rx (get-in modifiers [:resize-vector :x])
        ry (get-in modifiers [:resize-vector :y])]
    (cond-> shape
      (and rx (< rx 0)) (update :flip-x not)
      (and ry (< ry 0)) (update :flip-y not))))

(defn transform-shape [shape]
  (let [center (gco/center-shape shape)]
    (if (and (:modifiers shape) center)
      (let [transform (modifiers->transform center (:modifiers shape))]
        (-> shape
            (set-flip (:modifiers shape))
            (apply-transform transform)
            (dissoc :modifiers)))
      shape)))

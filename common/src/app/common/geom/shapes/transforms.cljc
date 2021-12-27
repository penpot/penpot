;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.transforms
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.spec :as us]
   [app.common.text :as txt]))

(def ^:dynamic *skip-adjust* false)

;; --- Relative Movement

(defn- move-selrect [selrect pt]
  (when (and (some? selrect) (some? pt))
    (let [dx (.-x pt)
          dy (.-y pt)
          {:keys [x y x1 y1 x2 y2 width height]} selrect]
      {:x     (if (some? x) (+ dx x) x)
       :y     (if (some? y) (+ dy y) y)
       :x1    (if (some? x1) (+ dx x1) x1)
       :y1    (if (some? y1) (+ dy y1) y1)
       :x2    (if (some? x2) (+ dx x2) x2)
       :y2    (if (some? y2) (+ dy y2) y2)
       :width width
       :height height})))

(defn- move-points [points move-vec]
  (->> points
       (mapv #(gpt/add % move-vec))))

(defn move
  "Move the shape relatively to its current
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
        (cond-> (= :bool (:type shape))
          (update :bool-content gpa/move-content move-vec))
        (cond-> (= :path (:type shape))
          (update :content gpa/move-content move-vec)))))


;; --- Absolute Movement

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape {:keys [x y]}]
  (let [dx (- (d/check-num x) (-> shape :selrect :x))
        dy (- (d/check-num y) (-> shape :selrect :y))]
    (move shape (gpt/point dx dy))))


; ---- Geometric operations

(defn- normalize-scale
  "We normalize the scale so it's not too close to 0"
  [scale]
  (cond
    (and (<  scale 0) (> scale -0.01)) -0.01
    (and (>= scale 0) (< scale  0.01))  0.01
    :else scale))

(defn- calculate-skew-angle
  "Calculates the skew angle of the parallelogram given by the points"
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
  "Calculates the height of a parallelogram given by the points"
  [[p1 _ _ p4]]
  (-> (gpt/to-vec p4 p1)
      (gpt/length)))

(defn- calculate-width
  "Calculates the width of a parallelogram given by the points"
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


;; --- Transformation matrix operations

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
   (-> (gmt/matrix)
       (gmt/translate center)
       (cond->
           flip-x (gmt/scale (gpt/point -1 1))
           flip-y (gmt/scale (gpt/point 1 -1)))
       (gmt/multiply (:transform-inverse shape (gmt/matrix)))
       (gmt/translate (gpt/negate center)))))

(defn transform-point-center
  "Transform a point around the shape center"
  [point center matrix]
  (when point
    (gpt/transform
     point
     (gmt/multiply (gmt/translate-matrix center)
                   matrix
                   (gmt/translate-matrix (gpt/negate center))))))

(defn transform-rect
  "Transform a rectangles and changes its attributes"
  [rect matrix]

  (let [points (-> (gpr/rect->points rect)
                   (gco/transform-points matrix))]
    (gpr/points->rect points)))

(defn calculate-adjust-matrix
  "Calculates a matrix that is a series of transformations we have to do to the transformed rectangle so that
  after applying them the end result is the `shape-path-temp`.
  This is compose of three transformations: skew, resize and rotation"
  ([points-temp points-rec]
   (calculate-adjust-matrix points-temp points-rec false false))

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
         h2 (max 1 (calculate-height (gco/transform-points points-rec center stretch-matrix)))
         h3 (if-not (mth/almost-zero? h2) (/ h1 h2) 1)
         h3 (if (mth/nan? h3) 1 h3)

         w1 (max 1 (calculate-width points-temp))
         w2 (max 1 (calculate-width (gco/transform-points points-rec center stretch-matrix)))
         w3 (if-not (mth/almost-zero? w2) (/ w1 w2) 1)
         w3 (if (mth/nan? w3) 1 w3)

         stretch-matrix (gmt/multiply stretch-matrix (gmt/scale-matrix (gpt/point w3 h3)))

         rotation-angle (calculate-rotation
                         center
                         (gco/transform-points points-rec (gco/center-points points-rec) stretch-matrix)
                         points-temp
                         flip-x
                         flip-y)

         stretch-matrix (gmt/multiply (gmt/rotate-matrix rotation-angle) stretch-matrix)

         ;; This is the inverse to be able to remove the transformation
         stretch-matrix-inverse
         (gmt/multiply (gmt/scale-matrix (gpt/point (/ 1 w3) (/ 1 h3)))
                       (gmt/skew-matrix (- skew-angle) 0)
                       (gmt/rotate-matrix (- rotation-angle)))]
     [stretch-matrix stretch-matrix-inverse rotation-angle])))

(defn is-rotated?
  [[a b _c _d]]
  ;; true if either a-b or c-d are parallel to the axis
  (not (mth/close? (:y a) (:y b))))

(defn- adjust-rotated-transform
  [{:keys [transform transform-inverse flip-x flip-y]} points]
  (let [center          (gco/center-points points)

        points-temp     (cond-> points
                          (some? transform-inverse)
                          (gco/transform-points center transform-inverse))
        points-temp-dim (calculate-dimensions points-temp)

        ;; This rectangle is the new data for the current rectangle. We want to change our rectangle
        ;; to have this width, height, x, y
        new-width  (max 1 (:width points-temp-dim))
        new-height (max 1 (:height points-temp-dim))
        selrect    (gco/make-centered-selrect center new-width new-height)

        rect-points  (gpr/rect->points selrect)
        [matrix matrix-inverse] (calculate-adjust-matrix points-temp rect-points flip-x flip-y)]

    [selrect
     (if transform (gmt/multiply transform matrix) matrix)
     (if transform-inverse (gmt/multiply matrix-inverse transform-inverse) matrix-inverse)]))

(defn- apply-transform
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx round-coords?]

  (let [points'  (:points shape)
        points   (gco/transform-points points' transform-mtx)
        bool?    (= (:type shape) :bool)
        path?    (= (:type shape) :path)
        rotated? (is-rotated? points)

        [selrect transform transform-inverse]
        (if (not rotated?)
          [(gpr/points->selrect points) nil nil]
          (adjust-rotated-transform shape points))

        selrect (cond-> selrect
                  round-coords? gpr/round-selrect)

        ;; Redondear los points?
        base-rotation  (or (:rotation shape) 0)
        modif-rotation (or (get-in shape [:modifiers :rotation]) 0)
        rotation       (mod (+ base-rotation modif-rotation) 360)]

    (-> shape
        (cond-> bool?
          (update :bool-content gpa/transform-content transform-mtx))
        (cond-> path?
          (update :content gpa/transform-content transform-mtx))
        (cond-> (not path?)
          (-> (merge (select-keys selrect [:x :y :width :height]))))
        (cond-> transform
          (-> (assoc :transform transform)
              (assoc :transform-inverse transform-inverse)))
        (assoc :selrect selrect)
        (assoc :points points)
        (assoc :rotation rotation))))

(defn- update-group-viewbox
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
        ;; Points for every shape inside the group
        points (->> children (mapcat :points))

        ;; Invert to get the points minus the transforms applied to the group
        base-points (gco/transform-points points shape-center (:transform-inverse group (gmt/matrix)))

        ;; Defines the new selection rect with its transformations
        new-points (-> (gpr/points->selrect base-points)
                       (gpr/rect->points)
                       (gco/transform-points shape-center (:transform group (gmt/matrix))))

        ;; Calculate the new selrect
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
        (apply-transform (gmt/matrix) true))))


;; --- Modifiers

;; The `modifiers` structure contains a list of transformations to
;; do make to a shape, in this order:
;;
;; - resize-origin (gpt/point) + resize-vector (gpt/point)
;;   apply a scale vector to all points of the shapes, starting
;;   from the origin point.
;;
;; - resize-origin-2 + resize-vector-2
;;   same as the previous one, for cases in that we need to make
;;   two vectors from different origin points.
;;
;; - displacement (gmt/matrix)
;;   apply a translation matrix to the shape
;;
;; - rotation (gmt/matrix)
;;   apply a rotation matrix to the shape
;;
;; - resize-transform (gmt/matrix) + resize-transform-inverse (gmt/matrix)
;;   a copy of the rotation matrix currently applied to the shape;
;;   this is needed temporarily to apply the resize vectors.
;;
;; - resize-scale-text (bool)
;;   tells if the resize vectors must be applied to text shapes
;;   or not.

(defn empty-modifiers? [modifiers]
  (empty? (dissoc modifiers :ignore-geometry?)))

(defn resize-modifiers
  [shape attr value]
  (us/assert map? shape)
  (us/assert #{:width :height} attr)
  (us/assert number? value)
  (let [{:keys [proportion proportion-lock]} shape
        size (select-keys (:selrect shape) [:width :height])
        new-size (if-not proportion-lock
                   (assoc size attr value)
                   (if (= attr :width)
                     (-> size
                         (assoc :width value)
                         (assoc :height (/ value proportion)))
                     (-> size
                         (assoc :height value)
                         (assoc :width (* value proportion)))))
        width (:width new-size)
        height (:height new-size)

        shape-transform (:transform shape (gmt/matrix))
        shape-transform-inv (:transform-inverse shape (gmt/matrix))
        shape-center (gco/center-shape shape)
        {sr-width :width sr-height :height} (:selrect shape)

        origin (-> (gpt/point (:selrect shape))
                   (transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point width height)
                           (gpt/point sr-width sr-height))]
    {:resize-vector scalev
     :resize-origin origin
     :resize-transform shape-transform
     :resize-transform-inverse shape-transform-inv}))

(defn rotation-modifiers
  [shape center angle]
  (let [displacement (let [shape-center (gco/center-shape shape)]
                       (-> (gmt/matrix)
                           (gmt/rotate angle center)
                           (gmt/rotate (- angle) shape-center)))]
    {:rotation angle
     :displacement displacement}))

(defn merge-modifiers*
  [objects modifiers]

  (let [set-modifier
        (fn [objects [id modifiers]]
          (-> objects
              (d/update-when id merge modifiers)))]
    (->> modifiers
         (reduce set-modifier objects))))

(def merge-modifiers (memoize merge-modifiers*))

(defn modifiers->transform
  ([modifiers]
   (modifiers->transform nil modifiers))

  ([center modifiers]
   (let [displacement (:displacement modifiers)
         resize-v1 (:resize-vector modifiers)
         resize-v2 (:resize-vector-2 modifiers)
         origin-1 (:resize-origin modifiers (gpt/point))
         origin-2 (:resize-origin-2 modifiers (gpt/point))

         ;; Normalize x/y vector coordinates because scale by 0 is infinite
         resize-1 (when (some? resize-v1)
                    (gpt/point (normalize-scale (:x resize-v1))
                               (normalize-scale (:y resize-v1))))

         resize-2 (when (some? resize-v2)
                    (gpt/point (normalize-scale (:x resize-v2))
                               (normalize-scale (:y resize-v2))))


         resize-transform (:resize-transform modifiers (gmt/matrix))
         resize-transform-inverse (:resize-transform-inverse modifiers (gmt/matrix))

         rt-modif (:rotation modifiers)]

     (cond-> (gmt/matrix)
       (some? displacement)
       (gmt/multiply displacement)

       (some? resize-1)
       (-> (gmt/translate origin-1)
           (gmt/multiply resize-transform)
           (gmt/scale resize-1)
           (gmt/multiply resize-transform-inverse)
           (gmt/translate (gpt/negate origin-1)))

       (some? resize-2)
       (-> (gmt/translate origin-2)
           (gmt/multiply resize-transform)
           (gmt/scale resize-2)
           (gmt/multiply resize-transform-inverse)
           (gmt/translate (gpt/negate origin-2)))

       (some? rt-modif)
       (-> (gmt/translate center)
           (gmt/multiply (gmt/rotate-matrix rt-modif))
           (gmt/translate (gpt/negate center)))))))

(defn- set-flip [shape modifiers]
  (let [rx (or (get-in modifiers [:resize-vector :x])
               (get-in modifiers [:resize-vector-2 :x]))
        ry (or (get-in modifiers [:resize-vector :y])
               (get-in modifiers [:resize-vector-2 :y]))]
    (cond-> shape
      (and rx (< rx 0)) (-> (update :flip-x not)
                            (update :rotation -))
      (and ry (< ry 0)) (-> (update :flip-y not)
                            (update :rotation -)))))

(defn- apply-displacement [shape]
  (let [modifiers (:modifiers shape)]
    (if (contains? modifiers :displacement)
      (let [mov-vec (-> (gpt/point 0 0)
                        (gpt/transform (:displacement modifiers)))
            shape (move shape mov-vec)
            modifiers (dissoc modifiers :displacement)]
        (-> shape
            (assoc :modifiers modifiers)
            (cond-> (empty-modifiers? modifiers)
              (dissoc :modifiers))))
      shape)))

(defn- apply-text-resize
  [shape modifiers]
  (if (and (= (:type shape) :text)
           (:resize-scale-text modifiers))
    (let [merge-attrs (fn [attrs]
                        (let [font-size (-> (get attrs :font-size 14)
                                            (d/parse-double)
                                            (* (get-in modifiers [:resize-vector :x] 1))
                                            (* (get-in modifiers [:resize-vector-2 :x] 1))
                                            (mth/precision 2)
                                            (str))]
                          (attrs/merge attrs {:font-size font-size})))]
      (update shape :content #(txt/transform-nodes
                                txt/is-text-node?
                                merge-attrs
                                %)))
    shape))

(defn apply-modifiers
  [shape modifiers round-coords?]
  (let [center (gco/center-shape shape)
        transform (modifiers->transform center modifiers)]
    (apply-transform shape transform round-coords?)))

(defn transform-shape
  ([shape]
   (transform-shape shape nil))

  ([shape {:keys [round-coords?] :or {round-coords? true}}]
   (let [modifiers (:modifiers shape)]
     (cond
       (nil? modifiers)
       shape

       (empty-modifiers? modifiers)
       (dissoc shape :modifiers)

       :else
       (let [shape     (apply-displacement shape)
             modifiers (:modifiers shape)]
         (cond-> shape
           (not (empty-modifiers? modifiers))
           (-> (set-flip modifiers)
               (apply-modifiers modifiers round-coords?)
               (apply-text-resize modifiers))

           :always
           (dissoc :modifiers)))))))

(defn transform-selrect
  [selrect {:keys [displacement resize-transform-inverse resize-vector resize-origin resize-vector-2 resize-origin-2]}]
 
  ;; FIXME: Improve Performance
  (let [resize-transform-inverse (or resize-transform-inverse (gmt/matrix))

        displacement
        (when (some? displacement)
          (gmt/multiply resize-transform-inverse displacement)
          #_(-> (gpt/point 0 0)
              (gpt/transform displacement)
              (gpt/transform resize-transform-inverse)
              (gmt/translate-matrix)))

        resize-origin
        (when (some? resize-origin)
          (transform-point-center resize-origin (gco/center-selrect selrect) resize-transform-inverse))

        resize-origin-2
        (when (some? resize-origin-2)
          (transform-point-center resize-origin-2 (gco/center-selrect selrect) resize-transform-inverse))]

    (if (and (nil? displacement) (nil? resize-origin) (nil? resize-origin-2))
      selrect

      (cond-> selrect
        :always
        (gpr/rect->points)

        (some? displacement)
        (gco/transform-points displacement)

        (some? resize-origin)
        (gco/transform-points resize-origin (gmt/scale-matrix resize-vector))

        (some? resize-origin-2)
        (gco/transform-points resize-origin-2 (gmt/scale-matrix resize-vector-2))

        :always
        (gpr/points->selrect)))))


(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (->> shapes
       (map (comp gpr/points->selrect :points transform-shape))
       (gpr/join-selrects)))

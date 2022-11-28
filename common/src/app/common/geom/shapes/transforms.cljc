;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.transforms
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.bool :as gshb]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]))

(def ^:dynamic *skip-adjust* false)

;; --- Relative Movement

(defn- move-selrect [{:keys [x y x1 y1 x2 y2 width height] :as selrect} {dx :x dy :y :as pt}]
  (if (and (some? selrect) (some? pt) (d/num? dx dy))
    {:x      (if (d/num? x)  (+ dx x)  x)
     :y      (if (d/num? y)  (+ dy y)  y)
     :x1     (if (d/num? x1) (+ dx x1) x1)
     :y1     (if (d/num? y1) (+ dy y1) y1)
     :x2     (if (d/num? x2) (+ dx x2) x2)
     :y2     (if (d/num? y2) (+ dy y2) y2)
     :width  width
     :height height}
    selrect))

(defn- move-points [points move-vec]
  (cond->> points
    (d/num? (:x move-vec) (:y move-vec))
    (mapv #(gpt/add % move-vec))))

(defn move-position-data
  [position-data dx dy]

  (when (some? position-data)
    (cond->> position-data
      (d/num? dx dy)
      (mapv #(-> %
                 (update :x + dx)
                 (update :y + dy))))))

(defn move
  "Move the shape relatively to its current
  position applying the provided delta."
  [{:keys [type] :as shape} {dx :x dy :y}]
  (let [dx       (d/check-num dx 0)
        dy       (d/check-num dy 0)
        move-vec (gpt/point dx dy)]

    (-> shape
        (update :selrect move-selrect move-vec)
        (update :points move-points move-vec)
        (d/update-when :x + dx)
        (d/update-when :y + dy)
        (d/update-when :position-data move-position-data dx dy)
        (cond-> (= :bool type) (update :bool-content gpa/move-content move-vec))
        (cond-> (= :path type) (update :content gpa/move-content move-vec)))))

;; --- Absolute Movement

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape {:keys [x y]}]
  (let [dx (- (d/check-num x) (-> shape :selrect :x))
        dy (- (d/check-num y) (-> shape :selrect :y))]
    (move shape (gpt/point dx dy))))


; ---- Geometric operations

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
  ([shape]
   (transform-matrix shape nil))

  ([shape params]
   (transform-matrix shape params (or (gco/center-shape shape) (gpt/point 0 0))))

  ([{:keys [flip-x flip-y transform] :as shape} {:keys [no-flip]} shape-center]
   (-> (gmt/matrix)
       (gmt/translate shape-center)

       (cond-> (some? transform)
         (gmt/multiply transform))

       (cond->
           (and (not no-flip) flip-x) (gmt/scale (gpt/point -1 1))
           (and (not no-flip) flip-y) (gmt/scale (gpt/point 1 -1)))
       (gmt/translate (gpt/negate shape-center)))))

(defn transform-str
  ([shape]
   (transform-str shape nil))

  ([{:keys [transform flip-x flip-y] :as shape} {:keys [no-flip] :as params}]
   (if (and (some? shape)
            (or (some? transform)
                (and (not no-flip) flip-x)
                (and (not no-flip) flip-y)))
     (dm/str (transform-matrix shape params))
     "")))

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
  [points-temp points-rec flip-x flip-y]
  (let [center (gco/center-bounds points-temp)

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
    [stretch-matrix stretch-matrix-inverse rotation-angle]))

(defn- adjust-rotated-transform
  [{:keys [transform transform-inverse flip-x flip-y]} points]
  (let [center          (gco/center-bounds points)

        points-temp     (cond-> points
                          (some? transform-inverse)
                          (gco/transform-points center transform-inverse))
        points-temp-dim (calculate-dimensions points-temp)

        ;; This rectangle is the new data for the current rectangle. We want to change our rectangle
        ;; to have this width, height, x, y
        new-width  (max 0.01 (:width points-temp-dim))
        new-height (max 0.01 (:height points-temp-dim))
        selrect    (gpr/center->selrect center new-width new-height)

        rect-points  (gpr/rect->points selrect)
        [matrix matrix-inverse] (calculate-adjust-matrix points-temp rect-points flip-x flip-y)]

    [selrect
     (if transform (gmt/multiply transform matrix) matrix)
     (if transform-inverse (gmt/multiply matrix-inverse transform-inverse) matrix-inverse)]))

(defn- adjust-shape-flips
  "After some tranformations the flip-x/flip-y flags can change we need
  to check this before adjusting the selrect"
  [shape points]

  (let [points' (:points shape)

        xv1 (gpt/to-vec (nth points' 0) (nth points' 1))
        xv2 (gpt/to-vec (nth points 0) (nth points 1))
        dot-x (gpt/dot xv1 xv2)

        yv1 (gpt/to-vec (nth points' 0) (nth points' 3))
        yv2 (gpt/to-vec (nth points 0) (nth points 3))
        dot-y (gpt/dot yv1 yv2)]

    (cond-> shape
      (neg? dot-x)
      (-> (update :flip-x not)
          (update :rotation -))

      (neg? dot-y)
      (-> (update :flip-y not)
          (update :rotation -)))))

(defn- apply-transform-move
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx]
  (let [bool?   (= (:type shape) :bool)
        path?   (= (:type shape) :path)
        text?   (= (:type shape) :text)
        {dx :x dy :y} (gpt/transform (gpt/point) transform-mtx)
        points  (gco/transform-points (:points shape) transform-mtx)
        selrect (gco/transform-selrect (:selrect shape) transform-mtx)]
    (-> shape
        (cond-> bool?
          (update :bool-content gpa/transform-content transform-mtx))
        (cond-> path?
          (update :content gpa/transform-content transform-mtx))
        (cond-> text?
          (update :position-data move-position-data dx dy))
        (cond-> (not path?)
          (assoc :x (:x selrect)
                 :y (:y selrect)
                 :width (:width selrect)
                 :height (:height selrect)))
        (assoc :selrect selrect)
        (assoc :points points))))

(defn- apply-transform-generic
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx]

  (let [points'  (:points shape)
        points   (gco/transform-points points' transform-mtx)
        shape    (-> shape (adjust-shape-flips points))
        bool?    (= (:type shape) :bool)
        path?    (= (:type shape) :path)

        [selrect transform transform-inverse]
        (adjust-rotated-transform shape points)

        base-rotation  (or (:rotation shape) 0)
        modif-rotation (or (get-in shape [:modifiers :rotation]) 0)
        rotation       (mod (+ base-rotation modif-rotation) 360)]
    (-> shape
        (cond-> bool?
          (update :bool-content gpa/transform-content transform-mtx))
        (cond-> path?
          (update :content gpa/transform-content transform-mtx))
        (cond-> (not path?)
          (assoc :x (:x selrect)
                 :y (:y selrect)
                 :width (:width selrect)
                 :height (:height selrect)))
        (cond-> transform
          (-> (assoc :transform transform)
              (assoc :transform-inverse transform-inverse)))
        (cond-> (not transform)
          (dissoc :transform :transform-inverse))
        (cond-> (some? selrect)
          (assoc :selrect selrect))

        (cond-> (d/not-empty? points)
          (assoc :points points))
        (assoc :rotation rotation))))

(defn- apply-transform
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx]
  (if (gmt/move? transform-mtx)
    (apply-transform-move shape transform-mtx)
    (apply-transform-generic shape transform-mtx)))

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

(defn group-bounds
  [group children-bounds]
  (let [shape-center (gco/center-shape group)
        points (flatten children-bounds)
        points (if (empty? points) (:points group) points)]
    (-> points
        (gco/transform-points shape-center (:transform-inverse group (gmt/matrix)))
        (gpr/squared-points)
        (gco/transform-points shape-center (:transform group (gmt/matrix))))))

(defn update-group-selrect
  [group children]
  (let [shape-center (gco/center-shape group)
        ;; Points for every shape inside the group
        points (->> children (mapcat :points))

        ;; Fixed problem with empty groups. Should not happen (but it does)
        points (if (empty? points) (:points group) points)

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
        (apply-transform (gmt/matrix)))))

(defn update-mask-selrect
  [masked-group children]
  (let [mask (first children)]
    (-> masked-group
        (assoc :selrect (-> mask :selrect))
        (assoc :points  (-> mask :points))
        (assoc :x       (-> mask :selrect :x))
        (assoc :y       (-> mask :selrect :y))
        (assoc :width   (-> mask :selrect :width))
        (assoc :height  (-> mask :selrect :height))
        (assoc :flip-x  (-> mask :flip-x))
        (assoc :flip-y  (-> mask :flip-y)))))

(defn update-bool-selrect
  "Calculates the selrect+points for the boolean shape"
  [shape children objects]

  (let [bool-content     (gshb/calc-bool-content shape objects)
        shape            (assoc shape :bool-content bool-content)
        [points selrect] (gpa/content->points+selrect shape bool-content)]

    (if (and (some? selrect) (d/not-empty? points))
      (-> shape
          (assoc :selrect selrect)
          (assoc :points points))
      (update-group-selrect shape children))))

(defn transform-shape
  ([shape]
   (let [modifiers (:modifiers shape)]
     (-> shape
         (dissoc :modifiers)
         (transform-shape modifiers))))

  ([shape modifiers]
   (letfn [(apply-modifiers
             [shape modifiers]
             (if (ctm/empty? modifiers)
               shape
               (let [transform (ctm/modifiers->transform modifiers)]
                 (cond-> shape
                   (and (some? transform) (not= uuid/zero (:id shape))) ;; Never transform the root frame
                   (apply-transform transform)

                   (ctm/has-structure? modifiers)
                   (ctm/apply-structure-modifiers modifiers)))))]

     (cond-> shape
       (and (some? modifiers) (not (ctm/empty? modifiers)))
       (apply-modifiers modifiers)))))

(defn apply-objects-modifiers
  ([objects modifiers]
   (apply-objects-modifiers objects modifiers (keys modifiers)))

  ([objects modifiers ids]
   (loop [objects objects
          ids (seq ids)]
     (if (empty? ids)
       objects

       (let [id (first ids)
             modifier (dm/get-in modifiers [id :modifiers])]
         (recur (d/update-when objects id transform-shape modifier)
                (rest ids)))))))

(defn transform-bounds
  ([points modifiers]
   (transform-bounds points nil modifiers))

  ([points center modifiers]
   (let [transform (ctm/modifiers->transform modifiers)]
    (cond-> points
      (some? transform)
      (gco/transform-points center transform)))))

(defn transform-selrect
  [selrect modifiers]
  (-> selrect
      (gpr/rect->points)
      (transform-bounds modifiers)
      (gpr/points->selrect)))

(defn transform-selrect-matrix
  [selrect mtx]
  (-> selrect
      (gpr/rect->points)
      (gco/transform-points mtx)
      (gpr/points->selrect)))

(defn selection-rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (->> shapes
       (map (comp gpr/points->selrect :points transform-shape))
       (gpr/join-selrects)))

(declare apply-group-modifiers)

(defn apply-children-modifiers
  [objects modif-tree parent-modifiers children propagate?]
  (->> children
       (map (fn [child]
              (let [modifiers (cond-> (get-in modif-tree [(:id child) :modifiers])
                                propagate? (ctm/add-modifiers parent-modifiers))
                    child     (transform-shape child modifiers)
                    parent?   (cph/group-like-shape? child)

                    modif-tree
                    (cond-> modif-tree
                      propagate?
                      (assoc-in [(:id child) :modifiers] modifiers))]

                (cond-> child
                  parent?
                  (apply-group-modifiers objects modif-tree propagate?)))))))

(defn apply-group-modifiers
  "Apply the modifiers to the group children to calculate its selection rect"
  ([group objects modif-tree]
   (apply-group-modifiers group objects modif-tree true))

  ([group objects modif-tree propagate?]
   (let [modifiers (get-in modif-tree [(:id group) :modifiers])
         children
         (as-> (:shapes group) $
           (map (d/getf objects) $)
           (apply-children-modifiers objects modif-tree modifiers $ propagate?))]
     (cond
       (cph/mask-shape? group)
       (update-mask-selrect group children)

       (cph/bool-shape? group)
       (transform-shape group modifiers)

       (cph/group-shape? group)
       (update-group-selrect group children)

       :else
       group))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.transforms
  #?(:clj (:import (org.la4j Matrix LinearAlgebra))
     :cljs (:import goog.math.Matrix))
  (:require
   #?(:clj [app.common.exceptions :as ex])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.bool :as gshb]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]))

#?(:clj (set! *warn-on-reflection* true))

(defn- valid-point?
  [o]
  (and ^boolean (gpt/point? o)
       ^boolean (d/num? (dm/get-prop o :x)
                        (dm/get-prop o :y))))

;; --- Relative Movement

(defn- move-selrect
  [selrect pt]
  (if (and ^boolean (some? selrect)
           ^boolean (valid-point? pt))
    (let [x  (dm/get-prop selrect :x)
          y  (dm/get-prop selrect :y)
          w  (dm/get-prop selrect :width)
          h  (dm/get-prop selrect :height)
          x1 (dm/get-prop selrect :x1)
          y1 (dm/get-prop selrect :y1)
          x2 (dm/get-prop selrect :x2)
          y2 (dm/get-prop selrect :y2)
          dx (dm/get-prop pt :x)
          dy (dm/get-prop pt :y)]

      (grc/make-rect
       (if ^boolean (d/num? x) (+ dx x)  x)
       (if ^boolean (d/num? y)  (+ dy y)  y)
       w
       h
       (if ^boolean (d/num? x1) (+ dx x1) x1)
       (if ^boolean (d/num? y1) (+ dy y1) y1)
       (if ^boolean (d/num? x2) (+ dx x2) x2)
       (if ^boolean (d/num? y2) (+ dy y2) y2)))
    selrect))

(defn- move-points
  [points move-vec]
  (if (valid-point? move-vec)
    (mapv #(gpt/add % move-vec) points)
    points))

;; FIXME: revisit performance
(defn move-position-data
  ([position-data {:keys [x y]}]
   (move-position-data position-data x y))

  ([position-data dx dy]
   (when (some? position-data)
     (cond->> position-data
       (d/num? dx dy)
       (mapv #(-> %
                  (update :x + dx)
                  (update :y + dy)))))))

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

;; --- Transformation matrix operations

(defn transform-matrix
  "Returns a transformation matrix without changing the shape properties.
  The result should be used in a `transform` attribute in svg"
  ([shape]
   (transform-matrix shape nil))

  ([shape params]
   (transform-matrix shape params (or (gco/shape->center shape) (gpt/point 0 0))))

  ([{:keys [flip-x flip-y transform] :as shape} {:keys [no-flip]} shape-center]
   (-> (gmt/matrix)
       (gmt/translate shape-center)

       (cond-> (some? transform)
         (gmt/multiply transform))

       (cond-> (and flip-x no-flip)
         (gmt/scale (gpt/point -1 1)))

       (cond-> (and flip-y no-flip)
         (gmt/scale (gpt/point 1 -1)))

       (gmt/translate (gpt/negate shape-center)))))

(defn transform-str
  ([shape]
   (transform-str shape nil))

  ([{:keys [transform flip-x flip-y] :as shape} {:keys [no-flip] :as params}]
   (if (and (some? shape)
            (or (some? transform)
                (and no-flip flip-x)
                (and no-flip flip-y)))
     (dm/str (transform-matrix shape params))
     "")))

(defn inverse-transform-matrix
  ([shape]
   (let [shape-center (or (gco/shape->center shape)
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

  (let [points (-> (grc/rect->points rect)
                   (gco/transform-points matrix))]
    (grc/points->rect points)))

(defn transform-points-matrix
  "Calculate the transform matrix to convert from the selrect to the points bounds
    TargetM = SourceM * Transform ==> Transform = TargetM * inv(SourceM)"
  [{:keys [x1 y1 x2 y2]} [d1 d2 _ d4]]
  ;; If the coordinates are very close to zero (but not zero) the rounding can mess with the
  ;; transforms. So we round to zero the values
  (let [x1  (mth/round-to-zero x1)
        y1  (mth/round-to-zero y1)
        x2  (mth/round-to-zero x2)
        y2  (mth/round-to-zero y2)
        d1x (mth/round-to-zero (:x d1))
        d1y (mth/round-to-zero (:y d1))
        d2x (mth/round-to-zero (:x d2))
        d2y (mth/round-to-zero (:y d2))
        d4x (mth/round-to-zero (:x d4))
        d4y (mth/round-to-zero (:y d4))]
    #?(:clj
       ;; NOTE: the source matrix may not be invertible we can't
       ;; calculate the transform, so on exception we return `nil`
       (ex/ignoring
        (let [target-points-matrix
              (->> (list d1x d2x d4x
                         d1y d2y d4y
                         1     1   1)
                   (into-array Double/TYPE)
                   (Matrix/from1DArray 3 3))

              source-points-matrix
              (->> (list x1 x2 x1
                         y1 y1 y2
                         1  1  1)
                   (into-array Double/TYPE)
                   (Matrix/from1DArray 3 3))

              ;; May throw an exception if the matrix is not invertible
              source-points-matrix-inv
              (.. source-points-matrix
                  (withInverter LinearAlgebra/GAUSS_JORDAN)
                  (inverse))

              transform-jvm
              (.. target-points-matrix
                  (multiply source-points-matrix-inv))]

          (gmt/matrix (.get transform-jvm 0 0)
                      (.get transform-jvm 1 0)
                      (.get transform-jvm 0 1)
                      (.get transform-jvm 1 1)
                      (.get transform-jvm 0 2)
                      (.get transform-jvm 1 2))))

       :cljs
       (let [target-points-matrix
             (Matrix. #js [#js [d1x d2x d4x]
                           #js [d1y d2y d4y]
                           #js [  1   1   1]])

             source-points-matrix
             (Matrix. #js [#js [x1 x2 x1]
                           #js [y1 y1 y2]
                           #js [ 1  1  1]])

             ;; returns nil if not invertible
             source-points-matrix-inv (.getInverse source-points-matrix)

             ;; TargetM = SourceM * Transform ==> Transform = TargetM * inv(SourceM)
             transform-js
             (when source-points-matrix-inv
               (.multiply target-points-matrix source-points-matrix-inv))]

         (when transform-js
           (gmt/matrix (.getValueAt transform-js 0 0)
                       (.getValueAt transform-js 1 0)
                       (.getValueAt transform-js 0 1)
                       (.getValueAt transform-js 1 1)
                       (.getValueAt transform-js 0 2)
                       (.getValueAt transform-js 1 2)))))))

(defn calculate-geometry
  [points]
  (let [width  (calculate-width points)
        height (calculate-height points)

        ;; FIXME: looks redundant, we can convert points to rect directly
        center (gco/points->center points)
        sr     (grc/center->rect center width height)

        points-transform-mtx (transform-points-matrix sr points)

        ;; Calculate the transform by move the transformation to the center
        transform
        (when points-transform-mtx
          (gmt/multiply
           (gmt/translate-matrix (gpt/negate center))
           points-transform-mtx
           (gmt/translate-matrix center)))

        transform-inverse (when transform (gmt/inverse transform))

        ;; There is a rounding error when the matrix returned have float point values
        ;; when the matrix is unit we return a "pure" matrix so we don't accumulate
        ;; rounding problems
        [transform transform-inverse]
        (if (gmt/unit? transform)
          [(gmt/matrix) (gmt/matrix)]
          [transform transform-inverse])]

    [sr transform transform-inverse]))

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

        [selrect transform transform-inverse] (calculate-geometry points)

        base-rotation  (or (:rotation shape) 0)
        modif-rotation (or (get-in shape [:modifiers :rotation]) 0)
        rotation       (mod (+ base-rotation modif-rotation) 360)]

    (if-not (and transform transform-inverse)
      ;; When we cannot calculate the transformation we leave the shape as it was
      shape
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
          (assoc :rotation rotation)))))

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

(defn update-group-selrect
  [group children]
  (let [;; Points for every shape inside the group
        points (->> children (mapcat :points))

        shape-center (gco/points->center points)

        ;; Fixed problem with empty groups. Should not happen (but it does)
        points (if (empty? points) (:points group) points)

        ;; Invert to get the points minus the transforms applied to the group
        base-points (gco/transform-points points shape-center (:transform-inverse group (gmt/matrix)))

        ;; FIXME: looks redundant operation points -> rect -> points
        ;; Defines the new selection rect with its transformations
        new-points (-> (grc/points->rect base-points)
                       (grc/rect->points)
                       (gco/transform-points shape-center (:transform group (gmt/matrix))))

        ;; Calculate the new selrect
        new-selrect (grc/points->rect base-points)]

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
      (grc/rect->points)
      (transform-bounds modifiers)
      (grc/points->rect)))

(defn transform-selrect-matrix
  [selrect mtx]
  (-> selrect
      (grc/rect->points)
      (gco/transform-points mtx)
      (grc/points->rect)))


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

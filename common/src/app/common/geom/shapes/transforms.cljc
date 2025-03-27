;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.transforms
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.bool :as gshb]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]))

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
          dx (dm/get-prop pt :x)
          dy (dm/get-prop pt :y)]

      (grc/make-rect
       (if ^boolean (d/num? x) (+ dx x)  x)
       (if ^boolean (d/num? y)  (+ dy y)  y)
       w
       h))
    selrect))

(defn- move-points
  [points move-vec]
  (if (valid-point? move-vec)
    (mapv #(gpt/add % move-vec) points)
    points))

;; FIXME: deprecated
(defn move-position-data
  [position-data delta]
  (when (some? position-data)
    (let [dx (dm/get-prop delta :x)
          dy (dm/get-prop delta :y)]
      (if (d/num? dx dy)
        (mapv #(-> %
                   (update :x + dx)
                   (update :y + dy))
              position-data)
        position-data))))

(defn transform-position-data
  [position-data transform]
  (when (some? position-data)
    (let [dx (dm/get-prop transform :e)
          dy (dm/get-prop transform :f)]
      (if (d/num? dx dy)
        (mapv #(-> %
                   (update :x + dx)
                   (update :y + dy))
              position-data)
        position-data))))

;; FIXME: revist usage of mutability
(defn move
  "Move the shape relatively to its current
  position applying the provided delta."
  [shape point]
  (let [type (dm/get-prop shape :type)
        dx   (dm/get-prop point :x)
        dy   (dm/get-prop point :y)
        dx   (d/check-num dx 0)
        dy   (d/check-num dy 0)
        mvec (gpt/point dx dy)]

    (-> shape
        (update :selrect move-selrect mvec)
        (update :points move-points mvec)
        (d/update-when :x d/safe+ dx)
        (d/update-when :y d/safe+ dy)
        (d/update-when :position-data move-position-data mvec)
        (cond-> (or (= :bool type) (= :path type))
          (update :content gpa/move-content mvec)))))

;; --- Absolute Movement

(defn absolute-move
  "Move the shape to the exactly specified position."
  [shape pos]
  (when shape
    (let [x  (dm/get-prop pos :x)
          y  (dm/get-prop pos :y)
          sr (dm/get-prop shape :selrect)
          px (dm/get-prop sr :x)
          py (dm/get-prop sr :y)
          dx (- (d/check-num x) px)
          dy (- (d/check-num y) py)]
      (move shape (gpt/point dx dy)))))

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

(defn inverse-transform-matrix
  ([shape]
   (inverse-transform-matrix shape nil))

  ([shape params]
   (inverse-transform-matrix shape params (or (gco/shape->center shape) (gpt/point 0 0))))

  ([{:keys [flip-x flip-y transform-inverse] :as shape} {:keys [no-flip]} shape-center]
   (-> (gmt/matrix)
       (gmt/translate shape-center)

       (cond-> (and flip-x no-flip)
         (gmt/scale (gpt/point -1 1)))

       (cond-> (and flip-y no-flip)
         (gmt/scale (gpt/point 1 -1)))

       (cond-> (some? transform-inverse)
         (gmt/multiply transform-inverse))

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

;; FIXME: move to geom rect?
(defn transform-rect
  "Transform a rectangles and changes its attributes"
  [rect matrix]

  (let [points (-> (grc/rect->points rect)
                   (gco/transform-points matrix))]
    (grc/points->rect points)))

(defn transform-points-matrix
  [selrect [d1 d2 _ d4]]
  ;; If the coordinates are very close to zero (but not zero) the rounding can mess with the
  ;; transforms. So we round to zero the values
  (let [x1  (mth/round-to-zero (dm/get-prop selrect :x1))
        y1  (mth/round-to-zero (dm/get-prop selrect :y1))
        x2  (mth/round-to-zero (dm/get-prop selrect :x2))
        y2  (mth/round-to-zero (dm/get-prop selrect :y2))

        det (+ (- (* (- y1 y2) x1)
                  (* (- y1 y2) x2))
               (* (- y1 y1) x1))]

    (when-not (zero? det)
      (let [ma0 (mth/round-to-zero (dm/get-prop d1 :x))
            ma1 (mth/round-to-zero (dm/get-prop d2 :x))
            ma2 (mth/round-to-zero (dm/get-prop d4 :x))
            ma3 (mth/round-to-zero (dm/get-prop d1 :y))
            ma4 (mth/round-to-zero (dm/get-prop d2 :y))
            ma5 (mth/round-to-zero (dm/get-prop d4 :y))

            mb0 (/ (- y1 y2) det)
            mb1 (/ (- x1 x2) det)
            mb2 (/ (- (* x2 y2) (* x1 y1)) det)
            mb3 (/ (- y2 y1) det)
            mb4 (/ (- x1 x1) det)
            mb5 (/ (- (* x1 y1) (* x1 y2)) det)
            mb6 (/ (- y1 y1) det)
            mb7 (/ (- x2 x1) det)
            mb8 (/ (- (* x1 y1) (* x2 y1)) det)]

        (gmt/matrix (+ (* ma0 mb0)
                       (* ma1 mb3)
                       (* ma2 mb6))
                    (+ (* ma3 mb0)
                       (* ma4 mb3)
                       (* ma5 mb6))
                    (+ (* ma0 mb1)
                       (* ma1 mb4)
                       (* ma2 mb7))
                    (+ (* ma3 mb1)
                       (* ma4 mb4)
                       (* ma5 mb7))
                    (+ (* ma0 mb2)
                       (* ma1 mb5)
                       (* ma2 mb8))
                    (+ (* ma3 mb2)
                       (* ma4 mb5)
                       (* ma5 mb8)))))))

(defn calculate-selrect
  [points center]

  (let [p1     (nth points 0)
        p2     (nth points 1)
        p4     (nth points 3)

        width  (mth/hypot
                (- (dm/get-prop p2 :x)
                   (dm/get-prop p1 :x))
                (- (dm/get-prop p2 :y)
                   (dm/get-prop p1 :y)))

        height (mth/hypot
                (- (dm/get-prop p1 :x)
                   (dm/get-prop p4 :x))
                (- (dm/get-prop p1 :y)
                   (dm/get-prop p4 :y)))]

    (grc/center->rect center width height)))

(defn calculate-transform
  [points center selrect]
  (let [transform (transform-points-matrix selrect points)

        ;; Calculate the transform by move the transformation to the center
        transform
        (when (some? transform)
          (-> (gmt/translate-matrix-neg center)
              (gmt/multiply! transform)
              (gmt/multiply! (gmt/translate-matrix center))))]

    ;; There is a rounding error when the matrix returned have float point values
    ;; when the matrix is unit we return a "pure" matrix so we don't accumulate
    ;; rounding problems
    (when ^boolean (gmt/matrix? transform)
      (if ^boolean (gmt/unit? transform)
        gmt/base
        transform))))

(defn calculate-geometry
  [points]
  (let [center     (gco/points->center points)
        selrect    (calculate-selrect points center)
        transform  (calculate-transform points center selrect)]
    [selrect transform (when (some? transform) (gmt/inverse transform))]))

(defn- adjust-shape-flips
  "After some tranformations the flip-x/flip-y flags can change we need
  to check this before adjusting the selrect"
  [shape points]
  (let [points' (dm/get-prop shape :points)
        p0'     (nth points' 0)
        p0      (nth points 0)

        ;; FIXME: unroll and remove point allocation here
        xv1     (gpt/to-vec p0' (nth points' 1))
        xv2     (gpt/to-vec p0  (nth points 1))
        dot-x   (gpt/dot xv1 xv2)

        yv1     (gpt/to-vec p0' (nth points' 3))
        yv2     (gpt/to-vec p0  (nth points 3))
        dot-y   (gpt/dot yv1 yv2)]

    (cond-> shape
      (neg? dot-x)
      (update :flip-x not)

      (neg? dot-x)
      (update :rotation -)

      (neg? dot-y)
      (update :flip-y not)

      (neg? dot-y)
      (update :rotation -))))

(defn- apply-transform-move
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx]
  (let [type    (dm/get-prop shape :type)
        points  (gco/transform-points  (dm/get-prop shape :points) transform-mtx)
        selrect (gco/transform-selrect (dm/get-prop shape :selrect) transform-mtx)

        shape   (if (= type :text)
                  (update shape :position-data transform-position-data transform-mtx)
                  shape)
        shape   (if (or (= type :path) (= type :bool))
                  (update shape :content gpa/transform-content transform-mtx)
                  (assoc shape
                         :x (dm/get-prop selrect :x)
                         :y (dm/get-prop selrect :y)
                         :width (dm/get-prop selrect :width)
                         :height (dm/get-prop selrect :height)))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))


(defn- apply-transform-generic
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx]
  (let [points    (-> (dm/get-prop shape :points)
                      (gco/transform-points transform-mtx))

        shape     (adjust-shape-flips shape points)

        center    (gco/points->center points)
        selrect   (calculate-selrect points center)
        transform (calculate-transform points center selrect)
        inverse   (when (some? transform) (gmt/inverse transform))]

    (if-not (and (some? inverse) (some? transform))
      shape
      (let [type     (dm/get-prop shape :type)
            rotation (mod (+ (d/nilv (:rotation shape) 0)
                             (d/nilv (dm/get-in shape [:modifiers :rotation]) 0))
                          360)

            shape    (if (or (= type :path) (= type :bool))
                       (update shape :content gpa/transform-content transform-mtx)
                       (assoc shape
                              :x (dm/get-prop selrect :x)
                              :y (dm/get-prop selrect :y)
                              :width (dm/get-prop selrect :width)
                              :height (dm/get-prop selrect :height)))]
        (-> shape
            (assoc :transform transform)
            (assoc :transform-inverse inverse)
            (assoc :selrect selrect)
            (assoc :points points)
            (assoc :rotation rotation))))))

(defn apply-transform
  "Given a new set of points transformed, set up the rectangle so it keeps
  its properties. We adjust de x,y,width,height and create a custom transform"
  [shape transform-mtx]
  (if ^boolean (gmt/move? transform-mtx)
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
        sr-transform (gmt/transform-in (gco/points->center new-points) (:transform-inverse group (gmt/matrix)))
        new-selrect
        (-> new-points
            (gco/transform-points sr-transform)
            (grc/points->rect))]

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

  (let [content
        (gshb/calc-bool-content shape objects)

        shape
        (assoc shape :content content)

        [points selrect]
        (gpa/content->points+selrect shape content)]

    (if (and (some? selrect) (d/not-empty? points))
      (-> shape
          (assoc :selrect selrect)
          (assoc :points points))
      (update-group-selrect shape children))))

(defn update-shapes-geometry
  [objects ids]
  (->> ids
       (reduce
        (fn [objects id]
          (let [shape (get objects id)
                children (cfh/get-immediate-children objects id)
                shape
                (cond
                  (cfh/mask-shape? shape)
                  (update-mask-selrect shape children)

                  (cfh/bool-shape? shape)
                  (update-bool-selrect shape children objects)

                  (cfh/group-shape? shape)
                  (update-group-selrect shape children)

                  :else
                  shape)]
            (assoc objects id shape)))
        objects)))

(defn transform-shape
  ([shape]
   (let [modifiers (:modifiers shape)]
     (-> shape
         (dissoc :modifiers)
         (transform-shape modifiers))))

  ([shape modifiers]
   (if (and (some? shape) (some? modifiers) (not (ctm/empty? modifiers)))
     (let [transform (ctm/modifiers->transform modifiers)]
       (cond-> shape
         (and (some? transform)
              (not (cfh/root? shape)))
         (apply-transform transform)

         (ctm/has-structure? modifiers)
         (ctm/apply-structure-modifiers modifiers)))
     shape)))

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
                    parent?   (cfh/group-like-shape? child)

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
       (cfh/mask-shape? group)
       (update-mask-selrect group children)

       (cfh/bool-shape? group)
       (transform-shape group modifiers)

       (cfh/group-shape? group)
       (update-group-selrect group children)

       :else
       group))))

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
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.path :as gpa]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.data :as d]))

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
  (let [shape-center (gco/center shape)]
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


(defn transform-shape-point
  "Transform a point around the shape center"
  [point shape transform]
  (let [shape-center (gco/center shape)]
    (gpt/transform
     point
     (-> (gmt/multiply
          (gmt/translate-matrix shape-center)
          transform
          (gmt/translate-matrix (gpt/negate shape-center)))))))

(defn shape->points [shape]
  (let [points (case (:type shape)
                 (:curve :path) (if (:content shape)
                                  (gpa/content->points (:content shape))
                                  (:segments shape))
                 (let [{:keys [x y width height]} shape]
                   [(gpt/point x y)
                    (gpt/point (+ x width) y)
                    (gpt/point (+ x width) (+ y height))
                    (gpt/point x (+ y height))]))]
    (->> points
         (map #(transform-shape-point % shape (:transform shape (gmt/matrix))))
         (map gpt/round)
         (vec))))

(defn rect-path-dimensions [rect-path]
  (let [seg (:segments rect-path)
        [width height] (mapv (fn [[c1 c2]] (gpt/distance c1 c2)) (take 2 (d/zip seg (rest seg))))]
    {:width width
     :height height}))

(defn update-path-selrect [shape]
  (as-> shape $
    (assoc $ :points (shape->points $))
    (assoc $ :selrect (gpr/points->selrect (:points $)))
    (assoc $ :x (get-in $ [:selrect :x]))
    (assoc $ :y (get-in $ [:selrect :y]))
    (assoc $ :width (get-in $ [:selrect :width]))
    (assoc $ :height (get-in $ [:selrect :height]))))

(defn fix-invalid-rect-values
  [rect-shape]
  (letfn [(check [num]
            (if (or (nil? num) (mth/nan? num) (= ##Inf num) (= ##-Inf num)) 0 num))
          (to-positive [num] (if (< num 1) 1 num))]
    (-> rect-shape
        (update :x check)
        (update :y check)
        (update :width (comp to-positive check))
        (update :height (comp to-positive check)))))

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
        v1 (gpt/to-vec (gco/center path-shape1) p1)
        v2 (gpt/to-vec (gco/center path-shape2) p2)

        rot-angle (gpt/angle-with-other v1 v2)
        rot-sign (if (> (* (:y v1) (:x v2)) (* (:x v1) (:y v2))) -1 1)]
    (* rot-sign rot-angle)))


(defn transform-apply-modifiers
  [shape]
  (let [modifiers (:modifiers shape)
        ds-modifier (:displacement modifiers (gmt/matrix))
        {res-x :x res-y :y} (:resize-vector modifiers (gpt/point 1 1))

        ;; Normalize x/y vector coordinates because scale by 0 is infinite
        res-x (cond
                (and (< res-x 0) (> res-x -0.01)) -0.01
                (and (>= res-x 0) (< res-x 0.01)) 0.01
                :else res-x)

        res-y (cond
                (and (< res-y 0) (> res-y -0.01)) -0.01
                (and (>= res-y 0) (< res-y 0.01)) 0.01
                :else res-y)

        resize (gpt/point res-x res-y)

        origin (:resize-origin modifiers (gpt/point 0 0))

        resize-transform (:resize-transform modifiers (gmt/matrix))
        resize-transform-inverse (:resize-transform-inverse modifiers (gmt/matrix))
        rt-modif (or (:rotation modifiers) 0)

        shape (-> shape
                  (transform ds-modifier))

        shape-center (gco/center shape)]

    (-> (gpr/shape->path shape)
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

(defn transform-path-shape
  [shape]
  (-> shape
      transform-apply-modifiers
      update-path-selrect)
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

(defn transform-rect-shape
  [shape]
  (let [;; Apply modifiers to the rect as a path so we have the end shape expected
        shape-path    (transform-apply-modifiers shape)
        shape-center  (gco/center shape-path)
        resize-vector (-> (get-in shape [:modifiers :resize-vector] (gpt/point 1 1))
                          (update :x #(if (zero? %) 1 %))
                          (update :y #(if (zero? %) 1 %)))

        ;; Reverse the current transformation stack to get the base rectangle
        shape-path-temp (center-transform shape-path (:transform-inverse shape (gmt/matrix)))
        shape-path-temp-dim (rect-path-dimensions shape-path-temp)
        shape-path-temp-rec (gpr/shape->rect-shape shape-path-temp)

        ;; This rectangle is the new data for the current rectangle. We want to change our rectangle
        ;; to have this width, height, x, y
        rec (gco/center->rect shape-center (:width shape-path-temp-dim) (:height shape-path-temp-dim))
        rec (fix-invalid-rect-values rec)
        rec-path (gpr/rect->path rec)

        ;; The next matrix is a series of transformations we have to do to the previous rec so that
        ;; after applying them the end result is the `shape-path-temp`
        ;; This is compose of three transformations: skew, resize and rotation
        stretch-matrix (gmt/matrix)

        skew-angle (calculate-rec-path-skew-angle shape-path-temp)

        ;; When one of the axis is flipped we have to reverse the skew
        skew-angle (if (neg? (* (:x resize-vector) (:y resize-vector))) (- skew-angle) skew-angle )
        skew-angle (if (mth/nan? skew-angle) 0 skew-angle)


        stretch-matrix (gmt/multiply stretch-matrix (gmt/skew-matrix skew-angle 0))

        h1 (calculate-rec-path-height shape-path-temp)
        h2 (calculate-rec-path-height (center-transform rec-path stretch-matrix))
        h3 (/ h1 h2)
        h3 (if (mth/nan? h3) 1 h3)

        stretch-matrix (gmt/multiply stretch-matrix (gmt/scale-matrix (gpt/point 1 h3)))

        rotation-angle (calculate-rec-path-rotation (center-transform rec-path stretch-matrix)
                                                    shape-path-temp resize-vector)

        stretch-matrix (gmt/multiply (gmt/rotate-matrix rotation-angle) stretch-matrix)

        ;; This is the inverse to be able to remove the transformation
        stretch-matrix-inverse (-> (gmt/matrix)
                                   (gmt/scale (gpt/point 1 h3))
                                   (gmt/skew (- skew-angle) 0)
                                   (gmt/rotate (- rotation-angle)))

        new-shape (as-> shape $
                    (merge  $ rec)
                    (update $ :x #(mth/precision % 0))
                    (update $ :y #(mth/precision % 0))
                    (update $ :width #(mth/precision % 0))
                    (update $ :height #(mth/precision % 0))
                    (update $ :transform #(gmt/multiply (or % (gmt/matrix)) stretch-matrix))
                    (update $ :transform-inverse #(gmt/multiply stretch-matrix-inverse (or % (gmt/matrix))))
                    (assoc  $ :points (shape->points $))
                    (assoc  $ :selrect (gpr/points->selrect (:points $)))
                    (update $ :selrect fix-invalid-rect-values)
                    (update $ :rotation #(mod (+ (or % 0)
                                                 (or (get-in $ [:modifiers :rotation]) 0)) 360)))]
    new-shape))

(defn transform-shape
  "Transform the shape properties given the modifiers"
  ([shape]
   
   (letfn [(transform-by-type [shape]
             (case (:type shape)
               (:curve :path)
               (transform-path-shape shape)

               #_:default
               (transform-rect-shape shape)))]
     
     (cond-> shape
       (:modifiers shape) (transform-by-type)
       :always            (dissoc :modifiers)))

   #_(cond-> shape
             (and (:modifiers shape) (#{:curve :path} (:type shape)))
             (transform-path-shape shape)
             
             (and (:modifiers shape) (not (#{:curve :path} (:type shape))))
             (transform-rect-shape shape)

             true
             (dissoc :modifiers)
             ))
  #_([frame shape kk]

   

   
   #_(if (:modifiers shape)
     (-> (case (:type shape)
           (:curve :path) (transform-path-shape shape)
           (transform-rect-shape shape))
         (dissoc :modifiers))
     shape)
   #_(let [new-shape
         ]
     
     #_(cond-> new-shape
       frame (translate-to-frame frame)))))


(defn transform-matrix
  "Returns a transformation matrix without changing the shape properties.
  The result should be used in a `transform` attribute in svg"
  ([{:keys [x y] :as shape}]
   (let [shape-center (gco/center shape)]
     (-> (gmt/matrix)
         (gmt/translate shape-center)
         (gmt/multiply (:transform shape (gmt/matrix)))
         (gmt/translate (gpt/negate shape-center))))))

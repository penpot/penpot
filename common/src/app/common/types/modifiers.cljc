;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.modifiers
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.spec :as us]))

;; --- Modifiers

;; The `modifiers` structure contains a list of transformations to
;; do make to a shape, in this order:
;;
;; - resize-origin (gpt/point) + resize-vector (gpt/point)q
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

(defn move
  ([x y]
   (move (gpt/point x y)))

  ([vector]
   {:v2 [{:type :move :vector vector}]}))

(defn resize
  [vector origin]
  {:v2 [{:type :resize :vector vector :origin origin}]})

(defn add-move
  ([object x y]
   (add-move object (gpt/point x y)))

  ([object vector]
   (assoc-in
    object
    [:modifiers :displacement]
    (gmt/translate-matrix (:x vector) (:y vector)))))

(defn add-resize
  [object vector origin]
  (-> object
      (assoc-in [:modifiers :resize-vector] vector)
      (assoc-in [:modifiers :resize-origin] origin)))

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

        shape-transform (:transform shape)
        shape-transform-inv (:transform-inverse shape)
        shape-center (gco/center-shape shape)
        {sr-width :width sr-height :height} (:selrect shape)

        origin (cond-> (gpt/point (:selrect shape))
                 (some? shape-transform)
                 (gmt/transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point width height)
                           (gpt/point sr-width sr-height))]
    {:resize-vector scalev
     :resize-origin origin
     :resize-transform shape-transform
     :resize-transform-inverse shape-transform-inv}))

(defn change-orientation-modifiers
  [shape orientation]
  (us/assert map? shape)
  (us/verify #{:horiz :vert} orientation)
  (let [width (:width shape)
        height (:height shape)
        new-width (if (= orientation :horiz) (max width height) (min width height))
        new-height (if (= orientation :horiz) (min width height) (max width height))

        shape-transform (:transform shape)
        shape-transform-inv (:transform-inverse shape)
        shape-center (gco/center-shape shape)
        {sr-width :width sr-height :height} (:selrect shape)

        origin (cond-> (gpt/point (:selrect shape))
                 (some? shape-transform)
                 (gmt/transform-point-center shape-center shape-transform))

        scalev (gpt/divide (gpt/point new-width new-height)
                           (gpt/point sr-width sr-height))]
    {:resize-vector scalev
     :resize-origin origin
     :resize-transform shape-transform
     :resize-transform-inverse shape-transform-inv}))

(defn rotation-modifiers
  [shape center angle]
  (let [shape-center (gco/center-shape shape)
        rotation (-> (gmt/matrix)
                     (gmt/rotate angle center)
                     (gmt/rotate (- angle) shape-center))]

    {:v2 [{:type :rotation
           :center shape-center
           :rotation angle}

          {:type :move
           :vector (gpt/transform (gpt/point 1 1) rotation)}]}
    #_{:rotation angle
     :displacement displacement}))

(defn merge-modifiers
  [objects modifiers]

  (let [set-modifier
        (fn [objects [id modifiers]]
          (-> objects
              (d/update-when id merge modifiers)))]
    (->> modifiers
         (reduce set-modifier objects))))

(defn modifiers-v2->transform
  [modifiers]
  (letfn [(apply-modifier [matrix {:keys [type vector rotation center origin transform transform-inverse] :as modifier}]
            (case type
              :move
              (gmt/multiply (gmt/translate-matrix vector) matrix)

              ;;:transform
              ;;(gmt/multiply transform matrix)

              :resize
              (gmt/multiply
               (-> (gmt/matrix)
                   (gmt/translate origin)
                   (cond-> (some? transform)
                     (gmt/multiply transform))
                   (gmt/scale vector)
                   (cond-> (some? transform-inverse)
                     (gmt/multiply transform-inverse))
                   (gmt/translate (gpt/negate origin)))
               matrix)

              :rotation
              ;; TODO LAYOUT: Comprobar que pasa si no hay centro
              (gmt/multiply
               (-> (gmt/matrix)
                   (gmt/translate center)
                   (gmt/multiply (gmt/rotate-matrix rotation))
                   (gmt/translate (gpt/negate center)))
               matrix)))]
    (->> modifiers
         (reduce apply-modifier (gmt/matrix)))))

(defn- normalize-scale
  "We normalize the scale so it's not too close to 0"
  [scale]
  (cond
    (and (<  scale 0) (> scale -0.01)) -0.01
    (and (>= scale 0) (< scale  0.01))  0.01
    :else scale))

(defn modifiers->transform
  ([modifiers]
   (modifiers->transform nil modifiers))

  ([center modifiers]
   (if (some? (:v2 modifiers))
     (modifiers-v2->transform (:v2 modifiers))
     (let [displacement (:displacement modifiers)
           displacement-after (:displacement-after modifiers)
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

           resize-transform (:resize-transform modifiers)
           resize-transform-inverse (:resize-transform-inverse modifiers)

           rt-modif (:rotation modifiers)]

       (cond-> (gmt/matrix)
         (some? displacement-after)
         (gmt/multiply displacement-after)

         (some? resize-1)
         (-> (gmt/translate origin-1)
             (cond-> (some? resize-transform)
               (gmt/multiply resize-transform))
             (gmt/scale resize-1)
             (cond-> (some? resize-transform-inverse)
               (gmt/multiply resize-transform-inverse))
             (gmt/translate (gpt/negate origin-1)))

         (some? resize-2)
         (-> (gmt/translate origin-2)
             (cond-> (some? resize-transform)
               (gmt/multiply resize-transform))
             (gmt/scale resize-2)
             (cond-> (some? resize-transform-inverse)
               (gmt/multiply resize-transform-inverse))
             (gmt/translate (gpt/negate origin-2)))

         (some? displacement)
         (gmt/multiply displacement)

         (some? rt-modif)
         (-> (gmt/translate center)
             (gmt/multiply (gmt/rotate-matrix rt-modif))
             (gmt/translate (gpt/negate center))))))
   ))

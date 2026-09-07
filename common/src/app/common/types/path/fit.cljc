;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.path.fit
  "Curve fitting helpers."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.path.helpers :as helpers]))

(defn- chord-length-params
  "Returns normalized chord-length parameters for `points`."
  [points]
  (let [dists (->> (map gpt/distance points (rest points))
                   (reductions + 0)
                   (vec))
        total (peek dists)]
    (if (mth/almost-zero? total)
      (let [n (max 1 (dec (count points)))]
        (mapv #(/ (double %) n) (range (count points))))
      (mapv #(/ % total) dists))))

(defn fit-cubic
  "Fits one cubic through `points` with fixed endpoints and tangents."
  ([points tan1 tan2]
   (let [points (vec points)]
     (fit-cubic points (chord-length-params points) tan1 tan2)))
  ([points params tan1 tan2]
   (let [points (vec points)
         p0     (first points)
         p3     (peek points)

         [c00 c01 c11 x0 x1]
         (reduce
          (fn [[c00 c01 c11 x0 x1] [point u]]
            (let [u'  (- 1.0 u)
                  b0  (* u' u' u')
                  b1  (* 3.0 u u' u')
                  b2  (* 3.0 u u u')
                  b3  (* u u u)
                  a1  (gpt/scale tan1 b1)
                  a2  (gpt/scale tan2 b2)
                  tmp (-> point
                          (gpt/subtract (gpt/scale p0 (+ b0 b1)))
                          (gpt/subtract (gpt/scale p3 (+ b2 b3))))]
              [(+ c00 (gpt/dot a1 a1))
               (+ c01 (gpt/dot a1 a2))
               (+ c11 (gpt/dot a2 a2))
               (+ x0 (gpt/dot a1 tmp))
               (+ x1 (gpt/dot a2 tmp))]))
          [0.0 0.0 0.0 0.0 0.0]
          (map vector points params))

         det-c  (- (* c00 c11) (* c01 c01))
         alpha1 (when-not (mth/almost-zero? det-c)
                  (/ (- (* x0 c11) (* x1 c01)) det-c))
         alpha2 (when-not (mth/almost-zero? det-c)
                  (/ (- (* c00 x1) (* c01 x0)) det-c))

         chord   (gpt/distance p0 p3)
         epsilon (* 0.000001 chord)

         [alpha1 alpha2]
         (if (or (nil? alpha1) (nil? alpha2)
                 (< alpha1 epsilon) (< alpha2 epsilon))
           [(/ chord 3.0) (/ chord 3.0)]
           [alpha1 alpha2])]

     [(gpt/add p0 (gpt/scale tan1 alpha1))
      (gpt/add p3 (gpt/scale tan2 alpha2))])))

(defn- curve-d1
  "Returns the first derivative at `t`."
  [[start end h1 h2] t]
  (let [t' (- 1.0 t)
        a  (* 3.0 t' t')
        b  (* 6.0 t' t)
        c  (* 3.0 t t)]
    (gpt/point (+ (* a (- (:x h1) (:x start)))
                  (* b (- (:x h2) (:x h1)))
                  (* c (- (:x end) (:x h2))))
               (+ (* a (- (:y h1) (:y start)))
                  (* b (- (:y h2) (:y h1)))
                  (* c (- (:y end) (:y h2)))))))

(defn- curve-d2
  "Returns the second derivative at `t`."
  [[start end h1 h2] t]
  (let [t' (- 1.0 t)]
    (gpt/point (+ (* 6.0 t' (+ (:x h2) (* -2.0 (:x h1)) (:x start)))
                  (* 6.0 t  (+ (:x end) (* -2.0 (:x h2)) (:x h1))))
               (+ (* 6.0 t' (+ (:y h2) (* -2.0 (:y h1)) (:y start)))
                  (* 6.0 t  (+ (:y end) (* -2.0 (:y h2)) (:y h1)))))))

(defn- refine-parameter
  "Moves `u` toward the closest point on `curve`."
  [curve point u]
  (let [d   (gpt/subtract (helpers/curve-values curve u) point)
        d1  (curve-d1 curve u)
        d2  (curve-d2 curve u)
        den (+ (gpt/dot d1 d1) (gpt/dot d d2))]
    (if (mth/almost-zero? den)
      u
      (mth/clamp (- u (/ (gpt/dot d d1) den)) 0.0 1.0))))

(defn- max-fit-error
  "Returns the largest interior fit error and its index."
  [points params curve]
  (let [n (count points)]
    (loop [i       1
           max-err 0.0
           split   (quot n 2)]
      (if (>= i (dec n))
        [max-err split]
        (let [d   (gpt/subtract (helpers/curve-values curve (nth params i))
                                (nth points i))
              err (gpt/dot d d)]
          (if (> err max-err)
            (recur (inc i) err i)
            (recur (inc i) max-err split)))))))

(def ^:private ^:const max-fit-iterations 4)

(defn- fit-curve*
  "Fits one or more curves through at least two points."
  [points tan1 tan2 tol2]
  (let [n  (count points)
        p0 (first points)
        p3 (peek points)]
    (if (= n 2)
      (let [alpha (/ (gpt/distance p0 p3) 3.0)]
        [[p0 p3
          (gpt/add p0 (gpt/scale tan1 alpha))
          (gpt/add p3 (gpt/scale tan2 alpha))]])

      (let [params      (chord-length-params points)
            [h1 h2]     (fit-cubic points params tan1 tan2)
            curve       [p0 p3 h1 h2]
            [err split] (max-fit-error points params curve)

            [curve err split]
            (if (and (> err tol2) (<= err (* 16.0 tol2)))
              (loop [it     0
                     params params
                     curve  curve
                     err    err
                     split  split]
                (if (or (>= it max-fit-iterations) (<= err tol2))
                  [curve err split]
                  (let [params      (mapv #(refine-parameter curve %1 %2) points params)
                        [h1 h2]     (fit-cubic points params tan1 tan2)
                        curve       [p0 p3 h1 h2]
                        [err split] (max-fit-error points params curve)]
                    (recur (inc it) params curve err split))))
              [curve err split])]

        (if (<= err tol2)
          [curve]
          (let [split  (mth/clamp split 1 (- n 2))
                center (let [v (gpt/to-vec (nth points (inc split))
                                           (nth points (dec split)))]
                         (if (mth/almost-zero? (gpt/length v))
                           (gpt/unit (gpt/to-vec (nth points split)
                                                 (nth points (dec split))))
                           (gpt/unit v)))]
            (into (fit-curve* (subvec points 0 (inc split)) tan1 center tol2)
                  (fit-curve* (subvec points split) (gpt/negate center) tan2 tol2))))))))

(def ^:private default-corner-angle 60.0)

(defn- corner-index?
  "True when point `i` turns more than `corner-angle` degrees."
  [points i corner-angle]
  (let [v-in  (gpt/to-vec (nth points (dec i)) (nth points i))
        v-out (gpt/to-vec (nth points i) (nth points (inc i)))]
    (and (not (mth/almost-zero? (gpt/length v-in)))
         (not (mth/almost-zero? (gpt/length v-out)))
         (> (gpt/angle-with-other v-in v-out) corner-angle))))

(defn fit-curve
  "Fits chained cubic curves through `points` within `tolerance`."
  ([points tolerance]
   (fit-curve points tolerance default-corner-angle))
  ([points tolerance corner-angle]
   (let [points (reduce (fn [acc point]
                          (if (and (seq acc)
                                   (< (gpt/distance (peek acc) point) 0.01))
                            acc
                            (conj acc point)))
                        []
                        points)
         n      (count points)]
     (when (>= n 2)
       (let [tol2    (* (double tolerance) (double tolerance))
             corners (into [] (filter #(corner-index? points % corner-angle))
                           (range 1 (dec n)))
             bounds  (concat [0] corners [(dec n)])]
         (into []
               (mapcat (fn [[a b]]
                         (let [span (subvec points a (inc b))
                               m    (count span)]
                           (when (>= m 2)
                             (let [tan1 (gpt/unit (gpt/to-vec (nth span 0) (nth span 1)))
                                   tan2 (gpt/unit (gpt/to-vec (nth span (dec m)) (nth span (- m 2))))]
                               (fit-curve* span tan1 tan2 tol2))))))
               (partition 2 1 bounds)))))))

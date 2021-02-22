;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.matrix
  (:require
   #?(:cljs [cljs.pprint :as pp]
      :clj  [clojure.pprint :as pp])
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.geom.point :as gpt]))

;; --- Matrix Impl

(defrecord Matrix [a b c d e f]
  Object
  (toString [_]
    (str "matrix(" a "," b "," c "," d "," e "," f ")")))

(defonce matrix-regex #"matrix\((.*),(.*),(.*),(.*),(.*),(.*)\)")

(defn matrix
  "Create a new matrix instance."
  ([]
   (Matrix. 1 0 0 1 0 0))
  ([a b c d e f]
   (Matrix. a b c d e f)))

(defn parse-matrix [mtx]
  (let [[_ a b c d e f] (re-matches matrix-regex mtx)]
    (->> [a b c d e f]
         (map str/trim)
         (map d/parse-double)
         (apply matrix))))

(defn multiply
  ([{m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f}
    {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f}]
   (Matrix.
    (+ (* m1a m2a) (* m1c m2b))
    (+ (* m1b m2a) (* m1d m2b))
    (+ (* m1a m2c) (* m1c m2d))
    (+ (* m1b m2c) (* m1d m2d))
    (+ (* m1a m2e) (* m1c m2f) m1e)
    (+ (* m1b m2e) (* m1d m2f) m1f)))
  ([m1 m2 & others]
   (reduce multiply (multiply m1 m2) others)))

(defn substract
  [{m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f}
   {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f}]
  (Matrix.
   (- m1a m2a) (- m1b m2b) (- m1c m2c)
   (- m1d m2d) (- m1e m2e) (- m1f m2f)))

(defn ^boolean matrix?
  "Return true if `v` is Matrix instance."
  [v]
  (instance? Matrix v))



(def base (matrix))

(defn base?
  [v]
  (= v base))

(defn translate-matrix
  [{x :x y :y :as pt}]
  (assert (gpt/point? pt))
  (Matrix. 1 0 0 1 x y))

(defn scale-matrix
  ([pt center]
   (multiply (translate-matrix center)
             (scale-matrix pt)
             (translate-matrix (gpt/negate center))))
  ([{x :x y :y :as pt}]
   (assert (gpt/point? pt))
   (Matrix. x 0 0 y 0 0)))

(defn rotate-matrix
  ([angle point] (multiply (translate-matrix point)
                           (rotate-matrix angle)
                           (translate-matrix (gpt/negate point))))
  ([angle]
   (let [a (mth/radians angle)]
     (Matrix. (mth/cos a)
              (mth/sin a)
              (- (mth/sin a))
              (mth/cos a)
              0
              0))))

(defn skew-matrix
  ([angle-x angle-y point]
   (multiply (translate-matrix point)
             (skew-matrix angle-x angle-y)
             (translate-matrix (gpt/negate point))))
  ([angle-x angle-y]
   (let [m1 (mth/tan (mth/radians angle-x))
         m2 (mth/tan (mth/radians angle-y))]
     (Matrix. 1 m2 m1 1 0 0))))

(defn rotate
  "Apply rotation transformation to the matrix."
  ([m angle]
   (multiply m (rotate-matrix angle)))
  ([m angle center]
   (multiply m (rotate-matrix angle center))))

(defn scale
  "Apply scale transformation to the matrix."
  ([m scale]
   (multiply m (scale-matrix scale)))
  ([m scale center]
   (multiply m (scale-matrix scale center))))

(defn translate
  "Apply translate transformation to the matrix."
  [m pt]
  (multiply m (translate-matrix pt)))

(defn skew
  "Apply translate transformation to the matrix."
  ([m angle-x angle-y]
   (multiply m (skew-matrix angle-x angle-y)))
  ([m angle-x angle-y p]
   (multiply m (skew-matrix angle-x angle-y p))))

(defn m-equal [m1 m2 threshold]
  (let [th-eq (fn [a b] (<= (mth/abs (- a b)) threshold))
        {m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f} m1
        {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f} m2]
    (and (th-eq m1a m2a)
         (th-eq m1b m2b)
         (th-eq m1c m2c)
         (th-eq m1d m2d)
         (th-eq m1e m2e)
         (th-eq m1f m2f))))

(defmethod pp/simple-dispatch Matrix [obj] (pr obj))

(defn transform-in [pt mtx]
  (-> (matrix)
      (translate pt)
      (multiply mtx)
      (translate (gpt/negate pt))))

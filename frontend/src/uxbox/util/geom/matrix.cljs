;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.geom.matrix
  (:require [cuerdas.core :as str]
            [cognitect.transit :as t]
            [uxbox.util.math :as mth]
            [uxbox.util.geom.point :as gpt]))

;; --- Matrix Impl

(defrecord Matrix [a b c d tx ty])

(defprotocol ICoerce
  "Matrix coersion protocol."
  (-matrix [v] "Return a matrix instance."))

(extend-type Matrix
  cljs.core/IDeref
  (-deref [v]
    (mapv #(get v %) [:a :c :b :d :tx :ty]))

  Object
  (toString [v]
    (->> (str/join "," @v)
         (str/format "matrix(%s)"))))

(extend-protocol ICoerce
  nil
  (-matrix [_]
    (Matrix. 1 0 0 1 0 0))

  Matrix
  (-matrix [v] v)

  cljs.core/PersistentVector
  (-matrix [v]
    (let [[a b c d tx ty] v]
      (Matrix. a b c d tx ty)))

  cljs.core/IndexedSeq
  (-matrix [v]
    (let [[a b c d tx ty] v]
      (Matrix. a b c d tx ty))))

(defn multiply
  ([m om]
   (let [a1 (:a m)
         b1 (:b m)
         c1 (:c m)
         d1 (:d m)
         a2 (:a om)
         b2 (:b om)
         c2 (:c om)
         d2 (:d om)
         tx1 (:tx m)
         ty1 (:ty m)
         tx2 (:tx om)
         ty2 (:ty om)]
     (Matrix.
      (+ (* a2 a1) (* c2 c1))
      (+ (* a2 b1) (* c2 d1))
      (+ (* b2 a1) (* d2 c1))
      (+ (* b2 b1) (* d2 d1))
      (+ tx1 (* tx2 a1) (* ty2 c1))
      (+ ty1 (* tx2 b1) (* ty2 d1)))))
  ([m om & others]
   (reduce multiply (multiply m om) others)))

(defn ^boolean matrix?
  "Return true if `v` is Matrix instance."
  [v]
  (instance? Matrix v))

(defn matrix
  "Create a new matrix instance."
  ([]
   (Matrix. 1 0 0 1 0 0))
  ([v]
   (-matrix v))
  ([a b c d tx ty]
   (Matrix. a b c d tx ty)))

(defn translate-matrix
  ([pt]
   (let [pt (gpt/point pt)]
     (Matrix. 1 0 0 1 (:x pt) (:y pt))))
  ([x y]
   (translate-matrix (gpt/point x y))))

(defn scale-matrix
  ([s]
   (Matrix. s 0 0 s 0 0))
  ([sx sy]
   (Matrix. sx 0 0 sy 0 0)))

(defn rotate-matrix
  [a]
  (let [a (mth/radians a)]
    (Matrix. (mth/cos a)
             (mth/sin a)
             (- (mth/sin a))
             (mth/cos a)
             0 0)))

(defn rotate
  "Apply rotation transformation to the matrix."
  ([m angle]
   (multiply m (rotate-matrix angle)))
  ([m angle center]
   (multiply m
           (translate-matrix center)
           (rotate-matrix angle)
           (translate-matrix (gpt/negate center)))))

(defn rotate*
  ([m angle]
   (let [center (gpt/point 0 0)]
     (rotate m angle center)))
  ([m angle center]
   (let [angle (mth/radians angle)
         x (:x center)
         y (:y center)
         cos (mth/cos angle)
         sin (mth/sin angle)
         nsin (- sin)
         tx (- x (+ (* x cos)) (* y sin))
         ty (- y (- (* x sin)) (* y cos))
         a (+ (* cos (:a m)) (* sin (:c m)))
         b (+ (* cos (:b m)) (* sin (:d m)))
         c (+ (* nsin (:a m)) (* cos (:c m)))
         d (+ (* nsin (:b m)) (* cos (:d m)))
         tx' (+ (:tx m) (* tx (:a m)) (* ty (:c m)))
         ty' (+ (:ty m) (* tx (:b m)) (* ty (:d m)))]
     (Matrix. a b c d tx' ty'))))

(defn scale
  "Apply scale transformation to the matrix."
  ([m v] (scale m v v))
  ([m vx vy] (multiply m (scale-matrix vx vy))))

  ;; ([m v] (scale m v v))
  ;; ([m x y]
  ;;  (assoc m
  ;;         :a (* (:a m) x)
  ;;         :c (* (:c m) x)
  ;;         :b (* (:b m) y)
  ;;         :d (* (:d m) y))))

(defn translate
  "Apply translate transformation to the matrix."
  ([m pt]
   (multiply m (translate-matrix pt)))
  ([m x y]
   (translate m (gpt/point x y))))

  ;; ([m pt]
  ;;  (let [pt (gpt/point pt)]
  ;;    (assoc m
  ;;           :tx (+ (:tx m) (* (:x pt) (:a m)) (* (:y pt) (:b m)))
  ;;           :ty (+ (:ty m) (* (:x pt) (:c m)) (* (:y pt) (:d m))))))
  ;; ([m x y]
  ;;  (translate m (gpt/point x y))))

(defn ^boolean invertible?
  [{:keys [a b c d tx ty] :as m}]
  (let [det (- (* a d) (* c b))]
    (and (not (mth/nan? det))
         (mth/finite? tx)
         (mth/finite? ty))))

(defn invert
  [{:keys [a b c d tx ty] :as m}]
  (when (invertible? m)
    (let [det (- (* a d) (* c b))]
      (Matrix. (/ d det)
               (/ (- b) det)
               (/ (- c) det)
               (/ a det)
               (/ (- (* c ty) (* d tx)) det)
               (/ (- (* b tx) (* a ty)) det)))))

;; --- Transit Adapter

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler
   (fn [value]
     (map->Matrix value))))


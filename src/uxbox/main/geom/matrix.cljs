;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.geom.matrix
  (:require [cuerdas.core :as str]
            [uxbox.util.math :as mth]
            [uxbox.main.geom.point :as gpt]))

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

(defn matrix?
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

(defn rotate
  "Apply rotation transformation to the matrix."
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
         a (+ (* cos (:a m)) (* sin (:b m)))
         b (+ (* nsin (:a m)) (* cos (:b m)))
         c (+ (* cos (:c m)) (* sin (:d m)))
         d (+ (* nsin (:c m)) (* cos (:d m)))
         tx' (+ (:tx m) (* tx (:a m)) (* ty (:b m)))
         ty' (+ (:ty m) (* tx (:c m)) (* ty (:d m)))]
     (Matrix. a b c d tx' ty'))))

(defn scale
  "Apply scale transformation to the matrix."
  ([m v] (scale m v v))
  ([m x y]
   (assoc m
          :a (* (:a m) x)
          :c (* (:c m) x)
          :b (* (:b m) y)
          :d (* (:d m) y))))

(defn translate
  "Apply translate transformation to the matrix."
  ([m pt]
   (let [pt (gpt/-point pt)]
     (assoc m
            :tx (+ (:tx m) (* (:x pt) (:a m)) (* (:y pt) (:b m)))
            :ty (+ (:ty m) (* (:x pt) (:c m)) (* (:y pt) (:d m))))))
  ([m x y]
   (translate m (gpt/point x y))))

(defn append
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
      (+ (* a2 a1) (* c2 b1))
      (+ (* b2 a1) (* d2 b1))
      (+ (* a2 c1) (* c2 d1))
      (+ (* b2 c1) (* d2 d1))
      (+ tx1 (* tx2 a1) (* ty2 b1))
      (+ ty1 (* tx2 c1) (* ty2 d1)))))
  ([m om & others]
   (reduce append (append m om) others)))

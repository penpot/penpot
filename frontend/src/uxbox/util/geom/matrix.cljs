;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.geom.matrix
  (:require [cuerdas.core :as str]
            [cognitect.transit :as t]
            [uxbox.util.math :as mth]
            [uxbox.util.geom.point :as gpt]))

;; --- Matrix Impl

(defrecord Matrix [a b c d e f]
  Object
  (toString [_]
    (str "matrix(" a "," b "," c "," d "," e "," f ")")))

(defn multiply
  ([{m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f :as m1}
    {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f :as m2}]
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
  [{m1a :a m1b :b m1c :c m1d :d m1e :e m1f :f :as m1}
   {m2a :a m2b :b m2c :c m2d :d m2e :e m2f :f :as m2}]
  (Matrix.
   (- m1a m2a) (- m1b m2b) (- m1c m2c)
   (- m1d m2d) (- m1e m2e) (- m1f m2f)))

(defn ^boolean matrix?
  "Return true if `v` is Matrix instance."
  [v]
  (instance? Matrix v))

(defn matrix
  "Create a new matrix instance."
  ([]
   (Matrix. 1 0 0 1 0 0))
  ([a b c d e f]
   (Matrix. a b c d e f)))

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


;; --- Transit Adapter

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler
   (fn [value]
     (map->Matrix value))))

;; Calculates the delta vector to move the figure when scaling after rotation
;; https://math.stackexchange.com/questions/1449672/determine-shift-between-scaled-rotated-object-and-additional-scale-step
(defn correct-rotation [handler lx ly kx ky angle]
  (let [[s1 s2 s3]
        ;; Different sign configurations change the anchor corner
        (cond
          (#{:right :bottom :bottom-right} handler) [-1 1 1]
          (#{:left :top :top-left} handler) [1 -1 1]
          (#{:bottom-left} handler) [-1 -1 -1]
          (#{:top-right} handler) [1 1 -1])
        rad (* (or angle 0) (/ Math/PI 180))
        kx' (* (/ (- kx 1.) 2.) lx)
        ky' (* (/ (- ky 1.) 2.) ly)
        dx (+ (* s3 (* kx' (- 1 (Math/cos rad))))
              (* ky' (Math/sin rad)))
        dy (+ (* (- s3) (* ky' (- 1 (Math/cos rad))))
              (* kx' (Math/sin rad)))]
    (translate-matrix
     (gpt/point (* s1 dx) (* s2 dy)))))

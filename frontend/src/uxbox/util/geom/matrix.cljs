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
  [{x :x y :y :as pt}]
  (assert (gpt/point? pt))
  (Matrix. x 0 0 y 0 0))

(defn rotate-matrix
  [a]
  (let [a (mth/radians a)]
    (Matrix. (mth/cos a)
             (mth/sin a)
             (- (mth/sin a))
             (mth/cos a)
             0
             0)))

(defn rotate
  "Apply rotation transformation to the matrix."
  ([m angle]
   (multiply m (rotate-matrix angle)))
  ([m angle center]
   (multiply m
             (translate-matrix center)
             (rotate-matrix angle)
             (translate-matrix (gpt/negate center)))))

(defn scale
  "Apply scale transformation to the matrix."
  ([m scale] (multiply m (scale-matrix scale)))
  ([m scale center]
   (multiply m
             (translate-matrix center)
             (scale-matrix scale)
             (translate-matrix (gpt/negate center)))))

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


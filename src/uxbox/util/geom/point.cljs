;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.geom.point
  (:require [uxbox.util.math :as mth]))

(defrecord Point [x y])

(defprotocol ICoerce
  "Point coersion protocol."
  (-point [v] "Return a pont instance."))

(extend-protocol ICoerce
  nil
  (-point [_]
    (Point. 0 0))

  number
  (-point [v]
    (Point. v v))

  Point
  (-point [v] v)

  cljs.core/PersistentVector
  (-point [v]
    (Point. (first v) (second v)))

  cljs.core/IndexedSeq
  (-point [v]
    (Point. (first v) (second v))))

(defn point?
  "Return true if `v` is Point instance."
  [v]
  (instance? Point v))

(defn point
  "Create a Point instance."
  ([] (Point. 0 0))
  ([v] (-point v))
  ([x y] (Point. x y)))

(defn rotate
  "Apply rotation transformation to the point."
  [p angle]
  {:pre [(point? p)]}
  (let [angle (mth/radians angle)
        sin (mth/sin angle)
        cos (mth/cos angle)]
    (Point.
     (-> (- (* (:x p) cos) (* (:y p) sin))
         (mth/precision 6))
     (-> (+ (* (:x p) sin) (* (:y p) cos))
         (mth/precision 6)))))

(defn add
  "Returns the addition of the supplied value to both
  coordinates of the point as a new point."
  [p other]
  {:pre [(point? p)]}
  (let [other (-point other)]
    (Point. (+ (:x p) (:x other))
            (+ (:y p) (:y other)))))

(defn subtract
  "Returns the subtraction of the supplied value to both
  coordinates of the point as a new point."
  [p other]
  {:pre [(point? p)]}
  (let [other (-point other)]
    (Point. (- (:x p) (:x other))
            (- (:y p) (:y other)))))


(defn multiply
  "Returns the subtraction of the supplied value to both
  coordinates of the point as a new point."
  [p other]
  {:pre [(point? p)]}
  (let [other (-point other)]
    (Point. (* (:x p) (:x other))
            (* (:y p) (:y other)))))

(defn divide
  [p other]
  {:pre [(point? p)]}
  (let [other (-point other)]
    (Point. (/ (:x p) (:x other))
            (/ (:y p) (:y other)))))

(defn distance
  "Calculate the distance between two points."
  [p other]
  (let [other (-point other)
        dx (- (:x p) (:x other))
        dy (- (:y p) (:y other))]
    (-> (mth/sqrt (+ (mth/pow dx 2)
                     (mth/pow dy 2)))
        (mth/precision 6))))

(defn length
  [p]
  {:pre [(point? p)]}
  (mth/sqrt (+ (mth/pow (:x p) 2)
               (mth/pow (:y p) 2))))

(defn angle
  "Returns the smaller angle between two vectors.
  If the second vector is not provided, the angle
  will be measured from x-axis."
  ([p]
   {:pre [(point? p)]}
   (-> (mth/atan2 (:y p) (:x p))
       (mth/degrees)))
  ([p center]
   (let [center (-point center)]
     (angle (subtract p center)))))

(defn angle-with-other
  "Consider point as vector and calculate
  the angle between two vectors."
  [p other]
  {:pre [(point? p)]}
  (let [other (-point other)
        a (/ (+ (* (:x p) (:x other))
                (* (:y p) (:y other)))
             (* (length p) (length other)))
        a (mth/acos (if (< a -1)
                      -1
                      (if (> a 1) 1 a)))]
    (-> (mth/degrees a)
        (mth/precision 6))))

(defn update-angle
  "Update the angle of the point."
  [p angle]
  (let [len (length p)
        angle (mth/radians angle)]
    (Point. (* (mth/cos angle) len)
            (* (mth/sin angle) len))))

(defn quadrant
  "Return the quadrant of the angle of the point."
  [{:keys [x y] :as p}]
  {:pre [(point? p)]}
  (if (>= x 0)
    (if (>= y 0) 1 4)
    (if (>= y 0) 2 3)))

(defn transform-point
  [pt mx]
  (Point. (+ (* (:x pt) (:a mx))
             (* (:y pt) (:c mx))
             (:tx mx))
          (+ (* (:x pt) (:b mx))
             (* (:y pt) (:d mx))
             (:ty mx))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.geom.point
  (:refer-clojure :exclude [divide])
  (:require
   [cuerdas.core :as str]
   [uxbox.util.math :as mth]
   [cognitect.transit :as t]))

;; --- Point Impl

(defrecord Point [x y])

(defn s [{:keys [x y]}] (str "(" x "," y ")"))

(defn ^boolean point?
  "Return true if `v` is Point instance."
  [v]
  (instance? Point v))

(defn point
  "Create a Point instance."
  ([] (Point. 0 0))
  ([v]
   (cond
     (point? v)
     v

     (number? v)
     (Point. v v)

     :else
     (throw (ex-info "Invalid arguments" {:v v}))))
  ([x y] (Point. x y)))

(defn center
  [{:keys [x y width height]}]
  (point (+ x (/ width 2))
         (+ y (/ height 2))))

(defn add
  "Returns the addition of the supplied value to both
  coordinates of the point as a new point."
  [{x :x y :y :as p} {ox :x oy :y :as other}]
  (assert (point? p))
  (assert (point? other))
  (Point. (+ x ox) (+ y oy)))

(defn subtract
  "Returns the subtraction of the supplied value to both
  coordinates of the point as a new point."
  [{x :x y :y :as p} {ox :x oy :y :as other}]
  (assert (point? p))
  (assert (point? other))
  (Point. (- x ox) (- y oy)))

(defn multiply
  "Returns the subtraction of the supplied value to both
  coordinates of the point as a new point."
  [{x :x y :y :as p} {ox :x oy :y :as other}]
  (assert (point? p))
  (assert (point? other))
  (Point. (* x ox) (* y oy)))

(defn divide
  [{x :x y :y :as p} {ox :x oy :y :as other}]
  (assert (point? p))
  (assert (point? other))
  (Point. (/ x ox) (/ y oy)))

(defn inverse
  [{:keys [x y] :as p}]
  (assert (point? p))
  (Point. (/ 1 x) (/ 1 y)))

(defn negate
  [{x :x y :y :as p}]
  (assert (point? p))
  (Point. (- x) (- y)))

(defn distance
  "Calculate the distance between two points."
  [{x :x y :y :as p} {ox :x oy :y :as other}]
  (assert (point? p))
  (assert (point? other))
  (let [dx (- x ox)
        dy (- y oy)]
    (-> (mth/sqrt (+ (mth/pow dx 2)
                     (mth/pow dy 2)))
        (mth/precision 6))))

(defn length
  [{x :x y :y :as p}]
  (assert (point? p))
  (mth/sqrt (+ (mth/pow x 2)
               (mth/pow y 2))))

(defn angle
  "Returns the smaller angle between two vectors.
  If the second vector is not provided, the angle
  will be measured from x-axis."
  ([{x :x y :y :as p}]
   (-> (mth/atan2 y x)
       (mth/degrees)))
  ([p center]
   (angle (subtract p center))))

(defn angle-with-other
  "Consider point as vector and calculate
  the angle between two vectors."
  [{x :x y :y :as p} {ox :x oy :y :as other}]
  (assert (point? p))
  (assert (point? other))
  (let [a (/ (+ (* x ox)
                (* y oy))
             (* (length p)
                (length other)))
        a (mth/acos (if (< a -1) -1 (if (> a 1) 1 a)))]
    (-> (mth/degrees a)
        (mth/precision 6))))

(defn update-angle
  "Update the angle of the point."
  [p angle]
  (assert (point? p))
  (assert (number? angle))
  (let [len (length p)
        angle (mth/radians angle)]
    (Point. (* (mth/cos angle) len)
            (* (mth/sin angle) len))))

(defn quadrant
  "Return the quadrant of the angle of the point."
  [{:keys [x y] :as p}]
  (assert (point? p))
  (if (>= x 0)
    (if (>= y 0) 1 4)
    (if (>= y 0) 2 3)))

(defn round
  "Change the precision of the point coordinates."
  [{:keys [x y] :as p} decimanls]
  (assert (point? p))
  (assert (number? decimanls))
  (Point. (mth/precision x decimanls)
          (mth/precision y decimanls)))

(defn transform
  "Transform a point applying a matrix transfomation."
  [{:keys [x y] :as p} {:keys [a b c d e f] :as m}]
  (assert (point? p))
  (Point. (+ (* x a) (* y c) e)
          (+ (* x b) (* y d) f)))

;; --- Transit Adapter

(def point-write-handler
  (t/write-handler
   (constantly "point")
   (fn [v] (into {} v))))

(def point-read-handler
  (t/read-handler
   (fn [value]
     (map->Point value))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.point
  (:refer-clojure :exclude [divide min max])
  (:require
   #?(:cljs [cljs.pprint :as pp]
      :clj  [clojure.pprint :as pp])
   #?(:cljs [cljs.core :as c]
      :clj [clojure.core :as c])
   [app.common.math :as mth]))

;; --- Point Impl

(defrecord Point [x y])

(defn s [{:keys [x y]}] (str "(" x "," y ")"))

(defn ^boolean point?
  "Return true if `v` is Point instance."
  [v]
  (instance? Point v))

(defn ^boolean point-like?
  [{:keys [x y] :as v}]
  (and (map? v)
       (not (nil? x))
       (not (nil? y))
       (number? x)
       (number? y)))

(defn point
  "Create a Point instance."
  ([] (Point. 0 0))
  ([v]
   (cond
     (point? v)
     v

     (number? v)
     (point v v)

     (point-like? v)
     (point (:x v) (:y v))

     :else
     (throw (ex-info "Invalid arguments" {:v v}))))
  ([x y]
   (Point. x y)))

(defn angle->point [{:keys [x y]} angle distance]
  (point
   (+ x (* distance (mth/cos angle)))
   (- y (* distance (mth/sin angle)))))

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


(defn min
  ([] (min nil nil))
  ([p1] (min p1 nil))
  ([{x1 :x y1 :y :as p1} {x2 :x y2 :y :as p2}]
   (cond
     (nil? p1) p2
     (nil? p2) p1
     :else (Point. (c/min x1 x2) (c/min y1 y2)))))

(defn max
  ([] (max nil nil))
  ([p1] (max p1 nil))
  ([{x1 :x y1 :y :as p1} {x2 :x y2 :y :as p2}]
   (cond
     (nil? p1) p2
     (nil? p2) p1
     :else (Point. (c/max x1 x2) (c/max y1 y2)))))

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

  (let [length-p (length p)
        length-other (length other)]
    (if (or (mth/almost-zero? length-p)
            (mth/almost-zero? length-other))
      0
      (let [a (/ (+ (* x ox)
                    (* y oy))
                 (* length-p length-other))
            a (mth/acos (if (< a -1) -1 (if (> a 1) 1 a)))
            d (-> (mth/degrees a)
                  (mth/precision 6))]
        (if (mth/nan? d) 0 d)))))


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
  ([point] (round point 0))
  ([{:keys [x y] :as p} decimanls]
   (assert (point? p))
   (assert (number? decimanls))
   (Point. (mth/precision x decimanls)
           (mth/precision y decimanls))))

(defn transform
  "Transform a point applying a matrix transfomation."
  [{:keys [x y] :as p} {:keys [a b c d e f]}]
  (assert (point? p))
  (Point. (+ (* x a) (* y c) e)
          (+ (* x b) (* y d) f)))

;; Vector functions
(defn to-vec [p1 p2]
  (subtract p2 p1))

(defn dot [{x1 :x y1 :y} {x2 :x y2 :y}]
  (+ (* x1 x2) (* y1 y2)))

(defn unit [v]
  (let [v-length (length v)]
    (divide v (point v-length v-length))))

(defn project [v1 v2]
  (let [v2-unit (unit v2)
        scalar-projection (dot v1 (unit v2))]
    (multiply
     v2-unit
     (point scalar-projection scalar-projection))))

(defn center-points [points]
  (let [k (point (count points))]
    (reduce #(add %1 (divide %2 k)) (point) points)))

(defn normal-left
  "Returns the normal unit vector on the left side"
  [{:keys [x y]}]
  (unit (point (- y) x)))

(defn normal-right
  "Returns the normal unit vector on the right side"
  [{:keys [x y]}]
  (unit (point y (- x))))

(defn point-line-distance
  "Returns the distance from a point to a line defined by two points"
  [point line-point1 line-point2]
  (let [{x0 :x y0 :y} point
        {x1 :x y1 :y} line-point1
        {x2 :x y2 :y} line-point2
        num (mth/abs
             (+ (* x0 (- y2 y1))
                (- (* y0 (- x2 x1)))
                (* x2 y1)
                (- (* y2 x1))))
        dist (distance line-point2 line-point1)]
    (/ num dist)))

(defn almost-zero? [{:keys [x y] :as p}]
  (assert (point? p))
  (and (mth/almost-zero? x)
       (mth/almost-zero? y)))


;; --- Debug

(defmethod pp/simple-dispatch Point [obj] (pr obj))

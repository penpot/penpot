;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.point
  (:refer-clojure :exclude [divide min max abs])
  (:require
   #?(:cljs [cljs.pprint :as pp]
      :clj  [clojure.pprint :as pp])
   #?(:cljs [cljs.core :as c]
      :clj [clojure.core :as c])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Point Impl

(defrecord Point [x y])

(defn s
  [pt]
  (dm/str "(" (dm/get-prop pt :x) "," (dm/get-prop pt :y) ")"))

(defn point?
  "Return true if `v` is Point instance."
  [v]
  (instance? Point v))

(sm/def! ::point-map
  [:map {:title "PointMap"}
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]])


;; FIXME: deprecated
(s/def ::x ::us/safe-number)
(s/def ::y ::us/safe-number)

(s/def ::point-attrs
  (s/keys :req-un [::x ::y]))

(s/def ::point
  (s/and ::point-attrs point?))

(sm/def! ::point
  (letfn [(decode [p]
            (if (map? p)
              (map->Point p)
              (if (string? p)
                (let [[x y] (->> (str/split p #",") (mapv parse-double))]
                  (Point. x y))
                p)))

          (encode [p]
            (dm/str (dm/get-prop p :x) ","
                    (dm/get-prop p :y)))]

    {:type ::point
     :pred point?
     :type-properties
     {:title "point"
      :description "Point"
      :error/message "expected a valid point"
      :gen/gen (->> (sg/tuple (sg/small-int) (sg/small-int))
                    (sg/fmap #(apply ->Point %)))
      ::oapi/type "string"
      ::oapi/format "point"
      ::oapi/decode decode
      ::oapi/encode encode}}))

(defn point-like?
  [{:keys [x y] :as v}]
  (and (map? v)
       (d/num? x)
       (d/num? y)))

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
     (Point. (:x v) (:y v))

     :else
     (ex/raise :hint "invalid arguments (on pointer constructor)" :value v)))
  ([x y]
   (Point. x y)))

(defn close?
  [p1 p2]
  (and (mth/close? (dm/get-prop p1 :x)
                   (dm/get-prop p2 :x))
       (mth/close? (dm/get-prop p1 :y)
                   (dm/get-prop p2 :y))))

(defn angle->point
  [pt angle distance]
  (point
   (+ (dm/get-prop pt :x) (* distance (mth/cos angle)))
   (- (dm/get-prop pt :y) (* distance (mth/sin angle)))))

(defn add
  "Returns the addition of the supplied value to both
  coordinates of the point as a new point."
  [p1 p2]
  (dm/assert!
   "arguments should be point instance"
   (and (point? p1)
        (point? p2)))

  (Point. (+ (dm/get-prop p1 :x)
             (dm/get-prop p2 :x))
          (+ (dm/get-prop p1 :y)
             (dm/get-prop p2 :y))))

(defn subtract
  "Returns the subtraction of the supplied value to both
  coordinates of the point as a new point."
  [p1 p2]
  (assert (and (point? p1)
               (point? p2))
          "arguments should be pointer instance")
  (Point. (- (dm/get-prop p1 :x)
             (dm/get-prop p2 :x))
          (- (dm/get-prop p1 :y)
             (dm/get-prop p2 :y))))

(defn multiply
  "Returns the subtraction of the supplied value to both
  coordinates of the point as a new point."
  [p1 p2]
  (assert (and (point? p1)
               (point? p2))
          "arguments should be pointer instance")
  (Point. (* (dm/get-prop p1 :x)
             (dm/get-prop p2 :x))
          (* (dm/get-prop p1 :y)
             (dm/get-prop p2 :y))))

(defn divide
  [p1 p2]
  (assert (and (point? p1)
               (point? p2))
          "arguments should be pointer instance")
  (Point. (/ (dm/get-prop p1 :x)
             (dm/get-prop p2 :x))
          (/ (dm/get-prop p1 :y)
             (dm/get-prop p2 :y))))

(defn min
  ([] nil)
  ([p1] p1)
  ([p1 p2]
   (cond
     (nil? p1) p2
     (nil? p2) p1
     :else (Point. (c/min (dm/get-prop p1 :x)
                          (dm/get-prop p2 :x))
                   (c/min (dm/get-prop p1 :y)
                          (dm/get-prop p2 :y))))))
(defn max
  ([] nil)
  ([p1] p1)
  ([p1 p2]
   (cond
     (nil? p1) p2
     (nil? p2) p1
     :else (Point. (c/max (dm/get-prop p1 :x)
                          (dm/get-prop p2 :x))
                   (c/max (dm/get-prop p1 :y)
                          (dm/get-prop p2 :y))))))
(defn inverse
  [pt]
  (assert (point? pt) "point instance expected")
  (Point. (/ 1.0 (dm/get-prop pt :x))
          (/ 1.0 (dm/get-prop pt :y))))

(defn negate
  [pt]
  (assert (point? pt) "point instance expected")
  (Point. (- (dm/get-prop pt :x))
          (- (dm/get-prop pt :y))))

(defn distance
  "Calculate the distance between two points."
  [p1 p2]
  (assert (and (point? p1)
               (point? p2))
          "arguments should be point instances")
  (let [dx (- (dm/get-prop p1 :x)
              (dm/get-prop p2 :x))
        dy (- (dm/get-prop p1 :y)
              (dm/get-prop p2 :y))]
    (mth/hypot dx dy)))

(defn distance-vector
  "Calculate the distance, separated x and y."
  [p1 p2]
  (assert (and (point? p1)
               (point? p2))
          "arguments should be point instances")
  (let [dx (- (dm/get-prop p1 :x)
              (dm/get-prop p2 :x))
        dy (- (dm/get-prop p1 :y)
              (dm/get-prop p2 :y))]
    (Point. (mth/abs dx)
            (mth/abs dy))))

(defn length
  [pt]
  (assert (point? pt) "point instance expected")
  (let [x (dm/get-prop pt :x)
        y (dm/get-prop pt :y)]
    (mth/hypot x y)))

(defn angle
  "Returns the smaller angle between two vectors.
  If the second vector is not provided, the angle
  will be measured from x-axis."
  ([pt]
   (assert (point? pt) "point instance expected")
   (let [x (dm/get-prop pt :x)
         y (dm/get-prop pt :y)]
     (-> (mth/atan2 y x)
         (mth/degrees))))
  ([pt center]
   (assert (point? pt) "point instance expected")
   (assert (point? center) "point instance expected")
   (let [x (- (dm/get-prop pt :x)
              (dm/get-prop center :x))
         y (- (dm/get-prop pt :y)
              (dm/get-prop center :y))]
     (-> (mth/atan2 y x)
         (mth/degrees)))))

(defn angle-with-other
  "Consider point as vector and calculate
  the angle between two vectors."
  [p1 p2]
  (assert (and (point? p1)
               (point? p2))
          "arguments should be point instances")
  (let [length-p1 (length p1)
        length-p2 (length p2)]
    (if (or (mth/almost-zero? length-p1)
            (mth/almost-zero? length-p2))
      0
      (let [a (/ (+ (* (dm/get-prop p1 :x)
                       (dm/get-prop p2 :x))
                    (* (dm/get-prop p1 :y)
                       (dm/get-prop p2 :y)))
                 (* length-p1 length-p2))
            a (mth/acos (if (< a -1) -1 (if (> a 1) 1 a)))
            d (mth/degrees a)]
        (if (mth/nan? d) 0 d)))))

(defn angle-sign
  [p1 p2]
  (if (> (* (dm/get-prop p1 :y) (dm/get-prop p2 :x))
         (* (dm/get-prop p1 :x) (dm/get-prop p2 :y)))
    -1
    1))

(defn signed-angle-with-other
  [v1 v2]
  (* (angle-sign v1 v2) (angle-with-other v1 v2)))

(defn update-angle
  "Update the angle of the point."
  [p angle]
  (assert (number? angle) "expected number")
  (let [len   (length p)
        angle (mth/radians angle)]
    (Point. (* (mth/cos angle) len)
            (* (mth/sin angle) len))))

(defn quadrant
  "Return the quadrant of the angle of the point."
  [p]
  (assert (point? p) "expected point instance")
  (let [x (dm/get-prop p :x)
        y (dm/get-prop p :y)]
    (if (>= x 0)
      (if (>= y 0) 1 4)
      (if (>= y 0) 2 3))))

(defn round
  "Round the coordinates of the point to a precision"
  ([point]
   (round point 0))

  ([pt decimals]
   (assert (point? pt) "expected point instance")
   (assert (number? decimals) "expected number instance")
   (Point. (mth/precision (dm/get-prop pt :x) decimals)
           (mth/precision (dm/get-prop pt :y) decimals))))

(defn round-step
  "Round the coordinates to the closest half-point"
  [pt step]
  (assert (point? pt) "expected point instance")
  (Point. (mth/round (dm/get-prop pt :x) step)
          (mth/round (dm/get-prop pt :y) step)))

(defn transform
  "Transform a point applying a matrix transformation."
  [p m]
  (when (point? p)
    (if (nil? m)
      p
      (let [x (dm/get-prop p :x)
            y (dm/get-prop p :y)
            a (dm/get-prop m :a)
            b (dm/get-prop m :b)
            c (dm/get-prop m :c)
            d (dm/get-prop m :d)
            e (dm/get-prop m :e)
            f (dm/get-prop m :f)]
        (Point. (+ (* x a) (* y c) e)
                (+ (* x b) (* y d) f))))))


;; Vector functions
(defn to-vec [p1 p2]
  (subtract p2 p1))

(defn scale
  [p scalar]
  (Point. (* (dm/get-prop p :x) scalar)
          (* (dm/get-prop p :y) scalar)))

(defn dot
  [p1 p2]
  (+ (* (dm/get-prop p1 :x)
        (dm/get-prop p2 :x))
     (* (dm/get-prop p1 :y)
        (dm/get-prop p2 :y))))

(defn unit
  [p1]
  (let [p-length (length p1)]
    (if (mth/almost-zero? p-length)
      (Point. 0 0)
      (Point. (/ (dm/get-prop p1 :x) p-length)
              (/ (dm/get-prop p1 :y) p-length)))))

(defn perpendicular
  [pt]
  (Point. (- (dm/get-prop pt :y))
          (dm/get-prop pt :x)))

(defn project
  "V1 perpendicular projection on vector V2"
  [v1 v2]
  (let [v2-unit     (unit v2)
        scalar-proj (dot v1 v2-unit)]
    (scale v2-unit scalar-proj)))

(defn center-points
  "Centroid of a group of points"
  [points]
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
  (let [x0  (dm/get-prop point :x)
        y0  (dm/get-prop point :y)
        x1  (dm/get-prop line-point1 :x)
        y1  (dm/get-prop line-point1 :y)
        x2  (dm/get-prop line-point2 :x)
        y2  (dm/get-prop line-point2 :y)]
    (/ (mth/abs (+ (* x0 (- y2 y1))
                   (- (* y0 (- x2 x1)))
                   (* x2 y1)
                   (- (* y2 x1))))
       (distance line-point2 line-point1))))

(defn almost-zero?
  [p]
  (assert (point? p) "point instance expected")
  (and ^boolean (mth/almost-zero? (dm/get-prop p :x))
       ^boolean (mth/almost-zero? (dm/get-prop p :y))))

(defn lerp
  "Calculates a linear interpolation between two points given a tvalue"
  [p1 p2 t]
  (let [x (mth/lerp (dm/get-prop p1 :x) (dm/get-prop p2 :x) t)
        y (mth/lerp (dm/get-prop p1 :y) (dm/get-prop p2 :y) t)]
    (Point. x y)))

(defn rotate
  "Rotates the point around center with an angle"
  [p c angle]
  (assert (point? p) "point instance expected")
  (assert (point? c) "point instance expected")
  (let [angle (mth/radians angle)
        px    (dm/get-prop p :x)
        py    (dm/get-prop p :y)
        cx    (dm/get-prop c :x)
        cy    (dm/get-prop c :y)

        sa    (mth/sin angle)
        ca    (mth/cos angle)

        x     (+ (* ca (- px cx))
                 (* sa (- py cy) -1)
                 cx)
        y     (+ (* sa (- px cx))
                 (* ca (- py cy))
                 cy)]
    (Point. x y)))

(defn scale-from
  "Moves a point in the vector that creates with center with a scale
  value"
  [point center value]
  (add point
       (-> (to-vec center point)
           (unit)
           (scale value))))

(defn no-zeros
  "Remove zero values from either coordinate"
  [p]
  (let [x (dm/get-prop p :x)
        y (dm/get-prop p :y)]
    (Point. (if (mth/almost-zero? x) 0.001 x)
            (if (mth/almost-zero? y) 0.001 y))))


(defn abs
  [point]
  (-> point
      (update :x mth/abs)
      (update :y mth/abs)))

;; --- Debug

(defmethod pp/simple-dispatch Point [obj] (pr obj))


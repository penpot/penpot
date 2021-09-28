;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.math
  "A collection of math utils."
  #?(:cljs
     (:require [goog.math :as math])))

(def PI
  #?(:cljs (.-PI js/Math)
     :clj Math/PI))

(defn nan?
  [v]
  #?(:cljs (js/isNaN v)
     :clj (Double/isNaN v)))

(defn finite?
  [v]
  #?(:cljs (and (not (nil? v)) (js/isFinite v))
     :clj (and (not (nil? v)) (Double/isFinite v))))

(defn finite
  [v default]
  (if (finite? v) v default))

(defn abs
  [v]
  #?(:cljs (js/Math.abs v)
     :clj (Math/abs v)))

(defn sin
  "Returns the sine of a number"
  [v]
  #?(:cljs (js/Math.sin v)
     :clj (Math/sin v)))

(defn cos
  "Returns the cosine of a number."
  [v]
  #?(:cljs (js/Math.cos v)
     :clj (Math/cos v)))

(defn acos
  "Returns the arccosine of a number."
  [v]
  #?(:cljs (js/Math.acos v)
     :clj (Math/acos v)))

(defn tan
  "Returns the tangent of a number."
  [v]
  #?(:cljs (js/Math.tan v)
     :clj (Math/tan v)))

(defn atan2
  "Returns the arctangent of the quotient of its arguments."
  [x y]
  #?(:cljs (js/Math.atan2 x y)
     :clj (Math/atan2 x y)))

(defn neg
  "Negate the number"
  [v]
  (- v))

(defn sq
  "Calculates the square of a number"
  [v]
  (* v v))

(defn pow
  "Returns the base to the exponent power."
  [b e]
  #?(:cljs (js/Math.pow b e)
     :clj (Math/pow b e)))

(defn sqrt
  "Returns the square root of a number."
  [v]
  #?(:cljs (js/Math.sqrt v)
     :clj (Math/sqrt v)))

(defn cubicroot
  "Returns the cubic root of a number"
  [v]
  (if (pos? v)
    (pow v (/ 1 3))
    (- (pow (- v) (/ 1 3)))))

(defn floor
  "Returns the largest integer less than or
  equal to a given number."
  [v]
  #?(:cljs (js/Math.floor v)
     :clj (Math/floor v)))

(defn round
  "Returns the value of a number rounded to
  the nearest integer."
  [v]
  #?(:cljs (js/Math.round v)
     :clj (Math/round (float v))))

(defn ceil
  "Returns the smallest integer greater than
  or equal to a given number."
  [v]
  #?(:cljs (js/Math.ceil v)
     :clj (Math/ceil v)))

(defn precision
  [v n]
  (when (and (number? v) (number? n))
    (let [d (pow 10 n)]
      (/ (round (* v d)) d))))

(defn radians
  "Converts degrees to radians."
  [degrees]
  #?(:cljs (math/toRadians degrees)
     :clj (Math/toRadians degrees)))

(defn degrees
  "Converts radians to degrees."
  [radians]
  #?(:cljs (math/toDegrees radians)
     :clj (Math/toDegrees radians)))

(defn distance
  "Calculate the distance between two points."
  [[x1 y1] [x2 y2]]
  (let [dx (- x1 x2)
        dy (- y1 y2)]
    (-> (sqrt (+ (pow dx 2) (pow dy 2)))
        (precision 2))))

(defn log10
  "Logarithm base 10"
  [x]
  #?(:cljs (js/Math.log10 x)
     :clj (Math/log10 x)))

(defn clamp [num from to]
  (if (< num from)
    from
    (if (> num to) to num)))

(defn almost-zero? [num]
  (< (abs (double num)) 1e-5))

(defonce float-equal-precision 0.001)

(defn close?
  "Equality for float numbers. Check if the difference is within a range"
  [num1 num2]
  (<= (abs (- num1 num2)) float-equal-precision))

(defn lerp
  "Calculates a the linear interpolation between two values and a given percent"
  [v0 v1 t]
  (+ (* (- 1 t) v0)
     (* t       v1)))

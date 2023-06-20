;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.math
  "A collection of math utils."
  (:refer-clojure :exclude [abs min max])
  #?(:cljs
     (:require-macros [app.common.math :refer [min max]]))
  (:require
   #?(:cljs [goog.math :as math])
   [clojure.core :as c]))

(defmacro min
  [& params]
  (if (:ns &env)
    `(js/Math.min ~@params)
    `(c/min ~@params)))

(defmacro max
  [& params]
  (if (:ns &env)
    `(js/Math.max ~@params)
    `(c/max ~@params)))

(def PI
  #?(:cljs (.-PI js/Math)
     :clj Math/PI))

(defn nan?
  [v]
  #?(:cljs (js/isNaN v)
     :clj (Double/isNaN v)))

;; NOTE: on cljs we don't need to check for `number?` so we explicitly
;; ommit it for performance reasons.

(defn finite?
  [v]
  #?(:cljs (and (not (nil? v)) (js/isFinite v))
     :clj (and (not (nil? v)) (number? v) (Double/isFinite v))))

(defn finite
  [v default]
  (if (finite? v) v default))

(defn abs
  [v]
  #?(:cljs (js/Math.abs v)
     :clj (Math/abs (double v))))

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
  the nearest integer.
  If given step rounds to the next closest step, for example:
  (round 13.4 0.5) => 13.5
  (round 13.4 0.3) => 13.3"
  ([v step]
   (* (round (/ v step)) step))

  ([v]
   #?(:cljs (js/Math.round v)
      :clj (Math/round (float v)))))

(defn ceil
  "Returns the smallest integer greater than
  or equal to a given number."
  [v]
  #?(:cljs (js/Math.ceil v)
     :clj (Math/ceil v)))

(defn precision
  [v n]
  (when (and (number? v) (integer? n))
    (let [d (pow 10 n)]
      (/ (round (* v d)) d))))

(defn to-fixed
  "Returns a string representing the given number, using fixed precision."
  [v n]
  #?(:cljs (.toFixed ^js v n)
     :clj (str (precision v n))))

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

(defn hypot
  "Square root of the squares addition"
  [a b]
  #?(:cljs (js/Math.hypot a b)
     :clj (Math/hypot a b)))

(defn distance
  "Calculate the distance between two points."
  [[x1 y1] [x2 y2]]
  (let [dx (- x1 x2)
        dy (- y1 y2)]
    (-> (hypot dx dy)
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
  (< (abs (double num)) 1e-4))

(defn round-to-zero
  "Given a number if it's close enough to zero round to the zero to avoid precision problems"
  [num]
  (if (< (abs num) 1e-4)
    0
    num))

(defonce float-equal-precision 0.001)

(defn close?
  "Equality for float numbers. Check if the difference is within a range"
  ([num1 num2]
   (close? num1 num2 float-equal-precision))
  ([num1 num2 precision]
   (<= (abs (- num1 num2)) precision)))

(defn lerp
  "Calculates a the linear interpolation between two values and a given percent"
  [v0 v1 t]
  (+ (* (- 1 t) v0)
     (* t       v1)))

(defn max-abs
  [a b]
  (max (abs a)
       (abs b)))

(defn sign
  "Get the sign (+1 / -1) for the number"
  [n]
  (if (neg? n) -1 1))



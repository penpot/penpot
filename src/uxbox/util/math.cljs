;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.math
  "A collection of math utils."
  (:require [goog.math :as math]))

(defn abs
  [^number v]
  (js/Math.abs v))

(defn sin
  "Returns the sine of a number"
  [^number v]
  (js/Math.sin v))

(defn cos
  "Returns the cosine of a number."
  [^number v]
  (js/Math.cos v))

(defn acos
  "Returns the arccosine of a number."
  [^number v]
  (js/Math.acos v))

(defn tan
  "Returns the tangent of a number."
  [^number v]
  (js/Math.tan v))

(defn atan2
  "Returns the arctangent of the quotient of its arguments."
  [^number x ^number y]
  (js/Math.atan2 x y))

(defn neg
  "Negate the number"
  [^number v]
  (- v))

(defn sqrt
  "Returns the square root of a number."
  [v]
  (js/Math.sqrt v))

(defn pow
  "Returns the base to the exponent power."
  [b e]
  (js/Math.pow b e))

(defn floor
  "Returns the largest integer less than or
  equal to a given number."
  [^number v]
  (js/Math.floor v))

(defn round
  "Returns the value of a number rounded to
  the nearest integer."
  [^number v]
  (js/Math.round v))

(defn ceil
  "Returns the smallest integer greater than
  or equal to a given number."
  [^number v]
  (js/Math.ceil v))

(defn precision
  [^number v ^number n]
  (js/parseFloat (.toFixed v n)))

(defn radians
  "Converts degrees to radians."
  [^number degrees]
  (math/toRadians degrees))

(defn degrees
  "Converts radians to degrees."
  [^number radiants]
  (math/toDegrees radiants))

(defn distance
  "Calculate the distance between two points."
  [[x1 y1] [x2 y2]]
  (let [dx (- x1 x2)
        dy (- y1 y2)]
    (-> (sqrt (+ (pow dx 2) (pow dy 2)))
        (precision 2))))

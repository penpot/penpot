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

(defn tan
  "Returns the tangent of a number."
  [^number v]
  (js/Math.tan v))

(defn neg
  "Negate the number"
  [^number v]
  (- v))

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

(defn radians
  "Converts degrees to radians."
  [^number degrees]
  (math/toRadians degrees))

(defn degrees
  "Converts radians to degrees."
  [^number radiants]
  (math/toDegrees radiants))

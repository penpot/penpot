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

(defn radians
  "Converts degrees to radians."
  [^number degrees]
  (math/toRadians degrees))

(defn degrees
  "Converts radians to degrees."
  [^number radiants]
  (math/toDegrees radiants))

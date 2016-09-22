;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.color
  "Color conversion utils."
  (:require [cuerdas.core :as str]
            [uxbox.util.math :as math]
            [goog.color :as gcolor]))

(defn rgb->str
  [color]
  {:pre [(vector? color)]}
  (if (= (count color) 3)
    (apply str/format "rgb(%s,%s,%s)" color)
    (apply str/format "rgba(%s,%s,%s,%s)" color)))

(defn rgb->hsv
  [[r g b]]
  (into [] (gcolor/rgbToHsv r g b)))

(defn hsv->rgb
  [[h s v]]
  (into [] (gcolor/hsvToRgb h s v)))

(defn hex->rgb
  [v]
  (into [] (gcolor/hexToRgb v)))

(defn rgb->hex
  [[r g b]]
  (gcolor/rgbToHex r g b))

(defn hex->hsv
  [v]
  (into [] (gcolor/hexToHsv v)))

(defn hsv->hex
  [[h s v]]
  (gcolor/hsvToHex h s v))

(defn hex->rgba
  [^string data ^number opacity]
  (-> (hex->rgb data)
      (conj opacity)))

(defn hex?
  [v]
  (and (string? v)
       (re-seq #"^#[0-9A-Fa-f]{6}$" v)))

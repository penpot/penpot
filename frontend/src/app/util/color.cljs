;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.color
  "Color conversion utils."
  (:require
   [cuerdas.core :as str]
   [app.common.math :as math]
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
  (try
    (into [] (gcolor/hexToRgb v))
    (catch :default e [0 0 0])))

(defn rgb->hex
  [[r g b]]
  (gcolor/rgbToHex r g b))

(defn hex->hsv
  [v]
  (into [] (gcolor/hexToHsv v)))

(defn hex->rgba
  [^string data ^number opacity]
  (-> (hex->rgb data)
      (conj opacity)))

(defn hex->hsl [hex]
  (try
    (into [] (gcolor/hexToHsl hex))
    (catch :default e (do
                        (.log js/console e)
                        [0 0 0]))))

(defn hsl->rgb
  [[h s l]]
  (gcolor/hslToRgb h s l))

(defn hsl->hex
  [[h s l]]
  (gcolor/hslToHex h s l))

(defn hex?
  [v]
  (and (string? v)
       (re-seq #"^#[0-9A-Fa-f]{6}$" v)))

(defn hsl->hsv
  [[h s l]]
  (gcolor/hslToHsv h s l))

(defn hsv->hex
  [[h s v]]
  (gcolor/hsvToHex h s v))

(defn hsv->hsl
  [hsv]
  (hex->hsl (hsv->hex hsv)))

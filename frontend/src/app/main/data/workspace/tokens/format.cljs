;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.format
  (:require
   [app.common.math :as mth]
   [cuerdas.core :as str]))

(def category-dictionary
  {:stroke-width "Stroke Width"
   :spacing "Spacing"
   :sizing "Sizing"
   :border-radius "Border Radius"
   :x "X"
   :y "Y"
   :font-size "Font Size"
   :font-family "Font Family"
   :font-weight "Font Weight"
   :line-height "Line Height"
   :letter-spacing "Letter Spacing"
   :text-case "Text Case"
   :text-decoration "Text Decoration"
   :offset-x "X"
   :offset-y "Y"
   :blur "Blur"
   :spread "Spread"
   :color "Color"
   :inset "Inner Shadow"})

(declare format-token-value)

(defn- format-map-entries
  "Formats a sequence of [k v] entries into a formatted string."
  [entries]
  (->> entries
       (map (fn [[k v]]
              (str "- " (category-dictionary (keyword k)) ": " (format-token-value v))))
       (str/join "\n")
       (str "\n")))

(defn- gradient?
  [token-value]
  (and (map? token-value) (contains? token-value :stops)))

(defn format-angle
  "Writes an angle as tooltips, Inspect and the angle field of the
  gradient form show it. Writes a resolved number in degrees. Other
  values stay as they are."
  [angle]
  (if (number? angle)
    (str (mth/precision angle 2) "deg")
    (str angle)))

(defn format-position
  "Writes a gradient stop position as tooltips, Inspect and the position
  field of the gradient form show it. Writes a resolved fraction from 0
  to 1 as a percentage. Other values stay as they are."
  [position]
  (if (number? position)
    (str (mth/precision (* 100 position) 2) "%")
    (str position)))

(defn- format-gradient
  "Writes a gradient on one line: its kind and angle, then the color and
  position of each stop, like
  `linear 90deg: #ff0000 0%, {color.brand} 100%`. A resolved gradient
  has numbers. They are written as the gradient form shows them, in
  degrees and percentages. A token value keeps its text."
  [{:keys [kind angle stops]}]
  (str kind
       (when (some? angle) (str " " (format-angle angle)))
       ": "
       (->> stops
            (map (fn [{:keys [color position]}]
                   (str color " " (format-position position))))
            (str/join ", "))))

(defn format-token-value
  "Converts token value of any shape to a string."
  [token-value]
  (cond
    (gradient? token-value)
    (format-gradient token-value)

    (map? token-value)
    (format-map-entries token-value)

    (and (sequential? token-value) (every? map? token-value))
    (str/join "\n" (map format-token-value token-value))

    (sequential? token-value)
    (str/join ", " token-value)

    :else
    (str token-value)))

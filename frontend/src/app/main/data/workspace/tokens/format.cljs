;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.format
  (:require
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

(defn format-token-value
  "Converts token value of any shape to a string."
  [token-value]
  (cond
    (map? token-value)
    (format-map-entries token-value)

    (and (sequential? token-value) (every? map? token-value))
    (str/join "\n" (map format-token-value token-value))

    (sequential? token-value)
    (str/join ", " token-value)

    :else
    (str token-value)))

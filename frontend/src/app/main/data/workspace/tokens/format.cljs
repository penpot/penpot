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
   :offsetX "X"
   :offsetY "Y"
   :blur "Blur"
   :spread "Spread"
   :color "Color"
   :inset "Inner Shadow"})

(defn format-token-value
  "Converts token value of any shape to a string."
  [token-value]
  (cond
    (map? token-value)
    (->> (map (fn [[k v]] (str "- " (category-dictionary k) ": " (format-token-value v))) token-value)
         (str/join "\n")
         (str "\n"))

    (and (sequential? token-value) (every? map? token-value))
    (str/join "\n" (map format-token-value token-value))

    (sequential? token-value)
    (str/join ", " token-value)

    :else
    (str token-value)))

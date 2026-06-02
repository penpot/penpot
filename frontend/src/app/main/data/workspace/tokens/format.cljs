(ns app.main.data.workspace.tokens.format
  (:require
   [app.main.data.tokenscript :as ts]
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

(defn- format-structured-token
  "Formats tokenscript Token"
  [token-symbol]
  (->> (.-value token-symbol)
       (.entries)
       (es6-iterator-seq)
       (format-map-entries)))

(defn format-tokenscript-symbol
  [^js tokenscript-symbol]
  (cond
    (ts/rem-number-with-unit? tokenscript-symbol)
    (str (ts/rem->px tokenscript-symbol) "px")

    (ts/color-symbol? tokenscript-symbol)
    (ts/color-symbol->hex-string tokenscript-symbol)

    (ts/structured-record-token? tokenscript-symbol)
    (format-structured-token tokenscript-symbol)

    (ts/structured-array-token? tokenscript-symbol)
    (str/join "\n" (map format-tokenscript-symbol (.-value tokenscript-symbol)))

    :else
    (.toString tokenscript-symbol)))

(defn format-token-value
  "Converts token value of any shape to a string."
  [token-value]
  (cond
    (ts/tokenscript-symbol? token-value)
    (format-tokenscript-symbol token-value)

    (map? token-value)
    (format-map-entries token-value)

    (and (sequential? token-value) (every? map? token-value))
    (str/join "\n" (map format-token-value token-value))

    (sequential? token-value)
    (str/join ", " token-value)

    :else
    (str token-value)))

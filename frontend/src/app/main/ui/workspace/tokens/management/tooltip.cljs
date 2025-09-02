(ns app.main.ui.workspace.tokens.management.tooltip
  (:require-macros
   [app.common.data.macros :as dm])
  (:require
   [app.main.data.workspace.tokens.application :as dwta]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]))

(def ^:private attribute-dictionary
  {:rotation "Rotation"
   :opacity "Opacity"
   :stroke-width "Stroke Width"

   ;; Spacing
   :p1 "Top" :p2 "Right" :p3 "Bottom" :p4 "Left"
   :column-gap "Column Gap" :row-gap "Row Gap"

   ;; Sizing
   :width "Width"
   :height "Height"
   :layout-item-min-w "Min Width"
   :layout-item-min-h "Min Height"
   :layout-item-max-w "Max Width"
   :layout-item-max-h "Max Height"

   ;; Border Radius
   :r1 "Top Left" :r2 "Top Right" :r4 "Bottom Left" :r3 "Bottom Right"

   ;; Dimensions
   :x "X" :y "Y"

   ;; Color
   :fill "Fill"
   :stroke-color "Stroke Color"})

(def ^:private dimensions-dictionary
  {:stroke-width :stroke-width
   :p1 :spacing
   :p2 :spacing
   :p3 :spacing
   :p4 :spacing
   :column-gap :spacing
   :row-gap :spacing
   :width :sizing
   :height :sizing
   :layout-item-min-w :sizing
   :layout-item-min-h :sizing
   :layout-item-max-w :sizing
   :layout-item-max-h :sizing
   :r1 :border-radius
   :r2 :border-radius
   :r4 :border-radius
   :r3 :border-radius
   :x :x
   :y :y})

(def ^:private category-dictionary
  {:stroke-width "Stroke Width"
   :spacing "Spacing"
   :sizing "Sizing"
   :border-radius "Border Radius"
   :x "X"
   :y "Y"
   :font-size "Font Size"
   :font-family "Font Family"
   :font-weight "Font Weight"
   :letter-spacing "Letter Spacing"
   :text-case "Text Case"
   :text-decoration "Text Decoration"})

(defn- partially-applied-attr
  "Translates partially applied attributes based on the dictionary."
  [app-token-keys is-applied {:keys [attributes all-attributes]}]
  (let [filtered-keys (if all-attributes
                        (filter #(contains? all-attributes %) app-token-keys)
                        (filter #(contains? attributes %) app-token-keys))]
    (when is-applied
      (str/join ", " (map attribute-dictionary filtered-keys)))))

(defn- translate-and-format
  "Translates and formats grouped values by category."
  [grouped-values]
  (str/join "\n"
            (map (fn [[category values]]
                   (if (#{:x :y} category)
                     (dm/str "- " (category-dictionary category))
                     (dm/str "- " (category-dictionary category) ": "
                             (str/join ", " (map attribute-dictionary values)) ".")))
                 grouped-values)))

(defn format-token-value
  "Converts token value of any shape to a string."
  [token-value]
  (cond
    (map? token-value)
    (->> (map (fn [[k v]] (str "- " (category-dictionary k) ": " (format-token-value v))) token-value)
         (str/join "\n")
         (str "\n"))

    (sequential? token-value)
    (str/join ", " token-value)

    :else
    (str token-value)))

(defn generate-tooltip
  "Generates a tooltip for a given token"
  [is-viewer shape theme-token token half-applied no-valid-value ref-not-in-active-set]
  (let [{:keys [name type resolved-value value]} token
        resolved-value-theme (:resolved-value theme-token)
        resolved-value (or resolved-value-theme resolved-value)
        {:keys [title] :as token-props} (dwta/get-token-properties theme-token)
        applied-tokens (:applied-tokens shape)
        app-token-vals (set (vals applied-tokens))
        app-token-keys (keys applied-tokens)
        is-applied? (contains? app-token-vals name)


        applied-to (if half-applied
                     (partially-applied-attr app-token-keys is-applied? token-props)
                     (tr "labels.all"))
        grouped-values (group-by dimensions-dictionary app-token-keys)

        base-title (dm/str "Token: " name "\n"
                           (tr "workspace.tokens.original-value" (format-token-value value)) "\n"
                           (tr "workspace.tokens.resolved-value" (format-token-value resolved-value))
                           (when (= (:type token) :number)
                             (dm/str "\n" (tr "workspace.tokens.more-options"))))]

    (cond
      ;; If there are errors, show the appropriate message
      ref-not-in-active-set
      (tr "workspace.tokens.ref-not-valid")

      no-valid-value
      (tr "workspace.tokens.value-not-valid")

      ;; If the token is applied and the user is a is-viewer, show the details
      (and is-applied? is-viewer)
      (->> [base-title
            (tr "workspace.tokens.applied-to")
            (if (= :dimensions type)
              (translate-and-format grouped-values)
              (str "- " title ": " applied-to))]
           (str/join "\n"))

      ;; Otherwise only show the base title
      :else base-title)))

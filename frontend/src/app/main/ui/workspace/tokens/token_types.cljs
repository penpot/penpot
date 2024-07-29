(ns app.main.ui.workspace.tokens.token-types
  (:require
   [app.common.data :as d :refer [ordered-map]]
   [app.common.types.token :as ctt]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [clojure.set :as set]))

(def token-types
  (ordered-map
   [:border-radius
    {:title "Border Radius"
     :attributes ctt/border-radius-keys
     :on-update-shape wtch/update-shape-radius-all
     :modal {:key :tokens/border-radius
             :fields [{:label "Border Radius"
                       :key :border-radius}]}}]
   [:stroke-width
    {:title "Stroke Width"
     :attributes ctt/stroke-width-keys
     :on-update-shape wtch/update-stroke-width
     :modal {:key :tokens/stroke-width
             :fields [{:label "Stroke Width"
                       :key :stroke-width}]}}]

   [:sizing
    {:title "Sizing"
     :attributes #{:width :height}
     :all-attributes ctt/sizing-keys
     :on-update-shape wtch/update-shape-dimensions
     :modal {:key :tokens/sizing
             :fields [{:label "Sizing"
                       :key :sizing}]}}]
   [:dimensions
    {:title "Dimensions"
     :attributes #{:width :height}
     :all-attributes (set/union
                      ctt/spacing-keys
                      ctt/sizing-keys
                      ctt/border-radius-keys
                      ctt/stroke-width-keys)
     :on-update-shape wtch/update-shape-dimensions
     :modal {:key :tokens/dimensions
             :fields [{:label "Dimensions"
                       :key :dimensions}]}}]

   [:opacity
    {:title "Opacity"
     :attributes ctt/opacity-keys
     :on-update-shape wtch/update-opacity
     :modal {:key :tokens/opacity
             :fields [{:label "Opacity"
                       :key :opacity}]}}]

   [:rotation
    {:title "Rotation"
     :attributes ctt/rotation-keys
     :on-update-shape wtch/update-rotation
     :modal {:key :tokens/rotation
             :fields [{:label "Rotation"
                       :key :rotation}]}}]
   [:spacing
    {:title "Spacing"
     :attributes ctt/spacing-keys
     :on-update-shape wtch/update-layout-spacing-column
     :modal {:key :tokens/spacing
             :fields [{:label "Spacing"
                       :key :spacing}]}}]
   (comment
     [:boolean
      {:title "Boolean"
       :modal {:key :tokens/boolean
               :fields [{:label "Boolean"}]}}]

     [:box-shadow
      {:title "Box Shadow"
       :modal {:key :tokens/box-shadow
               :fields [{:label "Box shadows"
                         :key :box-shadow
                         :type :box-shadow}]}}]

     [:numeric
      {:title "Numeric"
       :modal {:key :tokens/numeric
               :fields [{:label "Numeric"
                         :key :numeric}]}}]

     [:other
      {:title "Other"
       :modal {:key :tokens/other
               :fields [{:label "Other"
                         :key :other}]}}]
     [:string
      {:title "String"
       :modal {:key :tokens/string
               :fields [{:label "String"
                         :key :string}]}}]
     [:typography
      {:title "Typography"
       :modal {:key :tokens/typography
               :fields [{:label "Font" :key :font-family}
                        {:label "Weight" :key :weight}
                        {:label "Font Size" :key :font-size}
                        {:label "Line Height" :key :line-height}
                        {:label "Letter Spacing" :key :letter-spacing}
                        {:label "Paragraph Spacing" :key :paragraph-spacing}
                        {:label "Paragraph Indent" :key :paragraph-indent}
                        {:label "Text Decoration" :key :text-decoration}
                        {:label "Text Case" :key :text-case}]}}])))

(defn get-token-properties [token]
  (get token-types (:type token)))

(defn token-attributes [token-type]
  (get-in token-types [token-type :attributes]))

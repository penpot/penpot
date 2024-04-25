;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.core
  (:require
   [app.common.data :as d :refer [ordered-map]]
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace.changes :as dch]))

;; Helpers ---------------------------------------------------------------------

(defn token-applied?
  "Test if `token` is applied to a `shape` with the given `token-attributes`."
  [token shape token-attributes]
  (let [{:keys [id]} token
        applied-tokens (get shape :applied-tokens {})]
    (some (fn [attr]
            (= (get applied-tokens attr) id))
          token-attributes)))

(defn tokens-applied?
  "Test if `token` is applied to to any of `shapes` with the given `token-attributes`."
  [token shapes token-attributes]
  (some #(token-applied? token % token-attributes) shapes))

;; Update functions ------------------------------------------------------------

(defn update-shape-radius [value shape-ids]
  (let [parsed-value (d/parse-integer value)]
    (dch/update-shapes shape-ids
                       (fn [shape]
                         (when (ctsr/has-radius? shape)
                           (ctsr/set-radius-1 shape parsed-value)))
                       {:reg-objects? true
                        :attrs [:rx :ry :r1 :r2 :r3 :r4]})))

;; Token types -----------------------------------------------------------------

(def token-types
  (ordered-map
   [:boolean {:title "Boolean"
              :modal {:key :tokens/boolean
                      :fields [{:label "Boolean"}]}}]
   [:border-radius {:title "Border Radius"
                    :attributes #{:rx :ry :r1 :r2 :r3 :r4}
                    :on-apply dt/update-token-from-attributes
                    :modal {:key :tokens/border-radius
                            :fields [{:label "Border Radius"
                                      :key :border-radius}]}
                    :on-update-shape update-shape-radius}]
   [:box-shadow
    {:title "Box Shadow"
     :modal {:key :tokens/box-shadow
             :fields [{:label "Box shadows"
                       :key :box-shadow
                       :type :box-shadow}]}}]
   [:sizing
    {:title "Sizing"
     :modal {:key :tokens/sizing
             :fields [{:label "Sizing"
                       :key :sizing}]}}]
   [:dimension
    {:title "Dimension"
     :modal {:key :tokens/dimensions
             :fields [{:label "Dimensions"
                       :key :dimensions}]}}]
   [:numeric
    {:title "Numeric"
     :modal {:key :tokens/numeric
             :fields [{:label "Numeric"
                       :key :numeric}]}}]
   [:opacity
    {:title "Opacity"
     :modal {:key :tokens/opacity
             :fields [{:label "Opacity"
                       :key :opacity}]}}]
   [:other
    {:title "Other"
     :modal {:key :tokens/other
             :fields [{:label "Other"
                       :key :other}]}}]
   [:rotation
    {:title "Rotation"
     :modal {:key :tokens/rotation
             :fields [{:label "Rotation"
                       :key :rotation}]}}]
   [:spacing
    {:title "Spacing"
     :modal {:key :tokens/spacing
             :fields [{:label "Spacing"
                       :key :spacing}]}}]
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
                      {:label "Text Case" :key :text-case}]}}]))

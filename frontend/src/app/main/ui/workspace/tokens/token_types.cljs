;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.token-types
  (:require
   [app.common.data :as d :refer [ordered-map]]
   [app.common.types.token :as ctt]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [clojure.set :as set]))

(def token-types
  (ordered-map
   :border-radius
   {:title "Border Radius"
    :attributes ctt/border-radius-keys
    :on-update-shape wtch/update-shape-radius-all
    :modal {:key :tokens/border-radius
            :fields [{:label "Border Radius"
                      :key :border-radius}]}}

   :color
   {:title "Color"
    :attributes #{:fill}
    :all-attributes ctt/color-keys
    :on-update-shape wtch/update-fill-stroke
    :modal {:key :tokens/color
            :fields [{:label "Color" :key :color}]}}

   :stroke-width
   {:title "Stroke Width"
    :attributes ctt/stroke-width-keys
    :on-update-shape wtch/update-stroke-width
    :modal {:key :tokens/stroke-width
            :fields [{:label "Stroke Width"
                      :key :stroke-width}]}}

   :sizing
   {:title "Sizing"
    :attributes #{:width :height}
    :all-attributes ctt/sizing-keys
    :on-update-shape wtch/update-shape-dimensions
    :modal {:key :tokens/sizing
            :fields [{:label "Sizing"
                      :key :sizing}]}}
   :dimensions
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
                      :key :dimensions}]}}

   :opacity
   {:title "Opacity"
    :attributes ctt/opacity-keys
    :on-update-shape wtch/update-opacity
    :modal {:key :tokens/opacity
            :fields [{:label "Opacity"
                      :key :opacity}]}}

   :rotation
   {:title "Rotation"
    :attributes ctt/rotation-keys
    :on-update-shape wtch/update-rotation
    :modal {:key :tokens/rotation
            :fields [{:label "Rotation"
                      :key :rotation}]}}
   :spacing
   {:title "Spacing"
    :attributes #{:column-gap :row-gap}
    :all-attributes ctt/spacing-keys
    :on-update-shape wtch/update-layout-spacing
    :modal {:key :tokens/spacing
            :fields [{:label "Spacing"
                      :key :spacing}]}}))

(defn get-token-properties [token]
  (get token-types (:type token)))

(defn token-attributes [token-type]
  (get-in token-types [token-type :attributes]))

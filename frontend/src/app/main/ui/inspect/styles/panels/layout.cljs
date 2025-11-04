;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.layout
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def ^:private properties
  [:display
   :flex-direction
   :flex-wrap
   :grid-template-rows
   :grid-template-columns
   :align-items
   :align-content
   :justify-items
   :justify-content
   :row-gap
   :column-gap
   :gap
   :padding-inline-start
   :padding-inline-end
   :padding-block-start
   :padding-block-end])

(def ^:private shape-prop->padding-prop
  {:padding-block-start :p1
   :padding-inline-end :p2
   :padding-block-end :p3
   :padding-inline-start :p4})

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (let [padding-prop (get shape-prop->padding-prop property)]
    (if padding-prop
      (get shape-tokens padding-prop)
      (get shape-tokens property))))

(defn- get-resolved-token
  [property shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens property)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

(mf/defc layout-panel*
  [{:keys [shapes objects resolved-tokens]}]
  [:div {:class (stl/css :variants-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class "layout-shape"}
      (for [property properties]
        (when-let [value (css/get-css-value objects shape property)]
          (let [property-name (cmm/get-css-rule-humanized property)
                resolved-token (get-resolved-token property shape resolved-tokens)
                property-value (if (not resolved-token) (css/get-css-property objects shape property) "")]
            [:> properties-row* {:key (dm/str "layout-property-" property)
                                 :term property-name
                                 :detail value
                                 :token resolved-token
                                 :property property-value
                                 :copiable true}])))])])

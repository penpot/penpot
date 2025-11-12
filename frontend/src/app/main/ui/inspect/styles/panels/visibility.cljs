;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.visibility
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def ^:private properties
  [:opacity
   :blend-mode])

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (get shape-tokens property))

(defn- get-resolved-token
  [property shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens property)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

(mf/defc visibility-panel*
  [{:keys [shapes objects resolved-tokens]}]
  [:div {:class (stl/css :visibility-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class "visibility-shape"}
      (for [property properties]
        (when-let [value (css/get-css-value objects shape property)]
          (let [property-name (cmm/get-css-rule-humanized property)
                resolved-token (get-resolved-token property shape resolved-tokens)
                property-value (if (not resolved-token) (css/get-css-property objects shape property) "")]
            [:> properties-row* {:key (dm/str "visibility-property-" property)
                                 :term property-name
                                 :detail (dm/str value)
                                 :token resolved-token
                                 :property property-value
                                 :copiable true}])))])])

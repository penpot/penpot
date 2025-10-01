;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.text :as txt]
   [app.common.types.text :as types.text]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc text-panel*
  [{:keys [shapes objects resolved-tokens]}]
  [:div {:class (stl/css :text-panel)}
  (for [shape shapes]
    (let [style-text-blocks (->> (:content shape)
                                 (txt/content->text+styles)
                                 (remove (fn [[_ text]] (str/empty? (str/trim text))))
                                 (mapv (fn [[style text]] (vector (merge (types.text/get-default-text-attrs) style) text))))]

      [:div {:key (:id shape) :class "text-shape"}
       (for [[style text] style-text-blocks]
         (let [_ (prn "full-style" style)
               _ (prn "text" text)]
           [:div {:key (:id style)} text]))]))])


#_(for [property properties]
        (when-let [value (css/get-css-value objects shape property)]
          (let [property-name (cmm/get-css-rule-humanized property)
                resolved-token (get-resolved-token property shape resolved-tokens)
                property-value (if (not resolved-token) (css/get-css-property objects shape property) "")]
            [:> properties-row* {:key (dm/str "text-property-" property)
                                 :term property-name
                                 :detail value
                                 :token resolved-token
                                 :property property-value
                                 :copiable true}])))

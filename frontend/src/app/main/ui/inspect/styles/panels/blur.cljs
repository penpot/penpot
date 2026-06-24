;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.styles.panels.blur
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(mf/defc blur-panel*
  [{:keys [shapes objects]}]
  [:div {:class (stl/css :blur-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class (stl/css :blur-shape)}
      (let [blur-property :filter
            blur-value (css/get-css-value objects shape blur-property)
            background-blur-property :backdrop-filter
            blur-property-name (cmm/get-css-rule-humanized blur-property)
            blur-property-value (css/get-css-property objects shape blur-property)
            background-blur-value (css/get-css-value objects shape background-blur-property)
            background-blur-property-name (cmm/get-css-rule-humanized background-blur-property)
            background-blur-property-value (css/get-css-property objects shape background-blur-property)]
        [:div
         (when blur-property-value
           [:> properties-row* {:key (dm/str "blur-property-" blur-property)
                                :term blur-property-name
                                :detail (dm/str blur-value)
                                :property blur-property-value
                                :copiable true}])
         (when background-blur-property-value
           [:> properties-row* {:key (dm/str "blur-property-" background-blur-property)
                                :term background-blur-property-name
                                :detail (dm/str background-blur-value)
                                :property background-blur-property-value
                                :copiable true}])])])])

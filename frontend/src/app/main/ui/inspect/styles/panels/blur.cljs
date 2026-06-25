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
   [app.util.code-gen.style-css-formats :refer [format-blur]]
   [rumext.v2 :as mf]))

(mf/defc blur-panel*
  [{:keys [shapes]}]
  [:div {:class (stl/css :blur-panel)}
   (for [shape shapes]
     [:div {:key (:id shape) :class (stl/css :blur-shape)}
      (let [blur-property :filter
            blue-value-raw (get-in shape [:blur :value])
            blur-value-detail (format-blur blue-value-raw)
            blur-property-name (cmm/get-css-rule-humanized blur-property)
            blur-property-value (css/format-css-property [blur-property blue-value-raw] {})

            background-blur-property :backdrop-filter
            background-blur-value-raw (get-in shape [:background-blur :value])
            background-blur-value-detail (format-blur background-blur-value-raw)
            background-blur-property-name (cmm/get-css-rule-humanized background-blur-property)
            background-blur-property-value (css/format-css-property [background-blur-property background-blur-value-raw] {})]
        [:div
         (when blue-value-raw
           [:> properties-row* {:key (dm/str "blur-property-" blur-property)
                                :term blur-property-name
                                :detail blur-value-detail
                                :property blur-property-value
                                :copiable true}])
         (when background-blur-value-raw
           [:> properties-row* {:key (dm/str "blur-property-" background-blur-property)
                                :term background-blur-property-name
                                :detail background-blur-value-detail
                                :property background-blur-property-value
                                :copiable true}])])])])

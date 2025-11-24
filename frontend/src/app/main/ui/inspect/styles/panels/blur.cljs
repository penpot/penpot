;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
      (let [property :filter
            value (css/get-css-value objects shape property)
            property-name (cmm/get-css-rule-humanized property)
            property-value (css/get-css-property objects shape property)]
        [:> properties-row* {:key (dm/str "blur-property-" property)
                             :term property-name
                             :detail (dm/str value)
                             :property property-value
                             :copiable true}])])])

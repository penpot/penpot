;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.shadow
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(mf/defc shadow-panel*
  [{:keys [shapes color-space]}]
  [:div {:class (stl/css :shadow-panel)}
   (for [shape shapes]
     (for [shadow (:shadow shape)]
       [:div {:key (dm/str (:id shape) (:type shadow)) :class "shadow-shape"}
        [:> color-properties-row* {:term "Shadow Color"
                                   :color (:color shadow)
                                   :format color-space
                                   :copiable true}]
        (let [value (dm/str (:offset-x shadow) "px" " " (:offset-y shadow) "px" " " (:blur shadow) "px" " " (:spread shadow) "px")
              property-name (cmm/get-css-rule-humanized (:style shadow))
              property-value (css/shadow->css shadow)]
          [:> properties-row* {:term property-name
                               :detail (dm/str value)
                               :property property-value
                               :copiable true}])]))])

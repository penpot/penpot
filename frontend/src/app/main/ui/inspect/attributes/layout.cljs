;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.layout
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.inspect.attributes.common :as cmm]
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
   :padding])

(mf/defc layout-block
  [{:keys [objects shape]}]
  (for [property properties]
    (when-let [value (css/get-css-value objects shape property)]
      (let [property-name (cmm/get-css-rule-humanized property)]
        [:div {:class (stl/css :layout-row)
               :key   (dm/str "layout-" (:id shape) "-" (d/name property))}
         [:div {:title property-name
                :class (stl/css :global/attr-label)}
          property-name]
         [:div {:class (stl/css :global/attr-value)}

          [:> copy-button* {:data (css/get-css-property objects shape property)}
           [:div {:class (stl/css :button-children)} value]]]]))))

(mf/defc layout-panel
  [{:keys [objects shapes]}]
  (let [shapes (->> shapes (filter ctl/any-layout?))]

    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title "Layout"
         :class (stl/css :title-spacing-layout)}

        (when (= (count shapes) 1)
          [:> copy-button* {:data (css/get-shape-properties-css objects (first shapes) properties)
                            :class (stl/css :copy-btn-title)}])]

       (for [shape shapes]
         [:& layout-block {:shape shape
                           :objects objects
                           :key (:id shape)}])])))

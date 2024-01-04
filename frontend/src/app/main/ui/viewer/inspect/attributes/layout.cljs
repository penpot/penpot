;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.layout
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def properties
  [:display
   :flex-direction
   :flex-wrap
   :grid-template-rows
   :grid-template-columns
   :align-items
   :align-content
   :justify-items
   :justify-content
   :gap
   :padding])

(mf/defc layout-block
  [{:keys [objects shape]}]
  (for [property properties]
    (when-let [value (css/get-css-value objects shape property)]
      [:div {:class (stl/css :layout-row)}
       [:div {:title (d/name property)
              :class (stl/css :global/attr-label)} (d/name property)]
       [:div {:class (stl/css :global/attr-value)}

        [:& copy-button {:data (css/get-css-property objects shape property)}
         [:div {:class (stl/css :button-children)} value]]]])))

(mf/defc layout-panel
  [{:keys [objects shapes]}]
  (let [shapes (->> shapes (filter ctl/any-layout?))]

    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:& title-bar {:collapsable? false
                      :title        "Layout"
                      :class        (stl/css :title-spacing-layout)}

        (when (= (count shapes) 1)
          [:& copy-button {:data (css/get-shape-properties-css objects (first shapes) properties)}])]

       (for [shape shapes]
         [:& layout-block {:shape shape
                           :objects objects
                           :key (:id shape)}])])))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.layout-element
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.util.code-gen.style-css :as css]
   [rumext.v2 :as mf]))

(def properties
  [:margin
   :max-height
   :min-height
   :max-width
   :min-width
   :align-self
   :justify-self
   :flex-shrink
   :flex

   ;; Grid cell properties
   :grid-column
   :grid-row])

(mf/defc layout-element-block
  [{:keys [objects shape]}]
  (for [property properties]
    (when-let [value (css/get-css-value objects shape property)]
      (let [property-name (cmm/get-css-rule-humanized property)]
        [:div {:class (stl/css :layout-element-row)
               :key (dm/str "layout-element-" (:id shape) "-" (d/name property))}
         [:div {:class (stl/css :global/attr-label)} property-name]
         [:div {:class (stl/css :global/attr-value)}

          [:> copy-button* {:data (css/get-css-property objects shape property)}
           [:div {:class (stl/css :button-children)} value]]]]))))

(mf/defc layout-element-panel
  [{:keys [objects shapes]}]
  (let [shapes (->> shapes (filter #(ctl/any-layout-immediate-child? objects %)))
        only-flex? (every? #(ctl/flex-layout-immediate-child? objects %) shapes)
        only-grid? (every? #(ctl/grid-layout-immediate-child? objects %) shapes)

        some-layout-prop?
        (->> shapes
             (mapcat (fn [shape]
                       (keep #(css/get-css-value objects shape %) properties)))
             (seq))

        menu-title
        (cond
          only-flex?
          "Flex element"
          only-grid?
          "Flex element"
          :else
          "Layout element")]

    (when some-layout-prop?
      [:div {:class (stl/css :attributes-block)}
       [:& title-bar {:collapsable false
                      :title       menu-title
                      :class       (stl/css :title-spacing-layout-element)}
        (when (= (count shapes) 1)
          [:> copy-button* {:data  (css/get-shape-properties-css objects (first shapes) properties)
                            :class (stl/css :copy-btn-title)}])]

       (for [shape shapes]
         [:& layout-element-block {:shape shape
                                   :objects objects
                                   :key (:id shape)}])])))

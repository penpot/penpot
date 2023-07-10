;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.layout-element
  (:require
   [app.common.data :as d]
   [app.common.types.shape.layout :as ctl]
   [app.main.ui.components.copy-button :refer [copy-button]]
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

   ;; Grid cell properties
   :grid-column
   :grid-row])

(mf/defc layout-element-block
  [{:keys [objects shape]}]
  [:*
   (for [property properties]
     (when-let [value (css/get-css-value objects shape property)]
       [:div.attributes-unit-row
        [:div.attributes-label (d/name property)]
        [:div.attributes-value value]
        [:& copy-button {:data (css/get-css-property objects shape property)}]]))])

(mf/defc layout-element-panel
  [{:keys [objects shapes]}]
  (let [shapes (->> shapes (filter #(ctl/any-layout-immediate-child? objects %)))
        only-flex? (every? #(ctl/flex-layout-immediate-child? objects %) shapes)
        only-grid? (every? #(ctl/grid-layout-immediate-child? objects %) shapes)]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (cond
                                            only-flex?
                                            "Flex element"
                                            only-grid?
                                            "Flex element"
                                            :else
                                            "Layout element"
                                            )]
        (when (= (count shapes) 1)
          [:& copy-button {:data (css/get-shape-properties-css objects (first shapes) properties)}])]

       (for [shape shapes]
         [:& layout-element-block {:shape shape
                                   :objects objects
                                   :key (:id shape)}])])))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.layout-flex
  (:require
   [app.common.data :as d]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.formats :as fm]
   [app.util.code-gen :as cg]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def properties [:layout
                 :layout-flex-dir
                 :layout-align-items
                 :layout-justify-content
                 :layout-gap
                 :layout-padding
                 :layout-wrap-type])

(def align-contet-prop [:layout-align-content])

(def layout-flex-params
  {:props   [:layout
             :layout-align-items
             :layout-flex-dir
             :layout-justify-content
             :layout-gap
             :layout-padding
             :layout-wrap-type]
   :to-prop {:layout "display"
             :layout-flex-dir "flex-direction"
             :layout-align-items "align-items"
             :layout-justify-content "justify-content"
             :layout-wrap-type "flex-wrap"
             :layout-gap "gap"
             :layout-padding "padding"}
   :format  {:layout d/name
             :layout-flex-dir d/name
             :layout-align-items d/name
             :layout-justify-content d/name
             :layout-wrap-type d/name
             :layout-gap fm/format-gap
             :layout-padding fm/format-padding}})

(def layout-align-content-params
  {:props   [:layout-align-content]
   :to-prop {:layout-align-content "align-content"}
   :format  {:layout-align-content d/name}})

(defn copy-data
  ([shape]
   (let [properties-for-copy (if (:layout-align-content shape)
                               (into [] (concat properties align-contet-prop))
                               properties)]
     (apply copy-data shape properties-for-copy)))

  ([shape & properties]
   (let [params (if (:layout-align-content shape)
                  (d/deep-merge layout-align-content-params layout-flex-params )
                  layout-flex-params)]
     (cg/generate-css-props shape properties params))))

(mf/defc manage-padding
  [{:keys [padding type]}]
  (let [values (fm/format-padding-margin-shorthand (vals padding))]
    [:div.attributes-value
     {:title (str (str/join "px " (vals values)) "px")}
     (for [[k v] values]
       [:span.items {:key (str type "-" k "-" v)} v "px"])]))

(mf/defc layout-flex-block
  [{:keys [shape]}]
  [:*
   [:div.attributes-unit-row
    [:div.attributes-label "Display"]
    [:div.attributes-value "Flex"]
    [:& copy-button {:data (copy-data shape)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Direction"]
    [:div.attributes-value (str/capital (d/name (:layout-flex-dir shape)))]
    [:& copy-button {:data (copy-data shape :layout-flex-dir)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Align-items"]
    [:div.attributes-value (str/capital (d/name (:layout-align-items shape)))]
    [:& copy-button {:data (copy-data shape :layout-align-items)}]]
   
   [:div.attributes-unit-row
    [:div.attributes-label "Justify-content"]
    [:div.attributes-value  (str/capital (d/name (:layout-justify-content shape)))]
    [:& copy-button {:data (copy-data shape :layout-justify-content)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Flex wrap"]
    [:div.attributes-value (str/capital (d/name (:layout-wrap-type shape)))]
    [:& copy-button {:data (copy-data shape :layout-wrap-type)}]]
   
   (when (= :wrap (:layout-wrap-type shape))
     [:div.attributes-unit-row
      [:div.attributes-label "Align-content"]
      [:div.attributes-value  (str/capital (d/name (:layout-align-content shape)))]
      [:& copy-button {:data (copy-data shape :layout-align-content)}]])
   
   [:div.attributes-unit-row
    [:div.attributes-label "Gap"]
    (if (=  (:row-gap (:layout-gap shape))  (:column-gap (:layout-gap shape)))
      [:div.attributes-value
       [:span (str/capital (d/name (:row-gap (:layout-gap shape)))) "px"]]
      [:div.attributes-value
       [:span.items (:row-gap (:layout-gap shape)) "px"]
       [:span (:column-gap (:layout-gap shape)) "px"]])
    [:& copy-button {:data (copy-data shape :layout-gap)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Padding"]
    [:& manage-padding {:padding (:layout-padding shape) :type "padding"}]
    [:& copy-button {:data (copy-data shape :layout-padding)}]]])

(defn has-flex? [shape]
  (= :flex (:layout shape)))

(mf/defc layout-flex-panel
  [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-flex?))] 
   (when (seq shapes)
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text "Layout"]
      (when (= (count shapes) 1)
        [:& copy-button {:data (copy-data (first shapes))}])]

     (for [shape shapes]
       [:& layout-flex-block {:shape shape
                              :key (:id shape)}])])))

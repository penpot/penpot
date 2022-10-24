;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.handoff.attributes.layout-flex-element
  (:require
   [app.common.data :as d]
   [app.main.refs :as refs]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks :as hooks]
   [app.util.code-gen :as cg]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))


(defn format-margin
  [margin-values]
  (let [short-hand (fmt/format-padding-margin-shorthand (vals margin-values))
        parsed-values (map #(str/fmt "%spx" %) (vals short-hand))]
    (str/join " " parsed-values)))

(def properties [:layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
                 :layout-item-h-sizing    ;; :fill-width :fix-width :auto-width
                 :layout-item-v-sizing    ;; :fill-height :fix-height :auto-height
                 :layout-item-max-h       ;; num
                 :layout-item-min-h       ;; num
                 :layout-item-max-w       ;; num
                 :layout-item-min-w       ;; num
                 :layout-item-align-self  ;; :start :end :center :strech :baseline
                 ])


(def layout-flex-item-params
  {:props   [:layout-item-margin
             :layout-item-h-sizing
             :layout-item-v-sizing
             :layout-item-max-h
             :layout-item-min-h
             :layout-item-max-w
             :layout-item-min-w
             :layout-item-align-self]
   :to-prop {:layout-item-margin "margin"
             :layout-item-h-sizing "width"
             :layout-item-v-sizing "height"
             :layout-item-align-self "align-self"
             :layout-item-max-h "max. height"
             :layout-item-min-h "min. height"
             :layout-item-max-w "max. width"
             :layout-item-min-w "min. width"}
   :format  {:layout-item-margin format-margin
             :layout-item-h-sizing name
             :layout-item-v-sizing name
             :layout-item-align-self name}})

(defn copy-data
  ([shape]
   (apply copy-data shape properties))

  ([shape & properties]
   (cg/generate-css-props shape properties layout-flex-item-params)))

(mf/defc manage-margin
  [{:keys [margin type]}]
  (let [values (fmt/format-padding-margin-shorthand (vals margin))]
    [:div.attributes-value
     (for [[k v] values]
       [:span.items {:key (str type "-" k "-" v)} v "px"])]))

(mf/defc layout-element-block
  [{:keys [shape]}]
  [:*
   [:div.attributes-unit-row
    [:div.attributes-label "Width"]
    [:div.attributes-value (str/capital (d/name (:layout-item-h-sizing shape)))]
    [:& copy-button {:data (copy-data shape :layout-item-h-sizing)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Height"]
    [:div.attributes-value (str/capital (d/name (:layout-item-v-sizing shape)))]
    [:& copy-button {:data (copy-data shape :layout-item-v-sizing)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Align self"]
    [:div.attributes-value  (str/capital (d/name (:layout-item-align-self shape)))]
    [:& copy-button {:data (copy-data shape :layout-item-align-self)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Margin"]
    [:& manage-margin {:margin (:layout-item-margin shape) :type "margin"}]
    [:& copy-button {:data (copy-data shape :layout-item-margin)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Max. width"]
    [:div.attributes-value (fmt/format-pixels (:layout-item-max-w shape))]
    [:& copy-button {:data (copy-data shape :layout-item-max-w)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Min. width"]
    [:div.attributes-value (fmt/format-pixels (:layout-item-min-w shape))]
    [:& copy-button {:data (copy-data shape :layout-item-min-w)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Max. height"]
    [:div.attributes-value (fmt/format-pixels (:layout-item-max-h shape))]
    [:& copy-button {:data (copy-data shape :layout-item-max-h)}]]

   [:div.attributes-unit-row
    [:div.attributes-label "Min. height"]
    [:div.attributes-value (fmt/format-pixels (:layout-item-min-w shape))]
    [:& copy-button {:data (copy-data shape :layout-item-min-h)}]]])

(defn get-flex-elements [page-id shapes]
  (let [ids (mapv :id shapes)
        ids (hooks/use-equal-memo ids)
        get-layout-children-refs (mf/use-memo (mf/deps ids page-id) #(refs/get-flex-child-viewer? ids page-id))]

    (mf/deref get-layout-children-refs)))

(mf/defc layout-flex-element-panel
  [{:keys [shapes]}]
  (let [route     (mf/deref refs/route)
        page-id   (:page-id (:query-params route))
        shapes    (get-flex-elements page-id shapes)]
    (when (and (= (count shapes) 1) (seq shapes))
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text "Flex element"]
        [:& copy-button {:data (copy-data (first shapes))}]]

       [:& layout-element-block {:shape (first shapes)}]])))

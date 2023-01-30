;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.layout-flex-element
  (:require
   [app.common.data :as d]
   [app.main.refs :as refs]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.viewer.inspect.code :as cd]
   [app.util.code-gen :as cg]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))


(defn format-margin
  [margin-values]
  (let [short-hand    (fmt/format-padding-margin-shorthand (vals margin-values))
        parsed-values (map #(str/fmt "%spx" %) (vals short-hand))]
    (str/join " " parsed-values)))

(def properties [:layout-item-margin       ;; {:m1 0 :m2 0 :m3 0 :m4 0}
                 :layout-item-max-h        ;; num
                 :layout-item-min-h        ;; num
                 :layout-item-max-w        ;; num
                 :layout-item-min-w        ;; num
                 :layout-item-align-self]) ;; :start :end :center

(def layout-flex-item-params
  {:props   [:layout-item-margin
             :layout-item-max-h
             :layout-item-min-h
             :layout-item-max-w
             :layout-item-min-w
             :layout-item-align-self]
   :to-prop {:layout-item-margin "margin"
             :layout-item-align-self "align-self"
             :layout-item-max-h "max-height"
             :layout-item-min-h "min-height"
             :layout-item-max-w "max-width"
             :layout-item-min-w "min-width"}
   :format  {:layout-item-margin format-margin
             :layout-item-align-self d/name}})

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

(defn manage-sizing
  [value type]
  (let [ref-value-h {:fill "Width 100%"
                     :fix  "Fixed width"
                     :auto "Fit content"}
        ref-value-v {:fill "Height 100%"
                     :fix  "Fixed height"
                     :auto "Fit content"}]
    (if (= :h type)
      (ref-value-h value)
      (ref-value-v value))))

(mf/defc layout-element-block
  [{:keys [shape]}]
  (let [old-margin (:layout-item-margin shape)
        new-margin {:m1 0 :m2 0 :m3 0 :m4 0}
        merged-margin (merge new-margin old-margin)
        shape (assoc shape :layout-item-margin merged-margin)]

    [:*
     (when (:layout-item-align-self shape)
       [:div.attributes-unit-row
        [:div.attributes-label "Align self"]
        [:div.attributes-value  (str/capital (d/name (:layout-item-align-self shape)))]
        [:& copy-button {:data (copy-data shape :layout-item-align-self)}]])

     (when (:layout-item-margin shape)
       [:div.attributes-unit-row
        [:div.attributes-label "Margin"]
        [:& manage-margin {:margin merged-margin :type "margin"}]
        [:& copy-button {:data (copy-data shape :layout-item-margin)}]])

     (when (:layout-item-h-sizing shape)
       [:div.attributes-unit-row
        [:div.attributes-label "Horizontal sizing"]
         [:div.attributes-value (manage-sizing (:layout-item-h-sizing shape) :h)]
        [:& copy-button {:data (copy-data shape :layout-item-h-sizing)}]])

     (when (:layout-item-v-sizing shape)
       [:div.attributes-unit-row
        [:div.attributes-label "Vertical sizing"]
        [:div.attributes-value (manage-sizing (:layout-item-v-sizing shape) :v)]
        [:& copy-button {:data (copy-data shape :layout-item-v-sizing)}]])

     (when (= :fill (:layout-item-h-sizing shape))
       [:*
        (when (some? (:layout-item-max-w shape))
          [:div.attributes-unit-row
           [:div.attributes-label "Max. width"]
           [:div.attributes-value (fmt/format-pixels (:layout-item-max-w shape))]
           [:& copy-button {:data (copy-data shape :layout-item-max-w)}]])

        (when (some? (:layout-item-min-w shape))
          [:div.attributes-unit-row
           [:div.attributes-label "Min. width"]
           [:div.attributes-value (fmt/format-pixels (:layout-item-min-w shape))]
           [:& copy-button {:data (copy-data shape :layout-item-min-w)}]])])

     (when (= :fill (:layout-item-v-sizing shape))
       [:*
        (when (:layout-item-max-h shape)
          [:div.attributes-unit-row
           [:div.attributes-label "Max. height"]
           [:div.attributes-value (fmt/format-pixels (:layout-item-max-h shape))]
           [:& copy-button {:data (copy-data shape :layout-item-max-h)}]])

        (when (:layout-item-min-h shape)
          [:div.attributes-unit-row
           [:div.attributes-label "Min. height"]
           [:div.attributes-value (fmt/format-pixels (:layout-item-min-h shape))]
           [:& copy-button {:data (copy-data shape :layout-item-min-h)}]])])]))

(mf/defc layout-flex-element-panel
  [{:keys [shapes from]}]
  (let [route        (mf/deref refs/route)
        page-id      (:page-id (:query-params route))
        mod-shapes   (cd/get-flex-elements page-id shapes from)
        shape        (first mod-shapes)
        has-margin?  (some? (:layout-item-margin shape))
        has-values?  (or (some? (:layout-item-max-w shape))
                         (some? (:layout-item-max-h shape))
                         (some? (:layout-item-min-w shape))
                         (some? (:layout-item-min-h shape)))
        has-align?   (some? (:layout-item-align-self shape))
        has-sizing?  (or (some? (:layout-item-h-sizing shape))
                         (some? (:layout-item-w-sizing shape)))
        must-show    (or has-margin? has-values? has-align? has-sizing?)]
    (when (and (= (count mod-shapes) 1) must-show)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text "Flex element"]
        [:& copy-button {:data (copy-data shape)}]]

       [:& layout-element-block {:shape shape}]])))

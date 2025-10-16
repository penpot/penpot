;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.stroke
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.inspect.attributes.common :as cmm]
   [app.util.code-gen.style-css :as css]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private properties [:border-style :border-width])

(defn- stroke->color [shape]
  {:color (:stroke-color shape)
   :opacity (:stroke-opacity shape)
   :gradient (:stroke-color-gradient shape)
   :id (:stroke-color-ref-id shape)
   :file-id (:stroke-color-ref-file shape)
   :image (:stroke-image shape)})

(defn- has-stroke? [shape]
  (seq (:strokes shape)))

;; DEPRECATED, use stroke-block-styles* instead.
;; This component is kept for backward compatibility
(mf/defc stroke-block
  {::mf/wrap-props false}
  [{:keys [objects shape stroke]}]
  (let [format*   (mf/use-state :hex)
        format    (deref format*)
        color     (stroke->color stroke)
        on-change
        (mf/use-fn
         (fn [format]
           (reset! format* format)))]
    [:div {:class (stl/css :attributes-fill-block)}
     (for [property properties]
       (let [property-name (cmm/get-css-rule-humanized property)
             property-value (css/get-css-value objects stroke property)]
         [:div {:class (stl/css :stroke-row) :key   (str "stroke-" (:id shape) "-" property)}
          [:div {:class (stl/css :global/attr-label)}
           property-name]
          [:div {:class (stl/css :global/attr-value)}

           [:> copy-button* {:data (css/get-css-property objects stroke property)}
            [:div {:class (stl/css :button-children)} property-value]]]]))
     [:& cmm/color-row
      {:color color
       :property "Border color"
       :format format
       :on-change-format on-change
       :copy-data (css/get-shape-properties-css objects {:strokes [shape]} properties)}]]))

;; New implementation of stroke-block using the new color format selector
;; This component is used when the flag :inspect-styles is enabled. Update when flag no longer needed.
(mf/defc stroke-block-styles*
  [{:keys [objects shape stroke color-space]}]
  (let [color     (stroke->color stroke)]
    [:div {:class (stl/css :attributes-fill-block)}
     (for [property properties]
       (let [property-name (cmm/get-css-rule-humanized property)
             property-value (css/get-css-value objects stroke property)]
         [:div {:class (stl/css :stroke-row) :key   (str "stroke-" (:id shape) "-" property)}
          [:div {:class (stl/css :global/attr-label)}
           property-name]
          [:div {:class (stl/css :global/attr-value)}

           [:> copy-button* {:data (css/get-css-property objects stroke property)}
            [:div {:class (stl/css :button-children)} property-value]]]]))
     [:> cmm/color-row
      {:color color
       :property "Border color"
       :format (d/nilv (keyword color-space) :hex)
       :copy-data (css/get-shape-properties-css objects {:strokes [shape]} properties {:format (keyword color-space)})}]]))

(mf/defc stroke-panel*
  [{:keys [shapes color-space]}]
  (let [shapes (->> shapes (filter has-stroke?))]
    (when (seq shapes)
      [:div {:class (stl/css :attributes-block)}
       [:> inspect-title-bar*
        {:title (tr "inspect.attributes.stroke")
         :class (stl/css :title-spacing-stroke)}]

       [:div {:class (stl/css :attributes-content)}
        (for [shape shapes]
          (for [stroke (:strokes shape)]

            (if (contains? cf/flags :inspect-styles)
              [:> stroke-block-styles* {:key (str "stroke-color-" (:id shape) stroke)
                                        :shape shape
                                        :color-space color-space
                                        :stroke stroke}]
              [:& stroke-block {:key (str "stroke-color-" (:id shape) stroke)
                                :shape shape
                                :stroke stroke}])))]])))

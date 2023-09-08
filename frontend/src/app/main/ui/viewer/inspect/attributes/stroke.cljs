;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.stroke
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.formats :as fmt]
   [app.main.ui.viewer.inspect.attributes.common :refer [color-row]]
   [app.util.code-gen.style-css-formats :as cssf]
   [app.util.code-gen.style-css-values :as cssv]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn stroke->color [shape]
  {:color (:stroke-color shape)
   :opacity (:stroke-opacity shape)
   :gradient (:stroke-color-gradient shape)
   :id (:stroke-color-ref-id shape)
   :file-id (:stroke-color-ref-file shape)
   :image (:stroke-image shape)})

(defn has-stroke? [shape]
  (seq (:strokes shape)))

(mf/defc stroke-block
  [{:keys [stroke]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        color-format   (mf/use-state :hex)
        color          (stroke->color stroke)]
    (if new-css-system
      [:div {:class (stl/css :attributes-stroke-block)}
       (let [{:keys [stroke-style stroke-alignment]} stroke
             stroke-style (if (= stroke-style :svg) :solid stroke-style)
             stroke-alignment (or stroke-alignment :center)
             stroke-def (dm/str (fmt/format-pixels (:stroke-width stroke)) " "
                                (tr (dm/str "inspect.attributes.stroke.style." (or (d/name stroke-style) "none"))) " "
                                (tr (dm/str "inspect.attributes.stroke.alignment." (d/name stroke-alignment))))]

         [:*
          [:& color-row {:color color
                         :format @color-format
                         :copy-data (uc/color->background color)
                         :on-change-format #(reset! color-format %)}]

          [:div {:class (stl/css :stroke-row)}
           [:div {:class (stl/css :global/attr-label)}
            "Border"]
           [:div {:class (stl/css :global/attr-value)}

            [:& copy-button {:data (cssf/format-value :border (cssv/get-stroke-data stroke))}
             [:div {:class (stl/css :button-children)} stroke-def]]]]])]


      [:div.attributes-stroke-block
       (let [{:keys [stroke-style stroke-alignment]} stroke
             stroke-style (if (= stroke-style :svg) :solid stroke-style)
             stroke-alignment (or stroke-alignment :center)]
         [:div.attributes-stroke-row
          [:div.attributes-label (tr "inspect.attributes.stroke.width")]
          [:div.attributes-value (fmt/format-pixels (:stroke-width stroke))]
                            ;; Execution time translation strings:
                            ;;   inspect.attributes.stroke.style.dotted
                            ;;   inspect.attributes.stroke.style.mixed
                            ;;   inspect.attributes.stroke.style.none
                            ;;   inspect.attributes.stroke.style.solid
          [:div.attributes-value (tr (dm/str "inspect.attributes.stroke.style." (d/name stroke-style)))]
                            ;; Execution time translation strings:
                            ;;   inspect.attributes.stroke.alignment.center
                            ;;   inspect.attributes.stroke.alignment.inner
                            ;;   inspect.attributes.stroke.alignment.outer
          [:div.attributes-label (tr (dm/str "inspect.attributes.stroke.alignment." (d/name stroke-alignment)))]
          [:& copy-button {:data (cssf/format-value :border (cssv/get-stroke-data stroke))}]])
       [:& color-row {:color color
                      :format @color-format
                      :copy-data (uc/color->background color)
                      :on-change-format #(reset! color-format %)}]])))

(mf/defc stroke-panel
  [{:keys [shapes]}]
  (let [new-css-system     (mf/use-ctx ctx/new-css-system)
        shapes (->> shapes (filter has-stroke?))]
    (if new-css-system
      (when (seq shapes)
        [:div {:class (stl/css :attributes-block)}
         [:& title-bar {:collapsable? false
                        :title        (tr "inspect.attributes.stroke")
                        :class        (stl/css :title-spacing-stroke)}]

         [:div {:class (stl/css :attributes-content)}
          (for [shape shapes]
            (for [value (:strokes shape)]
              [:& stroke-block {:key (str "stroke-color-" (:id shape) value)
                                :stroke value}]))]])


      (when (seq shapes)
        [:div.attributes-block
         [:div.attributes-block-title
          [:div.attributes-block-title-text (tr "inspect.attributes.stroke")]]

         [:div.attributes-stroke-blocks
          (for [shape shapes]
            (for [value (:strokes shape)]
              [:& stroke-block {:key (str "stroke-color-" (:id shape) value)
                                :stroke value}]))]]))))

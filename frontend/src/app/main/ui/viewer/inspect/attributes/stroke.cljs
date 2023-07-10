;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.stroke
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.copy-button :refer [copy-button]]
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
   :file-id (:stroke-color-ref-file shape)})

(defn has-stroke? [shape]
  (seq (:strokes shape)))

(mf/defc stroke-block
  [{:keys [stroke]}]
  (let [color-format (mf/use-state :hex)
        color (stroke->color stroke)]
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
                    :on-change-format #(reset! color-format %)}]]))

(mf/defc stroke-panel
  [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-stroke?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (tr "inspect.attributes.stroke")]]

       [:div.attributes-stroke-blocks
        (for [shape shapes]
          (for [value (:strokes shape)]
            [:& stroke-block {:key (str "stroke-color-" (:id shape) value)
                              :stroke value}]))]])))

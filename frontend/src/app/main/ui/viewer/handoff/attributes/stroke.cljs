;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.handoff.attributes.stroke
  (:require
   [app.common.data :as d]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.viewer.handoff.attributes.common :refer [color-row]]
   [app.util.code-gen :as cg]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn shape->color [shape]
  {:color (:stroke-color shape)
   :opacity (:stroke-opacity shape)
   :gradient (:stroke-color-gradient shape)
   :id (:stroke-color-ref-id shape)
   :file-id (:stroke-color-ref-file shape)})

(defn format-stroke [shape]
  (let [width (:stroke-width shape)
        style (d/name (:stroke-style shape))
        style (if (= style "svg") "solid" style)
        color (-> shape shape->color uc/color->background)]
    (str/format "%spx %s %s" width style color)))

(defn has-stroke? [shape]
  (let [stroke-style (:stroke-style shape)]
    (or
     (and stroke-style
          (and (not= stroke-style :none)
               (not= stroke-style :svg)))
     (seq (:strokes shape)))))

(defn copy-stroke-data [shape]
  (cg/generate-css-props
   shape
   :stroke-style
   {:to-prop "border"
    :format #(format-stroke shape)}))

(defn copy-color-data [shape]
  (cg/generate-css-props
   shape
   :stroke-color
   {:to-prop "border-color"
    :format #(uc/color->background (shape->color shape))}))

(mf/defc stroke-block
  [{:keys [shape]}]
  (let [color-format (mf/use-state :hex)
        color (shape->color shape)]
    [:*
     [:& color-row {:color color
                    :format @color-format
                    :copy-data (copy-color-data shape)
                    :on-change-format #(reset! color-format %)}]

     (let [{:keys [stroke-style stroke-alignment]} shape
           stroke-style (if (= stroke-style :svg) :solid stroke-style)
           stroke-alignment (or stroke-alignment :center)]
       [:div.attributes-stroke-row
        [:div.attributes-label (tr "handoff.attributes.stroke.width")]
        [:div.attributes-value (:stroke-width shape) "px"]
        [:div.attributes-value (->> stroke-style d/name (str "handoff.attributes.stroke.style.") (tr))]
        [:div.attributes-label (->> stroke-alignment d/name (str "handoff.attributes.stroke.alignment.") (tr))]
        [:& copy-button {:data (copy-stroke-data shape)}]])]))

(mf/defc stroke-panel
  [{:keys [shapes]}]
  (let [shapes (->> shapes (filter has-stroke?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (tr "handoff.attributes.stroke")]
        (when (= (count shapes) 1)
          [:& copy-button {:data (copy-stroke-data (first shapes))}])]

       (for [shape shapes]
         (if (seq (:strokes shape))
           (for [value (:strokes shape [])]
             [:& stroke-block {:key (str "stroke-color-" (:id shape) value)
                               :shape value}])
           [:& stroke-block {:key (str "stroke-color-only" (:id shape))
                             :shape shape}]))])))

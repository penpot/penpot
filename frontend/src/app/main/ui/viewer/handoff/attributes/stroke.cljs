;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.stroke
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.i18n :refer [t]]
   [app.util.color :as uc]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.handoff.attributes.common :refer [copy-cb color-row]]))

(defn shape->color [shape]
  {:color (:stroke-color shape)
   :opacity (:stroke-opacity shape)
   :gradient (:stroke-color-gradient shape)
   :id (:stroke-ref-id shape)
   :file-id (:stroke-ref-file-id shape)})

(defn format-stroke [shape]
  (let [width (:stroke-width shape)
        style (name (:stroke-style shape))
        color (-> shape shape->color uc/color->background)]
    (str/format "%spx %s %s" width style color)))

(defn has-stroke? [shape]
  (and (:stroke-style shape)
       (not= (:stroke-style shape) :none)))

(mf/defc stroke-block
  [{:keys [shape locale]}]
  (let [color-format (mf/use-state :hex)
        color (shape->color shape)
        handle-copy-stroke (copy-cb shape
                                    :stroke-style
                                    :to-prop "border"
                                    :format #(format-stroke shape))

        handle-copy-color (copy-cb shape
                                   :stroke-color
                                   :to-prop "border-color"
                                   :format #(uc/color->background color))]

    [:*
     [:& color-row {:color color
                    :format @color-format
                    :on-change-format #(reset! color-format %)
                    :on-copy handle-copy-color}]

     [:div.attributes-stroke-row
      [:div.attributes-label (t locale "handoff.attributes.stroke.width")]
      [:div.attributes-value (:stroke-width shape) "px"]
      [:div.attributes-value (->> shape :stroke-style name (str "handoff.attributes.stroke.style.") (t locale))]
      [:div.attributes-label (->> shape :stroke-alignment name (str "handoff.attributes.stroke.alignment.") (t locale))]
      [:button.attributes-copy-button {:on-click handle-copy-stroke} i/copy]]]))

(mf/defc stroke-panel
  [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-stroke?))
        handle-copy (when (= (count shapes) 1)
                      (copy-cb (first shapes)
                               :stroke-style
                               :to-prop "border"
                               :format #(format-stroke (first shapes))))]

    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.stroke")]
        (when handle-copy
          [:button.attributes-copy-button
           {:on-click handle-copy} i/copy])]

       (for [shape shapes]
         [:& stroke-block {:key (str "stroke-color-" (:id shape))
                           :shape shape
                           :locale locale}])])))

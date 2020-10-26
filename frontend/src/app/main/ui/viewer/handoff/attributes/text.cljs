;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.text
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.data :as d]
   [app.util.i18n :refer [t]]
   [app.util.color :as uc]
   [app.util.text :as ut]
   [app.main.fonts :as fonts]
   [app.main.ui.icons :as i]
   [app.util.webapi :as wapi]
   [app.main.ui.viewer.handoff.attributes.common :refer [copy-cb color-row]]))

(defn has-text? [shape]
  (:content shape))

(def properties [:fill-color
                 :fill-color-gradient
                 :font-family
                 :font-style
                 :font-size
                 :line-height
                 :letter-spacing
                 :text-decoration
                 :text-transform])

(defn shape->color [shape]
  {:color (:fill-color shape)
   :opacity (:fill-opacity shape)
   :gradient (:fill-color-gradient shape)
   :id (:fill-ref-id shape)
   :file-id (:fill-ref-file-id shape)})

(defn format-style [color]
  {:font-family #(str "'" % "'")
   :font-style #(str "'" % "'")
   :font-size #(str % "px")
   :line-height #(str % "px")
   :letter-spacing #(str % "px")
   :text-decoration name
   :text-transform name
   :fill-color #(uc/color->background color)
   :fill-color-gradient #(uc/color->background color)})

(mf/defc typography-block [{:keys [shape locale text style full-style]}]
  (let [color-format (mf/use-state :hex)
        color (shape->color style)
        to-prop {:fill-color "color"
                 :fill-color-gradient "color"}]
    [:div.attributes-text-block
     [:div.attributes-typography-row
      [:div.typography-sample
       {:style {:font-family (:font-family full-style)
                :font-weight (:font-weight full-style)
                :font-style (:font-style full-style)}}
       (t locale "workspace.assets.typography.sample")]
      [:button.attributes-copy-button
       {:on-click (copy-cb style properties
                           :to-prop to-prop
                           :format (format-style color))}
       i/copy]]

     [:div.attributes-content-row
      [:pre.attributes-content (str/trim text)]
      [:button.attributes-copy-button
       {:on-click #(wapi/write-to-clipboard (str/trim text))}
       i/copy]]

     (when (or (:fill-color style) (:fill-color-gradient style))
       [:& color-row {:format @color-format
                      :on-change-format #(reset! color-format %)
                      :color (shape->color style)
                      :on-copy (copy-cb style
                                        [:fill-color :fill-color-gradient]
                                        :to-prop to-prop
                                        :format (format-style color))}])

     (when (:font-id style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.font-family")]
        [:div.attributes-value (-> style :font-id fonts/get-font-data :name)]
        [:button.attributes-copy-button {:on-click (copy-cb style :font-family :format identity)} i/copy]])

     (when (:font-style style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.font-style")]
        [:div.attributes-value (str (:font-style style))]
        [:button.attributes-copy-button {:on-click (copy-cb style :font-style :format identity)} i/copy]])

     (when (:font-size style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.font-size")]
        [:div.attributes-value (str (:font-size style)) "px"]
        [:button.attributes-copy-button {:on-click (copy-cb style :font-size :format #(str % "px"))} i/copy]])

     (when (:line-height style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.line-height")]
        [:div.attributes-value (str (:line-height style)) "px"]
        [:button.attributes-copy-button {:on-click (copy-cb style :line-height :format #(str % "px"))} i/copy]])

     (when (:letter-spacing style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.letter-spacing")]
        [:div.attributes-value (str (:letter-spacing style)) "px"]
        [:button.attributes-copy-button {:on-click (copy-cb style :letter-spacing :format #(str % "px"))} i/copy]])

     (when (:text-decoration style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.text-decoration")]
        [:div.attributes-value (->> style :text-decoration (str "handoff.attributes.typography.text-decoration.") (t locale))]
        [:button.attributes-copy-button {:on-click (copy-cb style :text-decoration :format name)} i/copy]])

     (when (:text-transform style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.text-transform")]
        [:div.attributes-value (->> style :text-transform (str "handoff.attributes.typography.text-transform.") (t locale))]
        [:button.attributes-copy-button {:on-click (copy-cb style :text-transform :format name)} i/copy]])]))


(mf/defc text-block [{:keys [shape locale]}]
  (let [font (ut/search-text-attrs (:content shape)
                                   (keys ut/default-text-attrs))

        style-text-blocks (->> (keys ut/default-text-attrs)
                               (ut/parse-style-text-blocks (:content shape))
                               (remove (fn [[style text]] (str/empty? (str/trim text))))
                               (mapv (fn [[style text]] (vector (merge ut/default-text-attrs style) text))))

        font (merge ut/default-text-attrs font)]
    (for [[idx [full-style text]] (map-indexed vector style-text-blocks)]
      (let [previus-style (first (nth style-text-blocks (dec idx) nil))
            style (d/remove-equal-values full-style previus-style)

            ;; If the color is set we need to add opacity otherwise the display will not work
            style (cond-> style
                    (:fill-color style)
                    (assoc :fill-opacity (:fill-opacity full-style)))]
        [:& typography-block {:shape shape
                              :locale locale
                              :full-style full-style
                              :style style
                              :text text}]))))

(mf/defc text-panel [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-text?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.typography")]]

       (for [shape shapes]
         [:& text-block {:shape shape
                         :locale locale}])])))


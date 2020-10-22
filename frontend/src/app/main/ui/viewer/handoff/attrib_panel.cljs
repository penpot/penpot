;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attrib-panel
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.config :as cfg]
   [app.util.i18n :refer [locale t]]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.text :as ut]
   [app.main.fonts :as fonts]
   [app.main.ui.components.color-bullet :refer [color-bullet color-name]]))

(mf/defc color-row [{:keys [color]}]
  (let [locale (mf/deref locale)]
    [:div.attributes-color-row
     [:& color-bullet {:color color}]

     [:*
      [:& color-name {:color color}]
      (when-not (:gradient color) [:div (str (* 100 (:opacity color)) "%")])]
     
     [:select
      [:option (t locale "handoff.attributes.color.hex")]
      [:option (t locale "handoff.attributes.color.rgba")]
      [:option (t locale "handoff.attributes.color.hsla")]]

     [:button.attributes-copy-button i/copy]]))

(mf/defc layout-panel
  [{:keys [shape locale]}]
  [:div.attributes-block
   [:div.attributes-block-title
    [:div.attributes-block-title-text (t locale "handoff.attributes.layout")]
    [:button.attributes-copy-button i/copy]]

   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.width")]
    [:div.attributes-value (mth/precision (:width shape) 2) "px"]
    [:button.attributes-copy-button i/copy]]

   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.height")]
    [:div.attributes-value (mth/precision (:height shape) 2) "px"]
    [:button.attributes-copy-button i/copy]]

   (when (not= (:x shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.left")]
      [:div.attributes-value (mth/precision (:x shape) 2) "px"]
      [:button.attributes-copy-button i/copy]])
   
   (when (not= (:y shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.top")]
      [:div.attributes-value (mth/precision (:y shape) 2) "px"]
      [:button.attributes-copy-button i/copy]])

   (when (not= (:rotation shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.rotation")]
      [:div.attributes-value (mth/precision (:rotation shape) 2) "deg"]
      [:button.attributes-copy-button i/copy]])])

(mf/defc fill-panel
  [{:keys [shape locale]}]
  (let [{:keys [fill-color fill-opacity fill-color-gradient fill-ref-id fill-ref-file-id]} shape]
    (when (or fill-color fill-color-gradient)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.fill")]
        [:button.attributes-copy-button i/copy]]

       (let [color {:color fill-color
                    :opacity fill-opacity
                    :gradient fill-color-gradient
                    :id fill-ref-id
                    :file-id fill-ref-file-id}]
         [:& color-row {:color color}])])))

(mf/defc stroke-panel
  [{:keys [shape locale]}]
  (when (and (:stroke-style shape) (not= (:stroke-style shape) :none))
    (let [{:keys [stroke-style stroke-alignment stroke-width
                  stroke-color stroke-opacity stroke-color-gradient
                  stroke-color-ref-id stroke-color-file-id]} shape
          color {:color stroke-color
                 :opacity stroke-opacity
                 :gradient stroke-color-gradient
                 :id stroke-color-ref-id
                 :file-id stroke-color-file-id}]
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.stroke")]
        [:button.attributes-copy-button i/copy]]

       [:& color-row {:color color}]

       [:div.attributes-stroke-row
        [:div.attributes-label (t locale "handoff.attributes.stroke.width")]
        [:div.attributes-value (str stroke-width) "px"]
        [:div.attributes-value (->> stroke-style name (str "handoff.attributes.stroke.style.") (t locale))]
        [:div.attributes-label (->> stroke-alignment name (str "handoff.attributes.stroke.alignment.") (t locale))]
        [:button.attributes-copy-button i/copy]]])))

(mf/defc shadow-panel [{:keys [shape locale]}]
  (when (seq (:shadow shape))
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (t locale "handoff.attributes.shadow")]
      [:button.attributes-copy-button i/copy]]

     (for [shadow (:shadow shape)]
       (do
         (prn "???" (:spread shadow))
         [:*
          [:div.attributes-shadow-row
           [:div.attributes-label (->> shadow :style name (str "handoff.attributes.shadow.style.") (t locale))]
           [:div.attributes-shadow
            [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.offset-x")]
            [:div.attributes-value (str (:offset-x shadow))]]

           [:div.attributes-shadow
            [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.offset-y")]
            [:div.attributes-value (str (:offset-y shadow))]]

           [:div.attributes-shadow
            [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.blur")]
            [:div.attributes-value (str (:blur shadow))]]

           [:div.attributes-shadow
            [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.spread")]
            [:div.attributes-value (str (:spread shadow))]]

           [:button.attributes-copy-button i/copy]]
          [:& color-row {:color (:color shadow)}]]))]))

(mf/defc blur-panel [{:keys [shape locale]}]
  (when (:blur shape)
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (t locale "handoff.attributes.blur")]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.blur.value")]
      [:div.attributes-value (-> shape :blur :value) "px"]
      [:button.attributes-copy-button i/copy]]]))

(mf/defc image-panel [{:keys [shape locale]}]
  [:div.attributes-block
   [:div.attributes-image-row
    [:div.attributes-image
     [:img {:src (cfg/resolve-media-path (-> shape :metadata :path))}]]]
   [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.image.width")]
      [:div.attributes-value (-> shape :metadata :width) "px"]]
   [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.image.height")]
      [:div.attributes-value (-> shape :metadata :height) "px"]]
   (let [filename (last (str/split (-> shape :metadata :path) "/"))]
     [:a.download-button {:target "_blank"
                          :download filename
                          :href (cfg/resolve-media-path (-> shape :metadata :path))}
      (t locale "handoff.attributes.image.download")])])

(mf/defc typography-panel [{:keys [shape locale]}]
  (let [font (ut/search-text-attrs (:content shape)
                                   (keys ut/default-text-attrs))
        font (merge ut/default-text-attrs font)]
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (t locale "handoff.attributes.typography")]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.font-family")]
      [:div.attributes-value (-> font :font-id fonts/get-font-data :name)]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.font-style")]
      [:div.attributes-value (str (:font-style font))]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.font-size")]
      [:div.attributes-value (str (:font-size font)) "px"]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.line-height")]
      [:div.attributes-value (str (:line-height font)) "px"]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.letter-spacing")]
      [:div.attributes-value (str (:letter-spacing font)) "px"]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.text-decoration")]
      [:div.attributes-value (->> font :text-decoration (str "handoff.attributes.typography.text-decoration.") (t locale))]
      [:button.attributes-copy-button i/copy]]

     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.typography.text-transform")]
      [:div.attributes-value (->> font :text-transform (str "handoff.attributes.typography.text-transform.") (t locale))]
      [:button.attributes-copy-button i/copy]]]))

(mf/defc content-panel [{:keys [shape locale]}]
  [:div.attributes-block
   [:div.attributes-block-title
    [:div.attributes-block-title-text (t locale "handoff.attributes.content")]
    [:button.attributes-copy-button i/copy]]

   [:div.attributes-content-row
    [:pre.attributes-content (ut/content->text (:content shape))]
    [:button.attributes-copy-button i/copy]]])

(mf/defc attrib-panel [{:keys [shape frame options]}]
  (let [locale (mf/deref locale)]
    [:div.element-options
     (for [option options]
       [:>
        (case option
          :layout     layout-panel
          :fill       fill-panel
          :stroke     stroke-panel
          :shadow     shadow-panel
          :blur       blur-panel
          :image      image-panel
          :typography typography-panel
          :content    content-panel
          )
        {:shape (gsh/translate-to-frame shape frame)
         :frame frame
         :locale locale}])]))

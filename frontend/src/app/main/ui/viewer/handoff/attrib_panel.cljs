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
   [app.util.data :as d]
   [app.util.dom :as dom]
   [app.util.i18n :refer [locale t]]
   [app.util.color :as uc]
   [app.util.text :as ut]
   [app.common.math :as mth]
   [app.common.geom.shapes :as gsh]
   [app.main.fonts :as fonts]
   [app.main.ui.icons :as i]
   [app.util.webapi :as wapi]
   [app.main.ui.components.color-bullet :refer [color-bullet color-name]]))

(defn copy-cb [values properties & {:keys [to-prop format] :or {to-prop {}}}]
  (fn [event]
    (let [
          ;; We allow the :format and :to-prop to be a map for different properties
          ;; or just a value for a single property. This code transform a single
          ;; property to a uniform one
          properties (if-not (coll? properties) [properties] properties)

          format (if (not (map? format))
                   (into {} (map #(vector % format) properties))
                   format)

          to-prop (if (not (map? to-prop))
                    (into {} (map #(vector % to-prop) properties))
                    to-prop)

          default-format (fn [value] (str (mth/precision value 2) "px"))
          format-property (fn [prop]
                            (let [css-prop (or (prop to-prop) (name prop))]
                              (str/fmt "  %s: %s;" css-prop ((or (prop format) default-format) (prop values) values))))

          text-props (->> properties
                          (remove #(let [value (get values %)]
                                     (or (nil? value) (= value 0))))
                          (map format-property)
                          (str/join "\n"))

          result (str/fmt "{\n%s\n}" text-props)]

      (wapi/write-to-clipboard result))))


(mf/defc color-row [{:keys [color format on-copy on-change-format]}]
  (let [locale (mf/deref locale)]
    [:div.attributes-color-row
     [:& color-bullet {:color color}]

     (if (:gradient color)
       [:& color-name {:color color}]
       (case format
         :rgba (let [[r g b a] (->> (uc/hex->rgba (:color color) (:opacity color)) (map #(mth/precision % 2)))]
                 [:div (str/fmt "%s, %s, %s, %s" r g b a)])
         :hsla (let [[h s l a] (->> (uc/hex->hsla (:color color) (:opacity color)) (map #(mth/precision % 2)))]
                 [:div (str/fmt "%s, %s, %s, %s" h s l a)])
         [:*
          [:& color-name {:color color}]
          (when-not (:gradient color) [:div (str (* 100 (:opacity color)) "%")])]))

     (when-not (and on-change-format (:gradient color))
       [:select {:on-change #(-> (dom/get-target-val %) keyword on-change-format)}
        [:option {:value "hex"}
         (t locale "handoff.attributes.color.hex")]

        [:option {:value "rgba"}
         (t locale "handoff.attributes.color.rgba")]

        [:option {:value "hsla"}
         (t locale "handoff.attributes.color.hsla")]])

     (when on-copy
       [:button.attributes-copy-button {:on-click on-copy} i/copy])]))

(mf/defc layout-panel
  [{:keys [shape locale]}]
  [:div.attributes-block
   [:div.attributes-block-title
    [:div.attributes-block-title-text (t locale "handoff.attributes.layout")]
    [:button.attributes-copy-button
     {:on-click (copy-cb shape
                         [:width :height :x :y :rotation]
                         :to-prop {:x "left" :y "top" :rotation "transform"}
                         :format {:rotation #(str/fmt "rotate(%sdeg)" %)})}
     i/copy]]

   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.width")]
    [:div.attributes-value (mth/precision (:width shape) 2) "px"]
    [:button.attributes-copy-button
     {:on-click (copy-cb shape :width)}
     i/copy]]

   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.height")]
    [:div.attributes-value (mth/precision (:height shape) 2) "px"]
    [:button.attributes-copy-button
     {:on-click (copy-cb shape :height)}
     i/copy]]

   (when (not= (:x shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.left")]
      [:div.attributes-value (mth/precision (:x shape) 2) "px"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape :x :to-prop "left")}
       i/copy]])
   
   (when (not= (:y shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.top")]
      [:div.attributes-value (mth/precision (:y shape) 2) "px"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape :y :to-prop "top")}
       i/copy]])

   (when (not= (:rotation shape 0) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.rotation")]
      [:div.attributes-value (mth/precision (:rotation shape) 2) "deg"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape
                           :rotation
                           :to-prop "transform"
                           :format #(str/fmt "rotate(%sdeg)" %))}
       i/copy]])])

(mf/defc fill-panel
  [{:keys [shape locale]}]
  (let [color-format (mf/use-state :hex)
        color {:color (:fill-color shape)
               :opacity (:fill-opacity shape)
               :gradient (:fill-color-gradient shape)
               :id (:fill-ref-id shape)
               :file-id (:fill-ref-file-id shape)}

        handle-copy (copy-cb shape
                             [:fill-color :fill-color-gradient]
                             :to-prop "background"
                             :format #(uc/color->background color))]

    (when (or (:color color) (:gradient color))
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.fill")]
        [:button.attributes-copy-button
         {:on-click handle-copy}
         i/copy]]

       [:& color-row {:color color
                      :format @color-format
                      :on-change-format #(reset! color-format %)
                      :on-copy handle-copy}]])))

(mf/defc stroke-panel
  [{:keys [shape locale]}]
  (let [color-format (mf/use-state :hex)
        color {:color (:stroke-color shape)
               :opacity (:stroke-opacity shape)
               :gradient (:stroke-color-gradient shape)
               :id (:stroke-color-ref-id shape)
               :file-id (:stroke-color-file-id shape)}

        handle-copy-stroke (copy-cb shape
                                    :stroke-style
                                    :to-prop "border"
                                    :format #(let [width (:stroke-width %2)
                                                   style (name (:stroke-style %2))
                                                   color (uc/color->background color)]
                                               (str/format "%spx %s %s" width style color)))]

    (when (and (:stroke-style shape) (not= (:stroke-style shape) :none))
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.stroke")]
        [:button.attributes-copy-button
         {:on-click handle-copy-stroke} i/copy]]

       [:& color-row {:color color
                      :format @color-format
                      :on-change-format #(reset! color-format %)
                      :on-copy (copy-cb shape
                                        :stroke-color
                                        :to-prop "border-color"
                                        :format #(uc/color->background color))}]

       [:div.attributes-stroke-row
        [:div.attributes-label (t locale "handoff.attributes.stroke.width")]
        [:div.attributes-value (:stroke-width shape) "px"]
        [:div.attributes-value (->> shape :stroke-style name (str "handoff.attributes.stroke.style.") (t locale))]
        [:div.attributes-label (->> shape :stroke-alignment name (str "handoff.attributes.stroke.alignment.") (t locale))]
        [:button.attributes-copy-button
         {:on-click handle-copy-stroke} i/copy]]])))

(defn shadow->css [shadow]
  (let [{:keys [style offset-x offset-y blur spread]} shadow
        css-color (uc/color->background (:color shadow))]
    (str
     (if (= style :inner-shadow) "inset " "")
     (str/fmt "%spx %spx %spx %spx %s" offset-x offset-y blur spread css-color))))

(mf/defc shadow-block [{:keys [shape locale shadow]}]
  (let [color-format (mf/use-state :hex)]
    [:div.attributes-shadow-block
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

      [:button.attributes-copy-button
       {:on-click (copy-cb shadow
                           :style
                           :to-prop "box-shadow"
                           :format #(shadow->css shadow))}
       i/copy]]
     [:& color-row {:color (:color shadow)
                    :format @color-format
                    :on-change-format #(reset! color-format %)}]]))

(mf/defc shadow-panel [{:keys [shape locale]}]
  (when (seq (:shadow shape))
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (t locale "handoff.attributes.shadow")]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape
                           :shadow
                           :to-prop "box-shadow"
                           :format #(str/join ", " (map shadow->css (:shadow shape))))}
       i/copy]]

     (for [shadow (:shadow shape)]
       [:& shadow-block {:shape shape
                         :locale locale
                         :shadow shadow}])]))

(mf/defc blur-panel [{:keys [shape locale]}]
  (let [handle-copy
        (copy-cb shape
                 :blur
                 :to-prop "filter"
                 :format #(str/fmt "blur(%spx)" %))]
    (when (:blur shape)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.blur")]
        [:button.attributes-copy-button {:on-click handle-copy} i/copy]]

       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.blur.value")]
        [:div.attributes-value (-> shape :blur :value) "px"]
        [:button.attributes-copy-button {:on-click handle-copy} i/copy]]])))

(mf/defc image-panel [{:keys [shape locale]}]
  [:div.attributes-block
   [:div.attributes-image-row
    [:div.attributes-image
     [:img {:src (cfg/resolve-media-path (-> shape :metadata :path))}]]]
   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.image.width")]
    [:div.attributes-value (-> shape :metadata :width) "px"]
    [:button.attributes-copy-button {:on-click (copy-cb shape :width)} i/copy]]
   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.image.height")]
    [:div.attributes-value (-> shape :metadata :height) "px"]
    [:button.attributes-copy-button {:on-click (copy-cb shape :height)} i/copy]]
   (let [filename (last (str/split (-> shape :metadata :path) "/"))]
     [:a.download-button {:target "_blank"
                          :download filename
                          :href (cfg/resolve-media-path (-> shape :metadata :path))}
      (t locale "handoff.attributes.image.download")])])


(mf/defc text-block [{:keys [shape locale text style full-style]}]
  (let [color-format (mf/use-state :hex)
        color {:color (:fill-color style)
               :opacity (:fill-opacity style)
               :gradient (:fill-color-gradient style)
               :id (:fill-color-ref-id style)
               :file-id (:fill-color-ref-file-id style)}
        properties [:fill-color
                    :fill-color-gradient
                    :font-family
                    :font-style
                    :font-size
                    :line-height
                    :letter-spacing
                    :text-decoration
                    :text-transform]
        format {:font-family identity
                :font-style identity
                :font-size #(str % "px")
                :line-height #(str % "px")
                :letter-spacing #(str % "px")
                :text-decoration name
                :text-transform name
                :fill-color #(uc/color->background color)
                :fill-color-gradient #(uc/color->background color)}
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
       {:on-click (copy-cb style properties :to-prop to-prop :format format)} i/copy]]

     [:div.attributes-content-row
      [:pre.attributes-content (str/trim text)]
      [:button.attributes-copy-button
       {:on-click #(wapi/write-to-clipboard (str/trim text))}
       i/copy]]

     (when (or (:fill-color style) (:fill-color-gradient style))
       (let [color {:color (:fill-color style)
                    :opacity (:fill-opacity style)
                    :gradient (:fill-color-gradient style)
                    :id (:fill-ref-id style)
                    :file-id (:fill-ref-file-id style)}]
         [:& color-row {:format @color-format
                        :on-change-format #(reset! color-format %)
                        :color color
                        :on-copy (copy-cb style [:fill-color :fill-color-gradient] :to-prop to-prop :format format)}]))

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

(mf/defc typography-panel [{:keys [shape locale]}]
  (let [font (ut/search-text-attrs (:content shape)
                                   (keys ut/default-text-attrs))

        style-text-blocks (->> (keys ut/default-text-attrs)
                               (ut/parse-style-text-blocks (:content shape))
                               (remove (fn [[style text]] (str/empty? (str/trim text))))
                               (mapv (fn [[style text]] (vector (merge ut/default-text-attrs style) text))))

        font (merge ut/default-text-attrs font)]
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (t locale "handoff.attributes.typography")]]

     (for [[idx [full-style text]] (map-indexed vector style-text-blocks)]
       (let [previus-style (first (nth style-text-blocks (dec idx) nil))
             style (d/remove-equal-values full-style previus-style)

             ;; If the color is set we need to add opacity otherwise the display will not work
             style (cond-> style
                     (:fill-color style)
                     (assoc :fill-opacity (:fill-opacity full-style)))]
         [:& text-block {:shape shape
                         :locale locale
                         :full-style full-style
                         :style style
                         :text text}]))]))

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
          :typography typography-panel)
        {:shape (gsh/translate-to-frame shape frame)
         :frame frame
         :locale locale}])]))

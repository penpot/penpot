;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.fills :as types.fills]
   [app.common.types.text :as txt]
   [app.main.fonts :as fonts]
   [app.main.ui.formats :as fmt]
   [app.main.ui.inspect.common.typography :as ict]
   [app.main.ui.inspect.styles.property-detail-copiable :refer [property-detail-copiable*]]
   [app.main.ui.inspect.styles.rows.color-properties-row :refer [color-properties-row*]]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [app.util.clipboard :as clipboard]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- get-applied-tokens-in-shape
  [shape-tokens property]
  (get shape-tokens property))

(defn- get-resolved-token
  [property shape resolved-tokens]
  (let [shape-tokens (:applied-tokens shape)
        applied-tokens-in-shape (get-applied-tokens-in-shape shape-tokens property)
        token (get resolved-tokens applied-tokens-in-shape)]
    token))

(defn- get-style-text
  [shape]
  (->> (:content shape)
       (txt/content->text+styles)
       (remove (fn [[_ text]] (str/empty? (str/trim text))))
       (mapv (fn [[style text]] (vector (merge (txt/get-default-text-attrs) style) text)))))

(defn- generate-typography-shorthand
  [shapes]
  (let [shape (first shapes)
        style-text-blocks (get-style-text shape)]
    (reduce
     (fn [acc [style _]]
       (let [font-style (:font-style style)
             font-family (dm/str (:font-family style))
             font-size (:font-size style)
             font-weight (:font-weight style)
             line-height (:line-height style)
             text-transform (:text-transform style)]
         (dm/str acc "font:" font-style " " text-transform " " font-weight " " font-size "/" line-height " "  \"  font-family  \" ";")))
     ""
     style-text-blocks)))

(mf/defc typography-name-block*
  [{:keys [style]}]
  (let [typography (ict/get-typography style)
        property-value (:name typography)]
    (when typography
      [:> properties-row* {:term "Typography"
                           :detail property-value
                           :property property-value
                           :copiable true}])))

(mf/defc typography-color-row*
  [{:keys [fill shape resolved-tokens color-space]}]
  (let [color (types.fills/fill->color fill)
        resolved-token (get-resolved-token :fill shape resolved-tokens)]
    [:> color-properties-row* {:term "Font Color"
                               :color color
                               :token resolved-token
                               :format color-space
                               :copiable true}]))

(mf/defc style-text-block*
  [{:keys [shape style text resolved-tokens color-space]}]
  (let [copied* (mf/use-state false)
        copied (deref copied*)
        text (str/trim text)
        copy-text
        (mf/use-fn
         (mf/deps copied)
         (fn []
           (let [formatted-text (if (= (:text-transform style) "uppercase")
                                  (.toUpperCase text)
                                  text)]
             (reset! copied* true)
             (clipboard/to-clipboard formatted-text)
             (tm/schedule 1000 #(reset! copied* false)))))
        composite-typography-token (get-resolved-token :typography shape resolved-tokens)]
    [:div {:class (stl/css :text-properties)}

     (when (:fills style)
       (for [[idx fill] (map-indexed vector (:fills style))]
         [:> typography-color-row* {:key idx
                                    :fill fill
                                    :shape shape
                                    :resolved-tokens resolved-tokens
                                    :color-space color-space}]))

      ;; Typography style
     (when (and (not composite-typography-token)
                (:typography-ref-id style))
       [:> typography-name-block* {:style style}])

     ;; Composite Typography token
     (when (and (not (:typography-ref-id style))
                composite-typography-token)
       [:> properties-row* {:term "Typography"
                            :detail (:name composite-typography-token)
                            :token composite-typography-token
                            :property (:name composite-typography-token)
                            :copiable true}])

     (when (:font-id style)
       (let [name (get (fonts/get-font-data (:font-id style)) :name)
             resolved-token (get-resolved-token :font-family shape resolved-tokens)]
         [:> properties-row* {:term "Font Family"
                              :detail name
                              :token resolved-token
                              :property (str "font-family: \"" name "\";")
                              :copiable true}]))

     (when (:font-style style)
       [:> properties-row* {:term "Font Style"
                            :detail (:font-style style)
                            :property (str "font-style: " (:font-style style) ";")
                            :copiable true}])

     (when (:font-size style)
       (let [font-size (fmt/format-pixels (:font-size style))
             resolved-token (get-resolved-token :font-size shape resolved-tokens)]
         [:> properties-row* {:term "Font Size"
                              :detail font-size
                              :token resolved-token
                              :property (str "font-size: " font-size ";")
                              :copiable true}]))
     (when (:font-weight style)
       (let [resolved-token (get-resolved-token :font-weight shape resolved-tokens)]
         [:> properties-row* {:term "Font Weight"
                              :detail (:font-weight style)
                              :token resolved-token
                              :property (str "font-weight: " (:font-weight style) ";")
                              :copiable true}]))

     (when (:line-height style)
       (let [line-height (:line-height style)
             resolved-token (get-resolved-token :line-height shape resolved-tokens)]
         [:> properties-row* {:term "Line Height"
                              :detail (str line-height)
                              :token resolved-token
                              :property (str "line-height: " line-height ";")
                              :copiable true}]))

     (when (:letter-spacing style)
       (let [letter-spacing (fmt/format-pixels (:letter-spacing style))
             resolved-token (get-resolved-token :letter-spacing shape resolved-tokens)]
         [:> properties-row* {:term "Letter Spacing"
                              :detail letter-spacing
                              :token resolved-token
                              :property (str "letter-spacing: " letter-spacing ";")
                              :copiable true}]))

     (when (:text-decoration style)
       (let [resolved-token (get-resolved-token :text-decoration shape resolved-tokens)]
         [:> properties-row* {:term "Text Decoration"
                              :detail (:text-decoration style)
                              :token resolved-token
                              :property (str "text-decoration: " (:text-decoration style) ";")
                              :copiable true}]))

     (when (:text-transform style)
       (let [resolved-token (get-resolved-token :text-case shape resolved-tokens)]
         [:> properties-row* {:term "Text Transform"
                              :detail (:text-transform style)
                              :token resolved-token
                              :property (str "text-transform: " (:text-transform style) ";")
                              :copiable true}]))

     [:pre {:class (stl/css :text-content-wrapper)
            :role "presentation"}
      [:> property-detail-copiable* {:copied copied
                                     :on-click copy-text}
       [:span {:class (stl/css :text-content)
               :style {:font-family (:font-family style)
                       :font-weight (:font-weight style)
                       :text-transform (:text-transform style)
                       :letter-spacing (fmt/format-pixels (:letter-spacing style))
                       :font-style (:font-style style)}}
        text]]]]))

(mf/defc text-panel*
  [{:keys [shapes resolved-tokens color-space on-font-shorthand]}]
  (let [shorthand* (mf/use-state #(generate-typography-shorthand shapes))
        shorthand (deref shorthand*)]
    (mf/use-effect
     (mf/deps shorthand on-font-shorthand shapes)
     (fn []
       (reset! shorthand* (generate-typography-shorthand shapes))
       (on-font-shorthand {:panel :text
                           :property shorthand})))
    [:div {:class (stl/css :text-panel)}
     (for [shape shapes]
       (let [style-text-blocks (get-style-text shape)]
         [:div {:key (:id shape) :class (stl/css :text-shape)}
          (for [[style text] style-text-blocks]
            [:> style-text-block* {:key (:id shape)
                                   :shape shape
                                   :style style
                                   :text text
                                   :resolved-tokens resolved-tokens
                                   :color-space color-space}])]))]))


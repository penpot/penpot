;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.text
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.workspace.sidebar.options.measures :refer [measures-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

(declare +fonts+)

(mf/defc fonts-menu
  [{:keys [shape] :as props}]
  (let [id (:id shape)
        font-family (:font-family shape "sourcesanspro")
        font-style (:font-style shape "normal")
        font-weight (:font-weight shape "normal")
        font-size (:font-size shape 16)
        text-align (:text-align shape "left")
        line-height (:line-height shape 1.4)
        letter-spacing (:letter-spacing shape 1)

        styles (:styles (d/seek #(= (:id %) font-family) +fonts+))

        on-font-family-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value))
                attrs {:font-family value
                       :font-weight "normal"
                       :font-style "normal"}]
            (st/emit! (dw/update-shape id attrs))))

        on-font-size-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))
                attrs {:font-size value}]
            (st/emit! (dw/update-shape id attrs))))

        on-font-letter-spacing-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-double 0))
                attrs {:letter-spacing value}]
            (st/emit! (dw/update-shape id attrs))))

        on-font-line-height-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-double 0))
                attrs {:line-height value}]
            (st/emit! (dw/update-shape id attrs))))

        on-font-align-change
        (fn [event value]
          (let [attrs {:text-align value}]
            (st/emit! (dw/update-shape id attrs))))

        on-font-style-change
        (fn [event]
          (let [[weight style] (-> (dom/get-target event)
                                   (dom/get-value)
                                   (d/read-string))
                attrs {:font-style style
                       :font-weight weight}]
            (st/emit! (dw/update-shape id attrs))))
        ]
    [:div.element-set
     [:div.element-set-title (tr "workspace.options.font-options")]
     [:div.element-set-content
      [:div.row-flex
       [:select.input-select {:value font-family
                              :on-change on-font-family-change}
        (for [font +fonts+]
          [:option {:value (:id font)
                    :key (:id font)}
           (:name font)])]]

      [:div.row-flex
       [:div.editable-select
        [:select.input-select {:value font-size
                               :on-change on-font-size-change}
         [:option {:value "8"} "8"]
         [:option {:value "9"} "9"]
         [:option {:value "10"} "10"]
         [:option {:value "11"} "11"]
         [:option {:value "12"} "12"]
         [:option {:value "14"} "14"]
         [:option {:value "18"} "18"]
         [:option {:value "24"} "24"]
         [:option {:value "36"} "36"]
         [:option {:value "48"} "48"]
         [:option {:value "72"} "72"]]
        [:input.input-text {:type "number"
                            :min "0"
                            :max "200"
                            :value (-> font-size
                                       (math/precision 2)
                                       (d/coalesce-str "0"))
                            :on-change on-font-size-change}]]

       [:select.input-select {:value (pr-str [font-weight font-style])
                              :on-change on-font-style-change}
        (for [style styles
              :let [data (mapv #(get style %) [:weight :style])]]
          [:option {:value (pr-str data)
                    :key (:name style)}
           (:name style)])]]

      [:div.row-flex.align-icons
       [:span.tooltip.tooltip-bottom
               {:alt "Align left"
               :class (when (= text-align "left") "current")
               :on-click #(on-font-align-change % "left")}
        i/text-align-left]
       [:span.tooltip.tooltip-bottom
               {:alt "Align center"
               :class (when (= text-align "center") "current")
               :on-click #(on-font-align-change % "center")}
        i/text-align-center]
       [:span.tooltip.tooltip-bottom
               {:alt "Align right"
               :class (when (= text-align "right") "current")
               :on-click #(on-font-align-change % "right")}
        i/text-align-right]
       [:span.tooltip.tooltip-bottom
               {:alt "Justify"
               :class (when (= text-align "justify") "current")
               :on-click #(on-font-align-change % "justify")}
        i/text-align-justify]]

      [:div.row-flex
       [:div.input-icon
        [:span.icon-before.tooltip.tooltip-bottom
                           {:alt "Line height"}
                           i/line-height]
        [:input.input-text {:type "number"
                            :step "0.1"
                            :min "0"
                            :max "200"
                            :value (-> line-height
                                       (math/precision 2)
                                       (d/coalesce-str "0"))
                            :on-change on-font-line-height-change}]]
       [:div.input-icon
        [:span.icon-before.tooltip.tooltip-bottom
                           {:alt "Letter spacing"}
                           i/letter-spacing]
        [:input.input-text {:type "number"
                            :step "0.1"
                            :min "0"
                            :max "200"
                            :value (-> letter-spacing
                                       (math/precision 2)
                                       (d/coalesce-str "0"))
                            :on-change on-font-letter-spacing-change}]]]

      [:div.row-flex
       [:div.align-icons
        [:span.tooltip.tooltip-bottom
               {:alt "Align top"}
         i/align-top]
        [:span.tooltip.tooltip-bottom
               {:alt "Align middle"}
         i/align-middle]
        [:span.tooltip.tooltip-bottom
               {:alt "Align bottom"}
         i/align-bottom]]

       [:div.align-icons
        [:span.tooltip.tooltip-bottom
               {:alt "Auto height"}
         i/auto-height]
        [:span.tooltip.tooltip-bottom
               {:alt "Auto width"}
         i/auto-width]
        [:span.tooltip.tooltip-bottom
               {:alt "Fixed size"}
         i/auto-fix]]]

      [:div.row-flex
       [:span.element-set-subtitle "Decoration"]
       [:div.align-icons
        [:span.tooltip.tooltip-bottom
               {:alt "None"}
         i/minus]
        [:span.tooltip.tooltip-bottom
               {:alt "Underline"}
         i/underline]
        [:span.tooltip.tooltip-bottom
               {:alt "Strikethrough"}
         i/strikethrough]]]

      [:div.row-flex
       [:span.element-set-subtitle "Case"]
       [:div.align-icons
        [:span.tooltip.tooltip-bottom
               {:alt "None"}
         i/minus]
        [:span.tooltip.tooltip-bottom
               {:alt "Uppercase"}
         i/uppercase]
        [:span.tooltip.tooltip-bottom
               {:alt "Lowercase"}
         i/lowercase]
        [:span.tooltip.tooltip-bottom
               {:alt "Titlecase"}
         i/titlecase]]]
     ]]))

(def +fonts+
  [{:id "sourcesanspro"
    :name "Source Sans Pro"
    :styles [{:name "Extra-Light"
              :weight "100"
              :style "normal"}
             {:name "Extra-Light Italic"
              :weight "100"
              :style "italic"}
             {:name "Light"
              :weight "200"
              :style "normal"}
             {:name "Light Italic"
              :weight "200"
              :style "italic"}
             {:name "Regular"
              :weight "normal"
              :style "normal"}
             {:name "Italic"
              :weight "normal"
              :style "italic"}
             {:name "Semi-Bold"
              :weight "500"
              :style "normal"}
             {:name "Semi-Bold Italic"
              :weight "500"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}
             {:name "Black"
              :weight "900"
              :style "normal"}
             {:name "Black Italic"
              :weight "900"
              :style "italic"}]}
   {:id "opensans"
    :name "Open Sans"
    :styles [{:name "Extra-Light"
              :weight "100"
              :style "normal"}
             {:name "Extra-Light Italic"
              :weight "100"
              :style "italic"}
             {:name "Light"
              :weight "200"
              :style "normal"}
             {:name "Light Italic"
              :weight "200"
              :style "italic"}
             {:name "Regular"
              :weight "normal"
              :style "normal"}
             {:name "Italic"
              :weight "normal"
              :style "italic"}
             {:name "Semi-Bold"
              :weight "500"
              :style "normal"}
             {:name "Semi-Bold Italic"
              :weight "500"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}
             {:name "Black"
              :weight "900"
              :style "normal"}
             {:name "Black Italic"
              :weight "900"
              :style "italic"}]}
   {:id "bebas"
    :name "Bebas"
    :styles [{:name "Normal"
              :weight "normal"
              :style "normal"}]}
   {:id "gooddog"
    :name "Good Dog"
    :styles [{:name "Normal"
              :weight "normal"
              :style "normal"}]}
   {:id "caviardreams"
    :name "Caviar Dreams"
    :styles [{:name "Normal"
              :weight "normal"
              :style "normal"}
             {:name "Normal Italic"
              :weight "normal"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}]}
   {:id "ptsans"
    :name "PT Sans"
    :styles [{:name "Normal"
              :weight "normal"
              :style "normal"}
             {:name "Normal Italic"
              :weight "normal"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}]}
   {:id "roboto"
    :name "Roboto"
    :styles [{:name "Extra-Light"
              :weight "100"
              :style "normal"}
             {:name "Extra-Light Italic"
              :weight "100"
              :style "italic"}
             {:name "Light"
              :weight "200"
              :style "normal"}
             {:name "Light Italic"
              :weight "200"
              :style "italic"}
             {:name "Regular"
              :weight "normal"
              :style "normal"}
             {:name "Italic"
              :weight "normal"
              :style "italic"}
             {:name "Semi-Bold"
              :weight "500"
              :style "normal"}
             {:name "Semi-Bold Italic"
              :weight "500"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}
             {:name "Black"
              :weight "900"
              :style "normal"}
             {:name "Black Italic"
              :weight "900"
              :style "italic"}]}
   {:id "robotocondensed"
    :name "Roboto Condensed"
    :styles [{:name "Extra-Light"
              :weight "100"
              :style "normal"}
             {:name "Extra-Light Italic"
              :weight "100"
              :style "italic"}
             {:name "Light"
              :weight "200"
              :style "normal"}
             {:name "Light Italic"
              :weight "200"
              :style "italic"}
             {:name "Regular"
              :weight "normal"
              :style "normal"}
             {:name "Italic"
              :weight "normal"
              :style "italic"}
             {:name "Semi-Bold"
              :weight "500"
              :style "normal"}
             {:name "Semi-Bold Italic"
              :weight "500"
              :style "italic"}
             {:name "Bold"
              :weight "bold"
              :style "normal"}
             {:name "Bold Italic"
              :weight "bold"
              :style "italic"}
             {:name "Black"
              :weight "900"
              :style "normal"}
             {:name "Black Italic"
              :weight "900"
              :style "italic"}]}
   ])

(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures-menu {:shape shape}]
   [:& fonts-menu {:shape shape}]])

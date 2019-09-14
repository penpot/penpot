;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.text
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.geom :as geom]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.builtins.icons :as i]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.math :refer (precision-or-0)]
            [uxbox.util.data :refer (parse-int
                                     parse-float
                                     read-string
                                     index-by-id)]))

(declare +fonts+)
(declare +fonts-by-id+)

(mx/defc text-menu
  {:mixins [mx/static]}
  [menu {:keys [id] :as shape}]
  (letfn [(update-attrs [attrs]
            (st/emit! (uds/update-attrs id attrs)))
          (on-font-family-change [event]
            (let [value (dom/event->value event)
                  attrs {:font-family (read-string value)
                         :font-weight "normal"
                         :font-style "normal"}]
              (update-attrs attrs)))
          (on-font-size-change [event]
            (let [value (-> (dom/event->value event)
                            (parse-int 0))]
              (update-attrs {:font-size value})))
          (on-font-letter-spacing-change [event]
            (let [value (-> (dom/event->value event)
                            (parse-float))]
              (update-attrs {:letter-spacing value})))
          (on-font-line-height-change [event]
            (let [value (-> (dom/event->value event)
                            (parse-float))]
              (update-attrs {:line-height value})))
          (on-font-align-change [event value]
            (update-attrs {:text-align value}))
          (on-font-style-change [event]
            (let [[weight style] (-> (dom/event->value event)
                                     (read-string))]
              (update-attrs {:font-style style
                             :font-weight weight})))]
    (let [{:keys [font-family
                  font-style
                  font-weight
                  font-size
                  text-align
                  line-height
                  letter-spacing]
           :or {font-family "sourcesanspro"
                font-style "normal"
                font-weight "normal"
                font-size 16
                text-align "left"
                letter-spacing 1
                line-height 1.4}} shape
          styles (:styles (first (filter #(= (:id %) font-family) +fonts+)))]
      [:div.element-set {:key (str (:id menu))}
       [:div.element-set-title (:name menu)]
       [:div.element-set-content

        [:span (tr "ds.font-family")]
        [:div.row-flex
         [:select.input-select {:value (pr-str font-family)
                                :on-change on-font-family-change}
          (for [font +fonts+]
            [:option {:value (pr-str (:id font))
                      :key (:id font)} (:name font)])]]

        [:span (tr "ds.size-weight")]
        [:div.row-flex
         [:div.editable-select
          [:select.input-select
           {:id "common-font-sizes"
            :value font-size
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
          [:input.input-text
           {:placeholder (tr "ds.font-size")
            :type "number"
            :min "0"
            :max "200"
            :value (precision-or-0 font-size 2)
            :on-change on-font-size-change}]]
         [:select.input-select {:value (pr-str [font-weight font-style])
                                :on-change on-font-style-change}
          (for [style styles
                :let [data (mapv #(get style %) [:weight :style])]]
            [:option {:value (pr-str data)
                      :key (:name style)} (:name style)])]]

        [:span (tr "ds.line-height-letter-spacing")]
        [:div.row-flex
         [:input.input-text
          {:placeholder (tr "ds.line-height")
           :type "number"
           :step "0.1"
           :min "0"
           :max "200"
           :value (precision-or-0 line-height 2)
           :on-change on-font-line-height-change}]
         [:input.input-text
          {:placeholder (tr "ds.letter-spacing")
           :type "number"
           :step "0.1"
           :min "0"
           :max "200"
           :value (precision-or-0 letter-spacing 2)
           :on-change on-font-letter-spacing-change}]]

        [:span (tr "ds.text-align")]
        [:div.row-flex.align-icons
         [:span {:class (when (= text-align "left") "current")
                 :on-click #(on-font-align-change % "left")}
          i/align-left]
         [:span {:class (when (= text-align "center") "current")
                 :on-click #(on-font-align-change % "center")}
          i/align-center]
         [:span {:class (when (= text-align "right") "current")
                 :on-click #(on-font-align-change % "right")}
          i/align-right]
         [:span {:class (when (= text-align "justify") "current")
                 :on-click #(on-font-align-change % "justify")}
          i/align-justify]]]])))

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

(def +fonts-by-id+
  (index-by-id +fonts+))

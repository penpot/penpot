;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.text
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.workspace.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int
                                     parse-float
                                     read-string
                                     index-by-id)]))

(declare +fonts+)
(declare +fonts-by-id+)

(defn- text-menu-render
  [own menu {:keys [font] :as shape}]
  (letfn [(on-font-family-change [event]
            (let [value (dom/event->value event)
                  sid (:id shape)
                  params {:family (read-string value)
                          :weight "normal"
                          :style "normal"}]
              (rs/emit! (uds/update-font-attrs sid params))))
          (on-font-size-change [event]
            (let [value (dom/event->value event)
                  params {:size (parse-int value)}
                  sid (:id shape)]
              (rs/emit! (uds/update-font-attrs sid params))))
          (on-font-letter-spacing-change [event]
            (let [value (dom/event->value event)
                  params {:letter-spacing (parse-float value)}
                  sid (:id shape)]
              (rs/emit! (uds/update-font-attrs sid params))))
          (on-font-line-height-change [event]
            (let [value (dom/event->value event)
                  params {:line-height (parse-float value)}
                  sid (:id shape)]
              (rs/emit! (uds/update-font-attrs sid params))))
          (on-font-align-change [event value]
            (let [params {:align value}
                  sid (:id shape)]
              (rs/emit! (uds/update-font-attrs sid params))))

          (on-font-style-change [event]
            (let [value (dom/event->value event)
                  [weight style] (read-string value)
                  sid (:id shape)
                  params {:style style
                          :weight weight}]
              (rs/emit! (uds/update-font-attrs sid params))))]
    (let [{:keys [family style weight size align line-height letter-spacing]
           :or {family "sourcesanspro"
                align "left"
                style "normal"
                weight "normal"
                letter-spacing 1
                line-height 1.4
                size 16}} font
          styles (:styles (first (filter #(= (:id %) family) +fonts+)))]
      (html
       [:div.element-set {:key (str (:id menu))}
        [:div.element-set-title (:name menu)]
        [:div.element-set-content

         [:span "Font family"]
         [:div.row-flex
          [:select.input-select {:value (pr-str family)
                                 :on-change on-font-family-change}
           (for [font +fonts+]
             [:option {:value (pr-str (:id font))
                       :key (:id font)} (:name font)])]]

         [:span "Size and Weight"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "Font Size"
            :type "number"
            :min "0"
            :max "200"
            :value size
            :on-change on-font-size-change}]
          [:select.input-select {:value (pr-str [weight style])
                                 :on-change on-font-style-change}
           (for [style styles
                 :let [data (mapv #(get style %) [:weight :style])]]
             [:option {:value (pr-str data)
                       :key (:name style)} (:name style)])]]

         [:span "Line height and Letter spacing"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "Line height"
            :type "number"
            :step "0.1"
            :min "0"
            :max "200"
            :value line-height
            :on-change on-font-line-height-change}]
          [:input.input-text
           {:placeholder "Letter spacing"
            :type "number"
            :step "0.1"
            :min "0"
            :max "200"
            :value letter-spacing
            :on-change on-font-letter-spacing-change}]]


         [:span "Text align"]
         [:div.row-flex.align-icons
          [:span {:class (when (= align "left") "current")
                  :on-click #(on-font-align-change % "left")}
           i/align-left]
          [:span {:class (when (= align "right") "current")
                  :on-click #(on-font-align-change % "right")}
           i/align-right]
          [:span {:class (when (= align "center") "current")
                  :on-click #(on-font-align-change % "center")}
           i/align-center]
          [:span {:class (when (= align "justify") "current")
                  :on-click #(on-font-align-change % "justify")}
           i/align-justify]]]]))))

(def text-menu
  (mx/component
   {:render text-menu-render
    :name "text-menu"
    :mixins [mx/static]}))


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

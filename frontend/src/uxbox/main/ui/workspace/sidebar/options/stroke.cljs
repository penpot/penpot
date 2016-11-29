;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.stroke
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- stroke-menu-render
  [own menu shape]
  (letfn [(change-stroke [value]
            (let [sid (:id shape)]
              (st/emit! (uds/update-stroke-attrs sid value))))
          (on-width-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)]
              (change-stroke {:width value})))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)
                  value (/ value 10000)]
              (change-stroke {:opacity value})))
          (on-stroke-style-change [event]
            (let [value (dom/event->value event)
                  value (read-string value)]
              (change-stroke {:type value})))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  opts {:x x :y y
                        :shape (:id shape)
                        :attr :stroke
                        :transparent? true}]
              (udl/open! :workspace/shape-colorpicker opts)))]
    (let [local (:rum/local own)]
      (html
       [:div.element-set {:key (str (:id menu))}
        [:div.element-set-title (:name menu)]
        [:div.element-set-content
         [:span "Style"]
         [:div.row-flex
          [:select#style.input-select {:placeholder "Style"
                                       :value (:stroke-type shape)
                                       :on-change on-stroke-style-change}
           [:option {:value ":none"} "None"]
           [:option {:value ":solid"} "Solid"]
           [:option {:value ":dotted"} "Dotted"]
           [:option {:value ":dashed"} "Dashed"]
           [:option {:value ":mixed"} "Mixed"]]
          [:div.input-element.pixels
           [:input.input-text
            {:placeholder "Width"
             :type "number"
             :min "0"
             :value (:stroke-width shape "1")
             :on-change on-width-change}]]]

         [:span "Color"]
         [:div.row-flex.color-data
          [:span.color-th
           {:style {:background-color (:stroke shape "#000000")}
            :on-click show-color-picker}]
          [:div.color-info
           [:span (:stroke shape "#000000")]]]

         [:span "Opacity"]
         [:div.row-flex
          [:input.slidebar
           {:type "range"
            :min "0"
            :max "10000"
            :value (* 10000 (:stroke-opacity shape 1))
            :step "1"
            :on-change on-opacity-change}]]]]))))

(def stroke-menu
  (mx/component
   {:render stroke-menu-render
    :name "stroke-menu"
    :mixed [mx/static]}))

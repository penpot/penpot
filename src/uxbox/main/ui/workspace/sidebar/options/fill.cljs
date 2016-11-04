;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.fill
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn fill-menu-render
  [own menu shape]
  (letfn [(change-fill [value]
            (let [sid (:id shape)]
              (rs/emit! (uds/update-fill-attrs sid value))))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (change-fill {:color value})))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)
                  value (/ value 10000)]
              (change-fill {:opacity value})))
          (on-color-picker-event [color]
            (change-fill {:color color}))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  opts {:x x :y y
                        :shape (:id shape)
                        :attr :fill
                        :transparent? true}]
              (udl/open! :workspace/colorpicker opts)))]

    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content

       [:span "Color"]
       [:div.row-flex.color-data
        [:span.color-th
         {:style {:background-color (:fill shape "#000000")}
          :on-click show-color-picker}]
        [:div.color-info
         [:span (:fill shape "#000000")]]]

       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Opacity"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min "0"
          :max "10000"
          :value (* 10000 (:fill-opacity shape 1))
          :step "1"
          :on-change on-opacity-change}]]]])))

(def fill-menu
  (mx/component
   {:render fill-menu-render
    :name "fill-menu"
    :mixins [mx/static]}))

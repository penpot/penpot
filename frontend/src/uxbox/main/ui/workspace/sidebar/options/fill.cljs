;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.fill
  (:require [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.builtins.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]
            [uxbox.util.spec :refer (color?)]))

(mx/defc fill-menu
  {:mixins [mx/static]}
  [menu {:keys [id] :as shape}]
  (letfn [(change-attrs [attrs]
            (st/emit! (uds/update-attrs id attrs)))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (when (color? value)
                (change-attrs {:fill-color value}))))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)
                  value (/ value 10000)]
              (change-attrs {:fill-opacity value})))
          (on-color-picker-event [color]
            (change-attrs {:fill-color color}))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  opts {:x x :y y
                        :shape (:id shape)
                        :attr :fill-color
                        :transparent? true}]
              (udl/open! :workspace/shape-colorpicker opts)))]
    [:div.element-set {:key (str (:id menu))}
     [:div.element-set-title (:name menu)]
     [:div.element-set-content

      [:span "Color"]
      [:div.row-flex.color-data
       [:span.color-th
        {:style {:background-color (:fill-color shape)}
         :on-click show-color-picker}]
       [:div.color-info
        [:input
         {:on-change on-color-change
          :value (:fill-color shape)}]]]

      ;; SLIDEBAR FOR ROTATION AND OPACITY
      [:span "Opacity"]
      [:div.row-flex
       [:input.slidebar
        {:type "range"
         :min "0"
         :max "10000"
         :value (* 10000 (:fill-opacity shape))
         :step "1"
         :on-change on-opacity-change}]]]]))

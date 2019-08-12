;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.fill
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.data :refer [parse-float]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer (tr)]))

(mf/defc fill-menu
  [{:keys [menu shape]}]
  (letfn [(change-attrs [attrs]
            (st/emit! (udw/update-shape-attrs (:id shape) attrs)))
          (on-color-change [event]
            (let [value (dom/event->value event)]
              (change-attrs {:fill-color value})))
          (on-opacity-change [event]
            (let [value (dom/event->value event)
                  value (parse-float value 1)
                  value (/ value 10000)]
              (change-attrs {:fill-opacity value})))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  props {:x x :y y
                         :on-change #(change-attrs {:fill-color %})
                         :default "#ffffff"
                         :value (:fill-color shape)
                         :transparent? true}]
              (modal/show! colorpicker-modal props)))]
    [:div.element-set
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
          :value (:fill-color shape "")}]]]

      ;; SLIDEBAR FOR ROTATION AND OPACITY
      [:span "Opacity"]
      [:div.row-flex
       [:input.slidebar
        {:type "range"
         :min "0"
         :max "10000"
         :value (str (* 10000 (:fill-opacity shape 1)))
         :step "1"
         :on-change on-opacity-change}]]]]))

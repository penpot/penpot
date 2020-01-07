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
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

(mf/defc fill-menu
  [{:keys [shape] :as props}]
  (letfn [(update-shape! [attr value]
            (st/emit! (udw/update-shape (:id shape) {attr value})))
          (on-color-change [event]
            (let [value (-> (dom/get-target event)
                            (dom/get-value))]
              (update-shape! :fill-color value)))
          (on-opacity-change [event]
            (let [value (-> (dom/get-target event)
                            (dom/get-value)
                            (d/parse-double 1)
                            (/ 10000))]
              (update-shape! :fill-opacity value)))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  props {:x x :y y
                         :on-change #(update-shape! :fill-color %)
                         :default "#ffffff"
                         :value (:fill-color shape)
                         :transparent? true}]
              (modal/show! colorpicker-modal props)))]
    [:div.element-set
     [:div.element-set-title (tr "element.fill")]
     [:div.element-set-content

      [:span (tr "workspace.options.color")]
      [:div.row-flex.color-data
       [:span.color-th
        {:style {:background-color (:fill-color shape "#000000")}
         :on-click show-color-picker}]
       [:div.color-info
        [:input
         {:on-change on-color-change
          :value (:fill-color shape "")}]]]

      ;; SLIDEBAR FOR ROTATION AND OPACITY
      [:span (tr "workspace.options.opacity")]
      [:div.row-flex
       [:input.slidebar
        {:type "range"
         :min "0"
         :max "10000"
         :value (str (* 10000 (:fill-opacity shape 1)))
         :step "1"
         :on-change on-opacity-change}]]]]))

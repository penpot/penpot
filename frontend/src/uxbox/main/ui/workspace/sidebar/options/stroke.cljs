;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.stroke
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.data :refer [parse-int parse-float read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :as math]))

(mf/defc stroke-menu
  [{:keys [shape] :as props}]
  (let [show-options (not= (:stroke-style shape) :none)

        on-stroke-style-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/read-string))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-style value}))))

        on-stroke-width-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-double 1))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-width value}))))

        on-stroke-opacity-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-double 1)
                          (/ 10000))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-opacity value}))))

        show-color-picker
        (fn [event]
          (let [x (.-clientX event)
                y (.-clientY event)
                props {:x x :y y
                       :default "#ffffff"
                       :value (:stroke-color shape)
                       :on-change #(st/emit! (udw/update-shape (:id shape) {:stroke-color %}))
                       :transparent? true}]
            (modal/show! colorpicker-modal props)))]

    [:div.element-set
     [:div.element-set-title (tr "workspace.options.stroke")]
     [:div.element-set-content

      ;; Stroke Style & Width
      [:span (tr "workspace.options.stroke.style")]
      [:div.row-flex
       [:select#style.input-select {:value (pr-str (:stroke-style shape))
                                    :on-change on-stroke-style-change}
        [:option {:value ":none"} (tr "workspace.options.stroke.none")]
        [:option {:value ":solid"} (tr "workspace.options.stroke.solid")]
        [:option {:value ":dotted"} (tr "workspace.options.stroke.dotted")]
        [:option {:value ":dashed"} (tr "workspace.options.stroke.dashed")]
        [:option {:value ":mixed"} (tr "workspace.options.stroke.mixed")]]

        [:div.input-element {:class (when show-options "pixels")}
         (when show-options
           [:input.input-text {:type "number"
                              :min "0"
                              :value (-> (:stroke-width shape)
                                         (math/precision 2)
                                         (d/coalesce-str "1"))
                              :on-change on-stroke-width-change}])]]

      ;; Stroke Color
      (when show-options
        [:*
         [:span (tr "workspace.options.color")]

         [:div.row-flex.color-data
          [:span.color-th {:style {:background-color (:stroke-color shape)}
                           :on-click show-color-picker}]
          [:div.color-info
           [:input {:read-only true
                    :default-value (:stroke-color shape "")}]]]

         [:span (tr "workspace.options.opacity")]

         [:div.row-flex
          [:input.slidebar {:type "range"
                            :min "0"
                            :max "10000"
                            :value (-> (:stroke-opacity shape 1)
                                       (* 10000)
                                       (d/coalesce-str "1"))
                            :step "1"
                            :on-change on-stroke-opacity-change}]]])]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.stroke
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer  [tr t]]
   [uxbox.util.math :as math]))

(mf/defc stroke-menu
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)
        show-options (not= (:stroke-style shape) :none)

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
                          (d/parse-integer 0))]
            (st/emit! (udw/update-shape (:id shape) {:stroke-width value}))))

        on-add-stroke
        (fn [event]
          (st/emit! (udw/update-shape (:id shape) {:stroke-style :solid
                                                   :stroke-color "#000000"
                                                   :stroke-opacity 1})))

        on-del-stroke
        (fn [event]
          (st/emit! (udw/update-shape (:id shape) {:stroke-style :none})))

        on-stroke-opacity-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0)
                          (/ 100))]
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

    (if (not= :none (:stroke-style shape :none))
      [:div.element-set
       [:div.element-set-title
        [:span (t locale "workspace.options.stroke")]
        [:div.add-page {:on-click on-del-stroke} i/minus]]

       [:div.element-set-content

        ;; Stroke Color
        [:div.row-flex.color-data
         [:span.color-th {:style {:background-color (:stroke-color shape)}
                          :on-click show-color-picker}]
         [:div.color-info
          [:input {:read-only true
                   :key (:stroke-color shape)
                   :default-value (:stroke-color shape)}]]

         [:div.input-element.percentail
          [:input.input-text {:placeholder ""
                              :value (str (-> (:stroke-opacity shape)
                                              (d/coalesce 1)
                                              (* 100)
                                              (math/round)))
                              :type "number"
                              :on-change on-stroke-opacity-change
                              :min "0"
                              :max "100"}]]

         [:input.slidebar {:type "range"
                           :min "0"
                           :max "100"
                           :value (str (-> (:stroke-opacity shape)
                                           (d/coalesce 1)
                                           (* 100)
                                           (math/round)))
                           :step "1"
                           :on-change on-stroke-opacity-change}]]

        ;; Stroke Style & Width
        [:div.row-flex
         [:select#style.input-select {:value (pr-str (:stroke-style shape))
                                      :on-change on-stroke-style-change}
          [:option {:value ":solid"} (t locale "workspace.options.stroke.solid")]
          [:option {:value ":dotted"} (t locale "workspace.options.stroke.dotted")]
          [:option {:value ":dashed"} (t locale "workspace.options.stroke.dashed")]
          [:option {:value ":mixed"} (t locale "workspace.options.stroke.mixed")]]

         [:div.input-element.pixels
          [:input.input-text {:type "number"
                              :min "0"
                              :value (str (-> (:stroke-width shape)
                                              (d/coalesce 1)
                                              (math/round)))
                              :on-change on-stroke-width-change}]]]]]

      ;; NO STROKE
      [:div.element-set
       [:div.element-set-title
        [:span (t locale "workspace.options.stroke")]
        [:div.add-page {:on-click on-add-stroke} i/close]]])))

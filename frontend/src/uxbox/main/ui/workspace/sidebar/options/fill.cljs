;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.fill
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.dom :as dom]
   [uxbox.util.object :as obj]
   [uxbox.util.math :as math]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(defn- fill-menu-memo-equals?
  [np op]
  (let [new-shape (obj/get np "shape")
        old-shape (obj/get op "shape")]
    (and (identical? (:fill-color new-shape)
                     (:fill-color old-shape))
         (identical? (:fill-opacity new-shape)
                     (:fill-opacity old-shape)))))

(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % fill-menu-memo-equals?)]}
  [{:keys [shape] :as props}]
  (let [locale (i18n/use-locale)

        on-color-change
        (fn [color]
          (st/emit! (udw/update-shape (:id shape) {:fill-color color})))

        on-color-input-change
        (fn [event]
          (let [input (dom/get-target event)
                value (dom/get-value input)]
            (when (dom/valid? input)
              (on-color-change value))))

        on-opacity-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 1)
                          (/ 100))]
            (st/emit! (udw/update-shape (:id shape) {:fill-opacity value}))))

        show-color-picker
        (fn [event]
          (let [x (.-clientX event)
                y (.-clientY event)
                props {:x x :y y
                       :on-change on-color-change
                       :default "#ffffff"
                       :value (:fill-color shape)
                       :transparent? true}]
            (modal/show! colorpicker-modal props)))]

    [:div.element-set
     [:div.element-set-title (t locale "workspace.options.fill")]
     [:div.element-set-content

      [:div.row-flex.color-data
       [:span.color-th
        {:style {:background-color (:fill-color shape)}
         :on-click show-color-picker}]

       [:div.color-info
        [:input {:default-value (:fill-color shape)
                 :ref (fn [el]
                        (when el
                          (set! (.-value el) (:fill-color shape))))
                 :pattern "^#(?:[0-9a-fA-F]{3}){1,2}$"
                 :on-change on-color-input-change}]]

       [:div.input-element.percentail
        [:input.input-text {:type "number"
                            :value (str (-> (:fill-opacity shape)
                                            (d/coalesce 1)
                                            (* 100)
                                            (math/round)))
                            :on-change on-opacity-change
                            :min "0"
                            :max "100"}]]

       [:input.slidebar {:type "range"
                         :min "0"
                         :max "100"
                         :value (str (-> (:fill-opacity shape)
                                         (d/coalesce 1)
                                         (* 100)
                                         (math/round)))
                         :step "1"
                         :on-change on-opacity-change}]]]]))

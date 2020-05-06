;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [uxbox.common.data :as d]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

(def default-options
  "Default data for page metadata."
  {:grid-x 10
   :grid-y 10
   :grid-color "#cccccc"})

(def options-iref
  (l/derived :options refs/workspace-data))

(mf/defc grid-options
  {:wrap [mf/memo]}
  [props]
  (let [options (->> (mf/deref options-iref)
                     (merge default-options))
        on-x-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (dw/update-options {:grid-x value}))))

        on-y-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (dw/update-options {:grid-y value}))))

        change-color
        (fn [color]
          (st/emit! (dw/update-options {:grid-color color})))

        on-color-input-change
        (fn [event]
          (let [input (dom/get-target event)
                value (dom/get-value input)]
            (when (dom/valid? input)
              (change-color value))))

        show-color-picker
        (fn [event]
          (let [x (.-clientX event)
                y (.-clientY event)
                props {:x x :y y
                       :transparent? true
                       :default "#cccccc"
                       :attr :grid-color
                       :on-change change-color}]
            (modal/show! colorpicker-modal props)))]
    [:div.element-set
     [:div.element-set-title (tr "workspace.options.grid-options")]
     [:div.element-set-content
      [:div.row-flex
       [:span.element-set-subtitle (tr "workspace.options.size")]
       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :value (:grid-x options)
                            :on-change on-x-change}]]
       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :value (:grid-y options)
                            :on-change on-y-change}]]]
      [:div.row-flex.color-data
       [:span.element-set-subtitle (tr "workspace.options.color")]
       [:span.color-th {:style {:background-color (:grid-color options)}
                        :on-click show-color-picker}]
       [:div.color-info
        [:input {:default-value (:grid-color options)
                 :ref (fn [el]
                        (when el
                          (set! (.-value el) (:grid-color options))))
                 :pattern "^#(?:[0-9a-fA-F]{3}){1,2}$"
                 :on-change on-color-input-change}]]]]]))

(mf/defc options
  [{:keys [page] :as props}]
  [:div
   [:& grid-options {:page page}]])


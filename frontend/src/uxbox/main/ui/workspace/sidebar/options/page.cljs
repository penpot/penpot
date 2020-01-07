;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.spec :refer [color?]]))

(mf/defc metadata-options
  [{:keys [page] :as props}]
  (let [metadata (:metadata page)
        change-color
        (fn [color]
          #_(st/emit! (->> (assoc metadata :background color)
                           (udp/update-metadata (:id page)))))
        on-color-change
        (fn [event]
          (let [value (dom/event->value event)]
            (change-color value)))

        show-color-picker
        (fn [event]
          (let [x (.-clientX event)
                y (.-clientY event)
                props {:x x :y y
                       :default "#ffffff"
                       :value (:background metadata)
                       :transparent? true
                       :on-change change-color}]
            (modal/show! colorpicker-modal props)))]

    [:div.element-set
     [:div.element-set-title (tr "workspace.options.page-measures")]
     [:div.element-set-content
      [:span (tr "workspace.options.background-color")]
      [:div.row-flex.color-data
       [:span.color-th
        {:style {:background-color (:background metadata "#ffffff")}
         :on-click show-color-picker}]
       [:div.color-info
        [:input
         {:on-change on-color-change
          :value (:background metadata "#ffffff")}]]]]]))

(mf/defc grid-options
  [{:keys [page] :as props}]
  (let [metadata (:metadata page)
        metadata (merge c/page-metadata metadata)]
    (letfn [(on-x-change [event]
              #_(let [value (-> (dom/event->value event)
                              (parse-int nil))]
                (st/emit! (->> (assoc metadata :grid-x-axis value)
                               (udp/update-metadata (:id page))))))
            (on-y-change [event]
              #_(let [value (-> (dom/event->value event)
                              (parse-int nil))]
                (st/emit! (->> (assoc metadata :grid-y-axis value)
                               (udp/update-metadata (:id page))))))

            (change-color [color]
              #_(st/emit! (->> (assoc metadata :grid-color color)
                             (udp/update-metadata (:id page)))))
            (on-color-change [event]
              (let [value (dom/event->value event)]
                (change-color value)))

            (show-color-picker [event]
              (let [x (.-clientX event)
                    y (.-clientY event)
                    props {:x x :y y
                           :transparent? true
                           :default "#cccccc"
                           :attr :grid-color
                           :on-change change-color}]
                (modal/show! colorpicker-modal props)))]
      [:div.element-set
       [:div.element-set-title (tr "element.page-grid-options")]
       [:div.element-set-content
        [:span (tr "workspace.options.size")]
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :value (:grid-x-axis metadata)
            :on-change on-x-change
            :placeholder "x"}]]
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :value (:grid-y-axis metadata)
            :on-change on-y-change
            :placeholder "y"}]]]
        [:span (tr "workspace.options.color")]
        [:div.row-flex.color-data
         [:span.color-th
          {:style {:background-color (:grid-color metadata)}
           :on-click show-color-picker}]
         [:div.color-info
          [:input
           {:on-change on-color-change
            :value (:grid-color metadata "#cccccc")}]]]]])))

(mf/defc options
  [{:keys [page] :as props}]
  [:div
   [:& metadata-options {:page page}]
   [:& grid-options {:page page}]])


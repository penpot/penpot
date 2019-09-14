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
   [uxbox.main.data.pages :as udp]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.spec :refer [color?]]))

(mf/defc measures-menu
  [{:keys [menu page] :as props}]
  (let [metadata (:metadata page)
        metadata (merge c/page-metadata metadata)]
    (letfn [(on-size-change [event attr]
              (let [value (-> (dom/event->value event)
                              (parse-int nil))]
                (st/emit! (->> (assoc metadata attr value)
                               (udp/update-metadata (:id page))))))

            (change-color [color]
              (st/emit! (->> (assoc metadata :background color)
                             (udp/update-metadata (:id page)))))

            (on-color-change [event]
              (let [value (dom/event->value event)]
                (change-color value)))

            (on-name-change [event]
              (let [value (-> (dom/event->value event)
                              (str/trim))]
                (st/emit! (-> (assoc page :name value)
                              (udp/update-page-attrs)))))

            (show-color-picker [event]
              (let [x (.-clientX event)
                    y (.-clientY event)
                    props {:x x :y y
                           :default "#ffffff"
                           :value (:background metadata)
                           :transparent? true
                           :on-change change-color}]
                (modal/show! colorpicker-modal props)))]

      [:div.element-set
       [:div.element-set-title (tr (:name menu))]
       [:div.element-set-content
        [:span (tr "ds.name")]
        [:div.row-flex
         [:div.input-element
          [:input.input-text
           {:type "text"
            :on-change on-name-change
            :value (str (:name page))
            :placeholder "page name"}]]]

        [:span (tr "ds.size")]
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :on-change #(on-size-change % :width)
            :value (str (:width metadata))
            :placeholder (tr "ds.width")}]]
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :on-change #(on-size-change % :height)
            :value (str (:height metadata))
            :placeholder (tr "ds.height")}]]]

        [:span (tr "ds.background-color")]
        [:div.row-flex.color-data
         [:span.color-th
          {:style {:background-color (:background metadata)}
           :on-click show-color-picker}]
         [:div.color-info
          [:input
           {:on-change on-color-change
            :value (:background metadata)}]]]]])))

(mf/defc grid-options-menu
  [{:keys [menu page] :as props}]
  (let [metadata (:metadata page)
        metadata (merge c/page-metadata metadata)]
    (letfn [(on-x-change [event]
              (let [value (-> (dom/event->value event)
                              (parse-int nil))]
                (st/emit! (->> (assoc metadata :grid-x-axis value)
                               (udp/update-metadata (:id page))))))
            (on-y-change [event]
              (let [value (-> (dom/event->value event)
                              (parse-int nil))]
                (st/emit! (->> (assoc metadata :grid-y-axis value)
                               (udp/update-metadata (:id page))))))

            (change-color [color]
              (st/emit! (->> (assoc metadata :grid-color color)
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
       [:div.element-set-title (tr (:name menu))]
       [:div.element-set-content
        [:span (tr "ds.size")]
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
        [:span (tr "ds.color")]
        [:div.row-flex.color-data
         [:span.color-th
          {:style {:background-color (:grid-color metadata)}
           :on-click show-color-picker}]
         [:div.color-info
          [:input
           {:on-change on-color-change
            :value (:grid-color metadata "#cccccc")}]]]]])))

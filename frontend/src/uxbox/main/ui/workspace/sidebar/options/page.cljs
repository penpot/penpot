;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.workspace.base :refer [page-ref]]
            [uxbox.main.ui.workspace.colorpicker]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.data :refer [parse-int]]
            [uxbox.util.dom :as dom]))

(mx/defcs measures-menu
  {:mixins [mx/static mx/reactive]}
  [own menu]
  (let [{:keys [id metadata] :as page} (mx/react page-ref)
        {:keys [width height background] :as metadata} metadata]
    (letfn [(on-width-change []
              (when-let [value (-> (mx/ref-node own "width")
                                   (dom/get-value)
                                   (parse-int nil))]
                (->> (assoc metadata :width value)
                     (udp/update-metadata id)
                     (st/emit!))))
            (on-height-change []
              (when-let [value (-> (mx/ref-node own "height")
                                   (dom/get-value)
                                   (parse-int nil))]
                (->> (assoc metadata :height value)
                     (udp/update-metadata id)
                     (st/emit!))))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  opts {:x x :y y
                        :default "#ffffff"
                        :transparent? true
                        :attr :background}]
              (udl/open! :workspace/page-colorpicker opts)))]
      [:div.element-set
       [:div.element-set-title (:name menu)]
       [:div.element-set-content
        [:span "Size"]
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :ref "width"
            :on-change on-width-change
            :value (:width metadata)
            :placeholder "width"}]]
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :ref "height"
            :on-change on-height-change
            :value (:height metadata)
            :placeholder "height"}]]]
        [:span "Background color"]
        [:div.row-flex.color-data
         [:span.color-th
          {:style {:background-color (or background "#ffffff")}
           :on-click show-color-picker}]
         [:div.color-info
          [:span (or background "#ffffff")]]]]])))

(mx/defcs grid-options-menu
  {:mixins [mx/static mx/reactive]}
  [own menu]
  (let [{:keys [id metadata] :as page} (mx/react page-ref)]
    (letfn [(on-x-change []
              (when-let [value (-> (mx/ref-node own "x-axis")
                                   (dom/get-value)
                                   (parse-int nil))]
                (st/emit!
                 (->> (assoc metadata :grid-x-axis value)
                      (udw/update-metadata id)))))
            (on-y-change []
              (when-let [value (-> (mx/ref-node own "y-axis")
                                   (dom/get-value)
                                   (parse-int nil))]
                (st/emit!
                 (->> (assoc metadata :grid-y-axis value)
                      (udw/update-metadata id)))))
            (on-magnet-change []
              (let [checked? (dom/checked? (mx/ref-node own "magnet"))
                    metadata (assoc metadata :grid-alignment checked?)]
                (st/emit! (udw/update-metadata id metadata))))
            (show-color-picker [event]
              (let [x (.-clientX event)
                    y (.-clientY event)
                    opts {:x x :y y
                          :transparent? true
                          :default "#cccccc"
                          :attr :grid-color}]
                (udl/open! :workspace/page-colorpicker opts)))]
      [:div.element-set
       [:div.element-set-title (:name menu)]
       [:div.element-set-content
        [:span "Size"]
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :ref "x-axis"
            :value (:grid-x-axis metadata 10)
            :on-change on-x-change
            :placeholder "x"}]]
         [:div.input-element.pixels
          [:input.input-text
           {:type "number"
            :ref "y-axis"
            :value (:grid-y-axis metadata 10)
            :on-change on-y-change
            :placeholder "y"}]]]
        [:span "Color"]
        [:div.row-flex.color-dat
         [:span.color-th
          {:style {:background-color (:grid-color metadata "#cccccc")}
           :on-click show-color-picker}]
         [:div.color-info
          [:span (:grid-color metadata "#cccccc")]]]

        [:span "Magnet option"]
        [:div.row-flex
         [:div.input-checkbox.check-primary
          [:input
           {:type "checkbox"
            :ref "magnet"
            :id "magnet"
            :on-change on-magnet-change
            :checked (when (:grid-alignment metadata) "checked")}]
          [:label {:for "magnet"} "Activate magnet"]]]]])))

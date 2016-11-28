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
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.workspace.base :refer [page-ref]]
            [uxbox.main.ui.workspace.colorpicker :refer [colorpicker]]
            [uxbox.main.ui.workspace.recent-colors :refer [recent-colors]]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.data :refer [parse-int]]
            [uxbox.util.dom :as dom]))

(mx/defcs measures-menu
  {:mixins [mx/static mx/reactive]}
  [own menu]
  (let [{:keys [id] :as page} (mx/react page-ref)
        {:keys [width height] :as metadata} (:metadata page)]
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
                     (st/emit!))))]
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
         [:span.color-th {:style {:background-color "#d2d2d2"}}]
         [:div.color-info
          [:span "#D2D2D2"]]]]])))

(mx/defc grid-options-menu
  {:mixins [mx/static]}
  [menu]
  [:div.element-set
   [:div.element-set-title (:name menu)]
   [:div.element-set-content
    [:span "Size"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text {:type "number" :placeholder "x"}]]
     [:div.input-element.pixels
      [:input.input-text {:type "number" :placeholder "y"}]]]
    [:span "Color"]
    [:div.row-flex.color-data
     [:span.color-th {:style {:background-color "#d2d2d2"}}]
     [:div.color-info
      [:span "#D2D2D2"]]]
    [:span "Magnet option"]
    [:div.row-flex
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox" :id "magnet" :value "Yes"}]
      [:label {:for "magnet"} "Activate magnet"]]]]])

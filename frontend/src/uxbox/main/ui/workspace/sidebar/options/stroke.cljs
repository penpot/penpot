;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.stroke
  (:require [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.builtins.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]
            [uxbox.util.math :refer (precision-or-0)]
            [uxbox.util.spec :refer (color?)]))

(mx/defcs stroke-menu
  {:mixins [mx/static (mx/local)]}
  [{:keys [rum/local]} menu {:keys [id] :as shape}]
  (letfn [(on-width-change [event]
            (let [value (-> (dom/event->value event)
                            (parse-float 1))]
              (st/emit! (uds/update-attrs id {:stroke-width value}))))
          (on-opacity-change [event]
            (let [value (-> (dom/event->value event)
                            (parse-float 1)
                            (/ 10000))]
              (st/emit! (uds/update-attrs id {:stroke-opacity value}))))
          (on-stroke-style-change [event]
            (let [value (-> (dom/event->value event)
                            (read-string))]
              (st/emit! (uds/update-attrs id {:stroke-style value}))))
          (on-stroke-color-change [event]
            (let [value (dom/event->value event)]
              (when (color? value)
                (st/emit! (uds/update-attrs id {:stroke-color value})))))
          (on-border-change [event attr]
            (let [value (-> (dom/event->value event)
                            (parse-int nil))]
              (if (:border-lock @local)
                (st/emit! (uds/update-attrs id {:rx value :ry value}))
                (st/emit! (uds/update-attrs id {attr value})))))
          (on-border-proportion-lock [event]
            (swap! local update :border-lock not))
          (show-color-picker [event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  opts {:x x :y y
                        :shape (:id shape)
                        :attr :stroke-color
                        :transparent? true}]
              (udl/open! :workspace/shape-colorpicker opts)))]
    [:div.element-set {:key (str (:id menu))}
     [:div.element-set-title (:name menu)]
     [:div.element-set-content
      [:span "Style"]
      [:div.row-flex
       [:select#style.input-select {:placeholder "Style"
                                    :value (pr-str (:stroke-style shape))
                                    :on-change on-stroke-style-change}
        [:option {:value ":none"} "None"]
        [:option {:value ":solid"} "Solid"]
        [:option {:value ":dotted"} "Dotted"]
        [:option {:value ":dashed"} "Dashed"]
        [:option {:value ":mixed"} "Mixed"]]
       [:div.input-element.pixels
        [:input.input-text
         {:placeholder "Width"
          :type "number"
          :min "0"
          :value (precision-or-0 (:stroke-width shape 1) 2)
          :on-change on-width-change}]]]

      [:span "Color"]
      [:div.row-flex.color-data
       [:span.color-th
        {:style {:background-color (:stroke-color shape)}
         :on-click show-color-picker}]
       [:div.color-info
        [:input
         {:on-change on-stroke-color-change
          :value (:stroke-color shape)}]]]

      [:span "Radius"]
      [:div.row-flex
       [:input.input-text
        {:placeholder "rx"
         :type "number"
         :value (precision-or-0 (:rx shape 0) 2)
         :on-change #(on-border-change % :rx)}]
       [:div.lock-size
        {:class (when (:border-lock @local) "selected")
         :on-click on-border-proportion-lock}
        i/lock]
       [:input.input-text
        {:placeholder "ry"
         :type "number"
         :value (precision-or-0 (:ry shape 0) 2)
         :on-change #(on-border-change % :ry)}]]

      [:span "Opacity"]
      [:div.row-flex
       [:input.slidebar
        {:type "range"
         :min "0"
         :max "10000"
         :value (* 10000 (:stroke-opacity shape))
         :step "1"
         :on-change on-opacity-change}]]]]))

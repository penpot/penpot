;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.circle-measures
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.common.i18n :refer (tr)]
            [uxbox.common.router :as r]
            [uxbox.common.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.icons :as i]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.workspace.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- circle-measures-menu-render
  [own menu shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-radius-attrs sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (rs/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-position sid props))))]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       ;; SLIDEBAR FOR ROTATION AND OPACITY
       [:span "Size"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "Width"
          :type "number"
          :min "0"
          :value (:rx shape)
          :on-change (partial on-size-change :rx)}]
        [:div.lock-size i/lock]
        [:input.input-text
         {:placeholder "Height"
          :type "number"
          :min "0"
          :value (:ry shape)
          :on-change (partial on-size-change :ry)}]]

       [:span "Position"]
       [:div.row-flex
        [:input.input-text
         {:placeholder "cx"
          :type "number"
          :value (:cx shape "")
          :on-change (partial on-pos-change :x)}]
        [:input.input-text
         {:placeholder "cy"
          :type "number"
          :value (:cy shape "")
          :on-change (partial on-pos-change :y)}]]

       [:span "Rotation"]
       [:div.row-flex
        [:input.slidebar
         {:type "range"
          :min 0
          :max 360
          :value (:rotation shape 0)
          :on-change on-rotation-change}]]

       [:div.row-flex
        [:input.input-text
         {:placeholder ""
          :type "number"
          :min 0
          :max 360
          :value (:rotation shape "0")
          :on-change on-rotation-change
          }]
        [:input.input-text
         {:style {:visibility "hidden"}}]
        ]]]
     )))

(def circle-measures-menu
  (mx/component
   {:render circle-measures-menu-render
    :name "circle-measures-menu"
    :mixins [mx/static]}))

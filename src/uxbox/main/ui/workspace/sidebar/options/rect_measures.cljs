;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.rect-measures
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.workspace.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn rect-measures-menu-render
  [own menu shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-size sid props))))
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
              (rs/emit! (uds/update-position sid props))))
          (on-border-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (rs/emit! (uds/update-radius-attrs sid props))))]
    (let [size (geom/size shape)]
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
            :value (:width size)
            :on-change (partial on-size-change :width)}]
          [:div.lock-size i/lock]
          [:input.input-text
           {:placeholder "Height"
            :type "number"
            :min "0"
            :value (:height size)
            :on-change (partial on-size-change :height)}]]

         [:span "Position"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "x"
            :type "number"
            :value (:x1 shape "")
            :on-change (partial on-pos-change :x)}]
          [:input.input-text
           {:placeholder "y"
            :type "number"
            :value (:y1 shape "")
            :on-change (partial on-pos-change :y)}]]

         [:span "Border radius"]
         [:div.row-flex
          [:input.input-text
           {:placeholder "rx"
            :type "number"
            :value (:rx shape "")
            :on-change (partial on-border-change :rx)}]
          [:div.lock-size i/lock]
          [:input.input-text
           {:placeholder "ry"
            :type "number"
            :value (:ry shape "")
            :on-change (partial on-border-change :ry)}]]

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
          ]]]))))

(def rect-measures-menu
  (mx/component
   {:render rect-measures-menu-render
    :name "rect-measures"
    :mixins [mx/static]}))


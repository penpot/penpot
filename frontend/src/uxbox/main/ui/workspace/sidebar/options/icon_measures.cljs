;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.icon-measures
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.math :refer (precision)]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- icon-measures-menu-render
  [own menu shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (st/emit! (uds/update-size sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (st/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  props {attr value}]
              (st/emit! (uds/update-position sid props))))
          (on-proportion-lock-change [event]
            (if (:proportion-lock shape)
              (st/emit! (uds/unlock-proportions (:id shape)))
              (st/emit! (uds/lock-proportions (:id shape)))))]
    (let [size (geom/size shape)]
      (html
       [:div.element-set {:key (str (:id menu))}
        [:div.element-set-title (:name menu)]
        [:div.element-set-content
         ;; SLIDEBAR FOR ROTATION AND OPACITY
         [:span "Size"]
         [:div.row-flex
          [:div.input-element.pixels
           [:input.input-text
            {:placeholder "Width"
             :type "number"
             :min "0"
             :value (precision (:width size) 2)
             :on-change (partial on-size-change :width)}]]
          [:div.lock-size
           {:class (when (:proportion-lock shape) "selected")
            :on-click on-proportion-lock-change}
           i/lock]
          [:div.input-element.pixels
           [:input.input-text
            {:placeholder "Height"
             :type "number"
             :min "0"
             :value (precision (:height size) 2)
             :on-change (partial on-size-change :height)}]]]

         [:span "Position"]
         [:div.row-flex
          [:div.input-element.pixels
           [:input.input-text
            {:placeholder "X"
             :type "number"
             :value (precision (:x1 shape 0) 2)
             :on-change (partial on-pos-change :x)}]]
          [:div.input-element.pixels
           [:input.input-text
            {:placeholder "Y"
             :type "number"
             :value (precision (:y1 shape 0) 2)
             :on-change (partial on-pos-change :y)}]]]

         [:span "Rotation"]
         [:div.row-flex
          [:input.slidebar
           {:type "range"
            :min 0
            :max 360
            :value (:rotation shape 0)
            :on-change on-rotation-change}]]

         [:div.row-flex
          [:div.input-element.degrees
           [:input.input-text
            {:placeholder ""
             :type "number"
             :min 0
             :max 360
             :value (precision (:rotation shape 0) 2)
             :on-change on-rotation-change
            }]]
          [:input.input-text
           {:style {:visibility "hidden"}}]
          ]]]))))

(def icon-measures-menu
  (mx/component
   {:render icon-measures-menu-render
    :name "icon-measures-menu"
    :mixins [mx/static]}))

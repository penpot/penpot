;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.colorpicker
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.locales :refer (tr)]
            [uxbox.router :as r]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as udw]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.lightbox :as lbx]
            [uxbox.ui.colorpicker :as cp]
            [uxbox.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.util.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- colorpicker-render
  [own {:keys [x y shape] :as opts}]
  (println opts)
  (let [shape {}
        on-change (constantly nil)
        left (- x 260)
        top (- y 150)]
    (letfn [(on-color-change [event]
              (let [value (dom/event->value event)]
                (on-change {:color value})))]
      (html
       ;; COLOR PICKER TOOLTIP
       [:div.colorpicker-tooltip
        {:style {:left (str left "px")
                 :top (str top "px")}}

        (cp/colorpicker
         :theme :small
         :value (:stroke shape "#000000")
         :on-change (constantly nil))

        (recent-colors shape (constantly nil) #_#(change-stroke {:color %}))

        #_[:span "Color options"]
        #_[:div.row-flex
         [:span.color-th.palette-th i/picker]
         [:span.color-th.palette-th i/palette]]]))))

(def colorpicker
  (mx/component
   {:render colorpicker-render
    :name "colorpicker"
    :mixins []}))

(defmethod lbx/render-lightbox :workspace/colorpicker
  [params]
  (colorpicker params))

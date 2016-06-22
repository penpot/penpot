;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.colorpicker
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.common.i18n :refer (tr)]
            [uxbox.common.router :as r]
            [uxbox.common.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.common.ui.mixins :as mx]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :as cp]
            [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.common.geom :as geom]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

(defn- focus-shape
  [id]
  (as-> (l/in [:shapes-by-id id]) $
    (l/derive $ st/state)))

(defn- colorpicker-render
  [own {:keys [x y shape attr] :as opts}]
  (let [shape (rum/react (focus-shape shape))
        left (- x 260)
        top (- y 50)]
    (letfn [(change-color [color]
              (let [attrs {:color color}]
                (rs/emit!
                 (case attr
                   :stroke (uds/update-stroke-attrs (:id shape) attrs)
                   :fill (uds/update-fill-attrs (:id shape) attrs)))))
            (on-change-color [event]
              (let [color (dom/event->value event)]
                (change-color color)))]
      (html
       [:div.colorpicker-tooltip
        {:style {:left (str left "px")
                 :top (str top "px")}}

        (cp/colorpicker
         :theme :small
         :value (get shape attr "#000000")
         :on-change change-color)

        (recent-colors shape change-color)]))))

(def colorpicker
  (mx/component
   {:render colorpicker-render
    :name "colorpicker"
    :mixins [rum/reactive mx/static]}))

(defmethod lbx/render-lightbox :workspace/colorpicker
  [params]
  (colorpicker params))

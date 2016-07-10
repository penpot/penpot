;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer.canvas
  (:require [sablono.core :refer-macros [html]]
            [lentes.core :as l]
            [rum.core :as rum]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.main.state :as st]
            [uxbox.main.ui.shapes :as uus]
            [uxbox.main.ui.icons :as i]
            [uxbox.view.ui.viewer.shapes :as shapes]))

;; --- Refs

(defn- resolve-selected-page
  [state]
  (let [index (get-in state [:route :params :id])
        index (parse-int index 0)]
    (get-in state [:pages index])))

(def page-ref
  (-> (l/lens resolve-selected-page)
      (l/derive st/state)))

;; --- Background (Component)

(mx/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill "white"}])

;; --- Canvas (Component)

(declare shape)

(mx/defc canvas
  {:mixins [mx/static mx/reactive]}
  []
  (let [page (rum/react page-ref)
        width (:width page)
        height (:height page)]
    [:div.view-canvas
     [:svg.page-layout {:width width :height height}
      (background)
      (for [id (reverse (:shapes page))]
        (-> (shapes/shape id)
            (rum/with-key (str id))))]]))


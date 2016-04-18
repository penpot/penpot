;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.selection
  "Multiple selection handlers component."
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as st]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom :as geom]))

;; --- Lenses

(def ^:const selected-shapes-l
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (mapv #(get-in state [:shapes-by-id %]) selected)))]
    (-> (l/getter getter)
        (l/focus-atom  st/state))))

;; --- Selection Handlers (Component)

(defn selection-handlers-render
  [own]
  (let [shapes (rum/react selected-shapes-l)]
    (when (> (count shapes) 1)
      (let [{:keys [width height x y]} (geom/outer-rect-coll shapes)]
        (html
         [:g.controls
          [:rect {:x x :y y :width width :height height
                  :stroke-dasharray "5,5"
                  :style {:stroke "#333" :fill "transparent"
                          :stroke-opacity "1"}}]])))))

(def selection-handlers
  (mx/component
   {:render selection-handlers-render
    :name "selection-handlers"
    :mixins [rum/reactive mx/static]}))

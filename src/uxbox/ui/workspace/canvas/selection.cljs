;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.canvas.selection
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.state :as ust]
            [uxbox.shapes :as ush]
            [uxbox.util.lens :as ul]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]))

(def ^:const selected-shapes-l
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (mapv #(get-in state [:shapes-by-id %]) selected)))]
    (as-> (ul/getter getter) $
      (l/focus-atom $ ust/state))))

(defn shapes-selection-render
  [own]
  (let [shapes (rum/react selected-shapes-l)]
    (when (> (count shapes) 1)
      (let [{:keys [width height x y]} (ush/outer-rect shapes)]
        (html
         [:g.controls
          [:rect {:x x :y y :width width :height height
                  :stroke-dasharray "5,5"
                  :style {:stroke "#333" :fill "transparent"
                          :stroke-opacity "1"}}]])))))

(def ^:const shapes-selection
  (mx/component
   {:render shapes-selection-render
    :name "shapes-selection"
    :mixins [rum/reactive mx/static]}))

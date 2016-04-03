;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.grid
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grid
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn grid-render
  [own zoom]
  (let [page (rum/react wb/page-l)
        opts (:options page)
        step-size-x (/ (:grid/x-axis opts 10) zoom)
        step-size-y (/ (:grid/y-axis opts 10) zoom)
        grid-color (:grid/color opts "#cccccc")
        ticks-mod (/ 100 zoom)
        vertical-ticks (range (- 0 wb/canvas-start-y)
                              (- (:width page) wb/canvas-start-y)
                              step-size-x)
        horizontal-ticks (range (- 0 wb/canvas-start-x)
                                (- (:height page) wb/canvas-start-x)
                                step-size-y)]
    (letfn [(vertical-line [position value]
              (if (< (mod value ticks-mod) step-size-x)
                (html [:line {:key position
                              :y1 0
                              :y2 (:height page)
                              :x1 position
                              :x2 position
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.75}])
                (html [:line {:key position
                              :y1 0
                              :y2 (:height page)
                              :x1 position
                              :x2 position
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.25}])))
            (horizontal-line [position value]
              (if (< (mod value ticks-mod) step-size-y)
                (html [:line {:key position
                              :y1 position
                              :y2 position
                              :x1 0
                              :x2 (:width page)
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.75}])
                (html [:line {:key position
                              :y1 position
                              :y2 position
                              :x1 0
                              :x2 (:width page)
                              :stroke grid-color
                              :stroke-width (/ 0.5 zoom)
                              :opacity 0.25}])))]
      (html
       [:g.grid
        (for [tick vertical-ticks]
          (let [position (+ tick wb/canvas-start-x)
                line (vertical-line position tick)]
            (rum/with-key line (str "tick-" tick))))
        (for [tick horizontal-ticks]
          (let [position (+ tick wb/canvas-start-y)
                line (horizontal-line position tick)]
            (rum/with-key line (str "tick-" tick))))]))))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static rum/reactive]}))


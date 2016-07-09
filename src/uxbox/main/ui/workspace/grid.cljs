;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.grid
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.main.constants :as c]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.workspace.base :as wb]))

;; --- Grid (Component)

(declare vertical-line)
(declare horizontal-line)

(defn- grid-render
  [own]
  (let [options (:options (mx/react wb/page-l))
        color (:grid/color options "#cccccc")
        width c/viewport-width
        height c/viewport-height
        x-ticks (range (- 0 c/canvas-start-x)
                       (- width c/canvas-start-x)
                       (:grid/x-axis options 10))

        y-ticks (range (- 0 c/canvas-start-x)
                       (- height c/canvas-start-x)
                       (:grid/y-axis options 10))

        path (as-> [] $
               (reduce (partial vertical-line height) $ x-ticks)
               (reduce (partial horizontal-line width) $ y-ticks))]
    (html
     [:g.grid {:style {:pointer-events "none"}}
      [:path {:d (str/join " " path) :stroke color :opacity "0.3"}]])))

(def grid
  (mx/component
   {:render grid-render
    :name "grid"
    :mixins [mx/static mx/reactive]}))

;; --- Helpers

(defn- horizontal-line
  [width acc value]
  (let [pos (+ value c/canvas-start-y)]
    (conj acc (str/format "M %s %s L %s %s" 0 pos width pos))))

(defn- vertical-line
  [height acc value]
  (let [pos (+ value c/canvas-start-y)]
    (conj acc (str/format "M %s %s L %s %s" pos 0 pos height))))

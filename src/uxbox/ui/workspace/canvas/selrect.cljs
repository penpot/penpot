;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.ui.workspace.canvas.selrect
  "Components for indicate the user selection and selected shapes group."
  (:require-macros [uxbox.util.syntax :refer [define-once]])
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.matrix :as gmx]
            [uxbox.util.dom :as dom]))

(defonce selrect-pos (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare selrect->rect)

(defn selrect-render
  [own]
  (when-let [data (rum/react selrect-pos)]
    (let [{:keys [x y width height]} (selrect->rect data)]
      (html
       [:rect.selection-rect
        {:x x
         :y y
         :width width
         :height height}]))))

(def ^:const ^:private selrect
  (mx/component
   {:render selrect-render
    :name "selrect"
    :mixins [mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- selrect->rect
  ([data] (selrect->rect data false))
  ([data translate?]
   (let [start (:start data)
         current (:current data)
         start-x (min (:x start) (:x current))
         start-y (min (:y start) (:y current))
         current-x (max (:x start) (:x current))
         current-y (max (:y start) (:y current))
         width (- current-x start-x)
         height (- current-y start-y)]
     {:x start-x
      :y start-y
      :width (- current-x start-x)
      :height (- current-y start-y)})))

(defn- translate-to-canvas
  [selrect]
  (let [startx (* wb/canvas-start-x @wb/zoom-l)
        starty (* wb/canvas-start-y @wb/zoom-l)]
    (assoc selrect
           :x (- (:x selrect) startx)
           :y (- (:y selrect) starty)
           :width (/ (:width selrect) @wb/zoom-l)
           :height (/ (:height selrect) @wb/zoom-l))))

(define-once :selrect-subscriptions
  (letfn [(on-value [pos]
            (swap! selrect-pos assoc :current pos))

          (on-complete []
            (let [selrect (selrect->rect @selrect-pos)
                  selrect (translate-to-canvas selrect)]
              (rs/emit! (dw/select-shapes selrect))
              (reset! selrect-pos nil)))

          (init []
            (let [stoper (->> uuc/actions-s
                              (rx/map :type)
                              (rx/filter #(empty? %))
                              (rx/take 1))
                  pos @wb/mouse-viewport-a]
              (reset! selrect-pos {:start pos :current pos})

              (as-> wb/mouse-viewport-s $
                (rx/take-until stoper $)
                (rx/subscribe $ on-value nil on-complete))))]

    (as-> uuc/actions-s $
      (rx/map :type $)
      (rx/dedupe $)
      (rx/filter #(= "ui.selrect"  %) $)
      (rx/on-value $ init))))


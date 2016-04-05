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

(def ^:const ^:private zoom-l
  (-> (l/in [:workspace :zoom])
      (l/focus-atom st/state)))

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

(defn selrect->rect
  ([data] (selrect->rect data false))
  ([data translate?]
   (let [start (:start data)
         current (:current data )

         zoom (or @zoom-l 1)
         start (if translate?
                 (gpt/multiply start (- zoom (- zoom 1)))
                 start)

         current (if translate?
                   (gpt/multiply current (- zoom (- zoom 1)))
                   current)

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

(define-once :selrect-subscriptions
  (letfn [(on-value [pos]
            (let [pos' (as-> (gmx/matrix) $
                         (gmx/scale $ (or @zoom-l 1))
                         (gpt/transform-point pos $))]
              ;; (println "on-value" pos pos')
              (swap! selrect-pos assoc :current pos)))

          (translate-selrect [selrect]
            (let [zoom (or @zoom-l 1)
                  startx (* wb/canvas-start-x zoom)
                  starty (* wb/canvas-start-y zoom)]
              (assoc selrect
                     :x (- (:x selrect) startx)
                     :y (- (:y selrect) starty))))

          (on-complete []
            (let [selrect (selrect->rect @selrect-pos true)
                  selrect' (translate-selrect selrect)]
              ;; (println selrect "---" selrect2)
              (rs/emit! (dw/select-shapes selrect'))
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


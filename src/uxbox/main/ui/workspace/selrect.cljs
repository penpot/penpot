;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.selrect
  "Components for indicate the user selection and selected shapes group."
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [beicon.core :as rx]
            [uxbox.main.constants :as c]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.core :as uuc]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.workspace.base :as wb]))

(defonce position (atom nil))

;; --- Selrect (Component)

(declare selrect->rect)
(declare watch-selrect-actions)

(defn- selrect-render
  [own]
  (when-let [data (rum/react position)]
    (let [{:keys [x y width height]} (selrect->rect data)]
      (html
       [:rect.selection-rect
        {:x x
         :y y
         :width width
         :height height}]))))

(defn- selrect-will-mount
  [own]
  (assoc own ::sub (watch-selrect-actions)))

(defn- selrect-will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(defn- selrect-transfer-state
  [oldown own]
  (assoc own ::sub (::sub oldown)))

(def selrect
  (mx/component
   {:render selrect-render
    :name "selrect"
    :will-mount selrect-will-mount
    :will-unmount selrect-will-unmount
    :transfer-state selrect-transfer-state
    :mixins [mx/static mx/reactive]}))

;; --- Implementation

(defn- selrect->rect
  [data]
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
     :height (- current-y start-y)}))

(defn- translate-to-canvas
  "Translate the given rect to the canvas coordinates system."
  [rect]
  (let [zoom @wb/zoom-l
        startx (* c/canvas-start-x zoom)
        starty (* c/canvas-start-y zoom)]
    (assoc rect
           :x (- (:x rect) startx)
           :y (- (:y rect) starty)
           :width (/ (:width rect) zoom)
           :height (/ (:height rect) zoom))))

(defn- watch-selrect-actions
  []
  (letfn [(on-value [pos]
            (swap! position assoc :current pos))

          (on-complete []
            (rs/emit! (-> (selrect->rect @position)
                          (translate-to-canvas)
                          (uds/select-shapes)))
            (reset! position nil))

          (init []
            (let [stoper (->> uuc/actions-s
                              (rx/map :type)
                              (rx/filter #(empty? %))
                              (rx/take 1))
                  pos @wb/mouse-viewport-a]
              (reset! position {:start pos :current pos})

              (as-> wb/mouse-viewport-s $
                (rx/take-until stoper $)
                (rx/subscribe $ on-value nil on-complete))))]

    (as-> uuc/actions-s $
      (rx/map :type $)
      (rx/dedupe $)
      (rx/filter #(= "ui.selrect"  %) $)
      (rx/on-value $ init))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.selrect
  "Mouse selection interaction and component."
  (:require [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.constants :as c]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.util.rlocks :as rlocks]))

(defonce position (atom nil))

;; --- Selrect (Component)

(declare selrect->rect)
(declare watch-selrect-actions)

(defn- will-mount
  [own]
  (assoc own ::sub (watch-selrect-actions)))

(defn- will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(mx/defc selrect
  {:will-mount will-mount
   :will-unmount will-unmount
   :mixins [mx/static mx/reactive]}
  []
  (when-let [data (mx/react position)]
    (let [{:keys [x y width height]} (selrect->rect data)]
      [:rect.selection-rect
       {:x x
        :y y
        :width width
        :height height}])))

;; --- Interaction

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
  (let [zoom @wb/zoom-ref
        startx (* c/canvas-start-x zoom)
        starty (* c/canvas-start-y zoom)]
    (assoc rect
           :x (- (:x rect) startx)
           :y (- (:y rect) starty)
           :width (/ (:width rect) zoom)
           :height (/ (:height rect) zoom))))

(declare on-start)

(defn- watch-selrect-actions
  []
  (let [stream (->> (rx/map first rlocks/stream)
                    (rx/filter #(= % :ui/selrect)))]
    (rx/subscribe stream on-start)))

(defn- on-move
  "Function executed on each mouse movement while selrect
  interaction is active."
  [pos]
  (swap! position assoc :current pos))

(defn- on-complete
  "Function executed when the selection rect
  interaction is terminated."
  []
  (let [rect (-> (selrect->rect @position)
                 (translate-to-canvas))]
    (rs/emit! (uds/deselect-all)
              (uds/select-shapes rect))
    (rlocks/release! :ui/selrect)
    (reset! position nil)))

(defn- on-start
  "Function execution when selrect action is started."
  []
  (let [stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        stream (rx/take-until stoper wb/mouse-viewport-s)
        pos @wb/mouse-viewport-a]
    (reset! position {:start pos :current pos})
    (rx/subscribe stream on-move nil on-complete)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.selrect
  "Mouse selection interaction and component."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.geom :as geom]
            [uxbox.main.data.workspace :as dw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.util.mixins :as mx :include-macros true]
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
    (let [{:keys [x1 y1 width height]} (geom/size (selrect->rect data))]
      [:rect.selection-rect
       {:x x1
        :y y1
        :width width
        :height height}])))

;; --- Interaction

(defn- selrect->rect
  [{:keys [start current] :as data}]
  (let [start-x (min (:x start) (:x current))
        start-y (min (:y start) (:y current))
        end-x (max (:x start) (:x current))
        end-y (max (:y start) (:y current))]
    {:x1 start-x
     :y1 start-y
     :x2 end-x
     :y2 end-y
     :type :rect}))

(defn- translate-to-canvas
  "Translate the given rect to the canvas coordinates system."
  [rect]
  (let [zoom @refs/selected-zoom
        startx (* c/canvas-start-x zoom)
        starty (* c/canvas-start-y zoom)]
    (assoc rect
           :x1 (/ (- (:x1 rect) startx) zoom)
           :y1 (/ (- (:y1 rect) starty) zoom)
           :x2 (/ (- (:x2 rect) startx) zoom)
           :y2 (/ (- (:y2 rect) starty) zoom))))

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
    (st/emit! (uds/deselect-all)
              (uds/select-shapes-by-selrect rect))
    (rlocks/release! :ui/selrect)
    (reset! position nil)))

(defn- on-start
  "Function execution when selrect action is started."
  []
  (let [stoper (->> streams/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        stream (rx/take-until stoper streams/mouse-viewport-s)
        pos @streams/mouse-viewport-a]
    (reset! position {:start pos :current pos})
    (rx/subscribe stream on-move nil on-complete)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace.selrect
  "Workspace selection rect."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.user-events :as uev]
            [uxbox.util.geom.point :as gpt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare stop-selrect)
(declare update-selrect)
(declare get-selection-stoper)
(declare selection->rect)
(declare translate-to-canvas)

;; --- Start Selrect

(deftype StartSelrect []
  ptk/UpdateEvent
  (update [_ state]
    (let [position @refs/viewport-mouse-position
          selection {::start position
                     ::stop position}]
      (assoc-in state [:workspace :selrect] (selection->rect selection))))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [stoper (get-selection-stoper stream)]
      ;; NOTE: the `viewport-mouse-position` can be derived from `stream`
      ;; but it used from `streams/` ns just for convenience
      (rx/concat
       (->> streams/viewport-mouse-position
            (rx/take-until stoper)
            (rx/map update-selrect))
       (rx/just (stop-selrect))))))

(defn start-selrect
  []
  (StartSelrect.))

;; --- Update Selrect

(deftype UpdateSelrect [position]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (assoc-in [:workspace :selrect ::stop] position)
        (update-in [:workspace :selrect] selection->rect))))

(defn update-selrect
  [position]
  {:pre [(gpt/point? position)]}
  (UpdateSelrect. position))

;; --- Clear Selrect

(deftype ClearSelrect []
  ptk/UpdateEvent
  (update [_ state]
    (update state :workspace dissoc :selrect)))

(defn clear-selrect
  []
  (ClearSelrect.))

;; --- Stop Selrect

(deftype StopSelrect []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [rect (-> (get-in state [:workspace :selrect])
                   (translate-to-canvas))]
      (rx/of
       (clear-selrect)
       (uds/deselect-all)
       (uds/select-shapes-by-selrect rect)))))

(defn stop-selrect
  []
  (StopSelrect.))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selection Rect Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- selection->rect
  [data]
  (let [start (::start data)
        stop (::stop data)
        start-x (min (:x start) (:x stop))
        start-y (min (:y start) (:y stop))
        end-x (max (:x start) (:x stop))
        end-y (max (:y start) (:y stop))]
    (assoc data
           :x1 start-x
           :y1 start-y
           :x2 end-x
           :y2 end-y
           :type :rect)))

(defn- get-selection-stoper
  [stream]
  (->> (rx/merge (rx/filter #(= % ::uev/interrupt) stream)
                 (rx/filter uev/mouse-up? stream))
       (rx/take 1)))

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


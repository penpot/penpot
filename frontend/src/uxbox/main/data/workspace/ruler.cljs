;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace.ruler
  "Workspace ruler related events. Mostly or all events
  are related to UI logic."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.user-events :as uev]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]))

;; --- Constants

(declare stop-ruler?)
(declare clear-ruler)
(declare update-ruler)

(def ^:private immanted-zones
  (let [transform #(vector (- % 7) (+ % 7) %)
        right (map transform (range 0 181 15))
        left  (map (comp transform -) (range 0 181 15))]
    (vec (concat right left))))

(defn- align-position
  [pos]
  (let [angle (gpt/angle pos)]
    (reduce (fn [pos [a1 a2 v]]
              (if (< a1 angle a2)
                (reduced (gpt/update-angle pos v))
                pos))
            pos
            immanted-zones)))

;; --- Start Ruler

(deftype StartRuler []
  ptk/UpdateEvent
  (update [_ state]
    (let [pos (get-in state [:workspace :pointer :viewport])]
      (assoc-in state [:workspace :ruler] [pos pos])))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [stoper (->> (rx/filter #(= ::uev/interrupt %) stream)
                      (rx/take 1))]
      (->> streams/mouse-position
           (rx/take-until stoper)
           (rx/map (juxt :viewport :ctrl))
           (rx/map (fn [[pt ctrl?]]
                     (update-ruler pt ctrl?)))))))

(defn start-ruler
  []
  (StartRuler.))

;; --- Update Ruler

(deftype UpdateRuler [point ctrl?]
  ptk/UpdateEvent
  (update [_ state]
    (let [[start end] (get-in state [:workspace :ruler])]
      (if-not ctrl?
        (assoc-in state [:workspace :ruler] [start point])
        (let [end (-> (gpt/subtract point start)
                      (align-position)
                      (gpt/add start))]
          (assoc-in state [:workspace :ruler] [start end]))))))

(defn update-ruler
  [point ctrl?]
  {:pre [(gpt/point? point)
         (boolean? ctrl?)]}
  (UpdateRuler. point ctrl?))

;; --- Clear Ruler

(deftype ClearRuler []
  ptk/UpdateEvent
  (update [_ state]
    (update state :workspace dissoc :ruler)))

(defn clear-ruler
  []
  (ClearRuler.))


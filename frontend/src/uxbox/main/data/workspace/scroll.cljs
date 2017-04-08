;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace.scroll
  "Workspace scroll related events. Mostly or all events
  are related to UI logic."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]))

;; --- Start Viewport Positioning

(declare stop-viewport-positioning?)

(defn run-viewport-positioning
  [stream]
  (let [stoper (->> (rx/filter stop-viewport-positioning? stream)
                    (rx/take 1))
        reference @refs/viewport-mouse-position
        dom (dom/get-element "workspace-canvas")]
    (->> streams/viewport-mouse-position
         (rx/take-until stoper)
         (rx/map (fn [point]
                   (let [{:keys [x y]} (gpt/subtract point reference)
                         cx (.-scrollLeft dom)
                         cy (.-scrollTop dom)]
                     (set! (.-scrollLeft dom) (- cx x))
                     (set! (.-scrollTop dom) (- cy y)))))
         (rx/ignore))))

(deftype StartViewportPositioning [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :viewport-positionig] #(if (nil? %) id %)))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [cid (get-in state [:workspace :viewport-positionig])]
      (if (= cid id)
        (run-viewport-positioning stream)
        (rx/empty)))))

(defn start-viewport-positioning
  []
  (StartViewportPositioning. (gensym "viewport-positioning")))

;; --- Stop Viewport positioning

(deftype StopViewportPositioning []
  ptk/UpdateEvent
  (update [_ state]
    (update state :workspace dissoc :viewport-positionig)))

(defn stop-viewport-positioning
  []
  (StopViewportPositioning.))

(defn stop-viewport-positioning?
  [v]
  (instance? StopViewportPositioning v))



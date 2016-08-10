;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.drawarea
  "Draw interaction and component."
  (:require [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.constants :as c]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes :as shapes]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.rlocks :as rlocks]
            [uxbox.main.geom :as geom]
            [uxbox.main.geom.point :as gpt]
            [uxbox.util.dom :as dom]))

;; --- State

(defonce drawing-shape (atom nil))
(defonce drawing-position (atom nil))

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

;; --- Draw Area (Component)

(declare watch-draw-actions)

(defn- draw-area-will-mount
  [own]
  (assoc own ::sub (watch-draw-actions)))

(defn- draw-area-will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(mx/defc draw-area
  {:will-mount draw-area-will-mount
   :will-unmount draw-area-will-unmount
   :mixins [mx/static mx/reactive]}
  [own]
  (let [shape (mx/react drawing-shape)
        position (mx/react drawing-position)]
    (when (and shape position)
      (-> (assoc shape :drawing? true)
          (geom/resize position)
          (shapes/render-component)))))

;; --- Drawing Initialization

(declare on-init)
(declare on-init-draw-icon)
(declare on-init-draw-generic)
(declare on-draw-start)
(declare on-draw)
(declare on-draw-complete)

(defn- watch-draw-actions
  []
  (let [stream (->> (rx/map first rlocks/stream)
                    (rx/filter #(= % :ui/draw)))]
    (rx/subscribe stream on-init)))

(defn- on-init
  "Function execution when draw shape operation is requested.
  This is a entry point for the draw interaction."
  []
  (when-let [shape (:drawing @wb/workspace-ref)]
    (case (:type shape)
      :icon (on-init-draw-icon shape)
      (on-init-draw-generic shape))))

(defn- on-init-draw-icon
  [shape]
  (let [{:keys [x y]} (gpt/divide @wb/mouse-canvas-a @wb/zoom-ref)
        props {:x1 x :y1 y :x2 (+ x 100) :y2 (+ y 100)}
        shape (geom/setup shape props)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil)
              (uds/select-first-shape))
    (rlocks/release! :ui/draw)))

(defn- on-init-draw-generic
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-ref
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))

        stoper (->> wb/mouse-events-s
                    (rx/filter #(= % :mouse/up))
                    (rx/pr-log "mouse-events-s")
                    (rx/take 1))

        firstpos (rx/take 1 mouse)
        stream (->> (rx/take-until stoper mouse)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]

    (rx/subscribe firstpos #(on-draw-start shape %))
    (rx/subscribe stream on-draw nil on-draw-complete)))

(defn- on-draw-start
  [shape {:keys [x y] :as pt}]
  (let [shape (geom/setup shape {:x1 x :y1 y :x2 x :y2 y})]
    (reset! drawing-shape shape)))

(defn- on-draw
  [[pt ctrl?]]
  (let [pt (gpt/divide pt @wb/zoom-ref)]
    (reset! drawing-position (assoc pt :lock ctrl?))))

(defn- on-draw-complete
  []
  (let [shape @drawing-shape
        shpos @drawing-position
                  shape (geom/resize shape shpos)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil)
              (uds/select-first-shape))
    (reset! drawing-position nil)
    (reset! drawing-shape nil)
    (rlocks/release! :ui/draw)))

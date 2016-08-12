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
    (when shape
      (if position
        (-> (assoc shape :drawing? true)
            (geom/resize position)
            (shapes/render-component))
        (-> (assoc shape :drawing? true)
            (shapes/render-component))))))

;; --- Drawing Initialization

(declare on-init)
(declare on-init-draw-icon)
(declare on-init-draw-path)
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
      :path (on-init-draw-path shape)
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

(defn- on-init-draw-path
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-ref
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))
        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/double-click))
                    (rx/take 1))
        firstpos (rx/take 1 mouse)
        stream (->> (rx/take-until stoper mouse)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))
        ptstream (->> (rx/take-until stoper wb/events-s)
                      (rx/map first)
                      (rx/filter #(= % :mouse/click))
                      (rx/with-latest-from vector mouse)
                      (rx/map second))
        counter (atom 0)]
    (letfn [(append-point [{:keys [type] :as shape} point]
              (let [point (gpt/point point)]
                (update shape :points conj point)))

            (update-point [{:keys [type] :as shape} point index]
              (let [point (gpt/point point)
                    points (:points shape)]
                (if (= (count points) index)
                  (append-point shape point)
                  (assoc-in shape [:points index] point))))

            (normalize-shape [{:keys [points] :as shape}]
              (let [minx (apply min (map :x points))
                    miny (apply min (map :y points))
                    maxx (apply max (map :x points))
                    maxy (apply max (map :y points))

                    dx (- 0 minx)
                    dy (- 0 miny)
                    points (mapv #(gpt/add % [dx dy]) points)
                    width (- maxx minx)
                    height (- maxy miny)]

                (assoc shape
                       :x1 minx
                       :y1 miny
                       :x2 maxx
                       :y2 maxy
                       :view-box [0 0 width height]
                       :points points)))

            (on-first-point [point]
              (let [shape (append-point shape point)]
                (swap! counter inc)
                (reset! drawing-shape shape)))

            (on-click [point]
              (let [shape (append-point @drawing-shape point)]
                (swap! counter inc)
                (reset! drawing-shape shape)))

            (on-draw [[point ctrl?]]
              (let [shape (update-point @drawing-shape point @counter)]
                (reset! drawing-shape shape)))

            (on-end []
              (let [shape (normalize-shape @drawing-shape)]
                (println "on-end" shape)
                (rs/emit! (uds/add-shape shape)
                          (udw/select-for-drawing nil)
                          (uds/select-first-shape))
                (reset! drawing-shape nil)
                (reset! drawing-position nil)
                (rlocks/release! :ui/draw)))]

      (rx/subscribe firstpos on-first-point)
      (rx/subscribe ptstream on-click)
      (rx/subscribe stream on-draw nil on-end))))

(defn- on-init-draw-generic
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-ref
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))

        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        firstpos (rx/take 1 mouse)
        stream (->> (rx/take-until stoper mouse)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]

    (rx/subscribe firstpos (fn [{:keys [x y] :as pt}]
                             (let [shape (geom/setup shape {:x1 x :y1 y
                                                            :x2 x :y2 y})]
                               (reset! drawing-shape shape))))
    (rx/subscribe stream on-draw nil on-draw-complete)))

;; (defn- on-draw-start
;;   [shape {:keys [x y] :as pt}]
;;   (let [shape (geom/setup shape {:x1 x :y1 y :x2 x :y2 y})]
;;     (reset! drawing-shape shape)))

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

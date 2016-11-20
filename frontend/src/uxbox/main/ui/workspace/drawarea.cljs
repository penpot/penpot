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
            [uxbox.util.rlocks :as rlocks]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.path :as path]
            [uxbox.util.dom :as dom]))

;; --- State

(defonce drawing-stoper (rx/bus))
(defonce drawing-shape (atom nil))
(defonce drawing-position (atom nil))

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

;; --- Draw Area (Component)

(declare watch-draw-actions)
(declare on-init-draw)

(defn- watch-draw-actions
  []
  (let [stream (->> (rx/map first rlocks/stream)
                    (rx/filter #(= % :ui/draw)))]
    (rx/subscribe stream on-init-draw)))

(defn- draw-area-will-mount
  [own]
  (assoc own ::sub (watch-draw-actions)))

(defn- draw-area-will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(declare generic-shape-draw-area)
(declare path-shape-draw-area)

(mx/defc draw-area
  {:will-mount draw-area-will-mount
   :will-unmount draw-area-will-unmount
   :mixins [mx/static mx/reactive]}
  []
  (let [shape (mx/react drawing-shape)
        position (mx/react drawing-position)]
    (when shape
      (if (= (:type shape) :path)
        (path-shape-draw-area shape)
        (generic-shape-draw-area shape position)))))

(mx/defc generic-shape-draw-area
  [shape position]
  (if position
    (-> (assoc shape :drawing? true)
        (geom/resize position)
        (shapes/render-component))
    (-> (assoc shape :drawing? true)
        (shapes/render-component))))

(mx/defc path-shape-draw-area
  [{:keys [points] :as shape}]
  (letfn [(on-click [event]
            (dom/stop-propagation event)
            (swap! drawing-shape drop-last-point)
            (rx/push! drawing-stoper true))
          (drop-last-point [shape]
            (let [points (:points shape)
                  points (vec (butlast points))]
              (assoc shape :points points :close? true)))]
    (let [{:keys [x y]} (first points)]
      [:g
       (-> (assoc shape :drawing? true)
           (shapes/render-component))
       (when-not (:free shape)
         [:circle {:cx x
                   :cy y
                   :r 5
                   :fill "red"
                   :on-click on-click}])])))

;; --- Drawing Initialization

(declare on-init-draw-icon)
(declare on-init-draw-path)
(declare on-init-draw-free-path)
(declare on-init-draw-generic)

(defn- on-init-draw
  "Function execution when draw shape operation is requested.
  This is a entry point for the draw interaction."
  []
  (when-let [shape (:drawing @wb/workspace-ref)]
    (case (:type shape)
      :icon (on-init-draw-icon shape)
      :image (on-init-draw-icon shape)
      :path (if (:free shape)
              (on-init-draw-free-path shape)
              (on-init-draw-path shape))
      (on-init-draw-generic shape))))

;; --- Icon Drawing

(defn- on-init-draw-icon
  [{:keys [metadata] :as shape}]
  (let [{:keys [x y]} (gpt/divide @wb/mouse-canvas-a @wb/zoom-ref)
        {:keys [width height]} metadata
        proportion (/ width height)
        props {:x1 x
               :y1 y
               :x2 (+ x 200)
               :y2 (+ y (/ 200 proportion))}
        shape (geom/setup shape props)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil)
              (uds/select-first-shape))
    (rlocks/release! :ui/draw)))

;; --- Path Drawing

(def ^:private immanted-zones
  (let [transform #(vector (- % 7) (+ % 7) %)]
    (concat
     (mapv transform (range 0 181 15))
     (mapv (comp transform -) (range 0 181 15)))))

(defn- align-position
  [angle pos]
  (reduce (fn [pos [a1 a2 v]]
            (if (< a1 angle a2)
              (reduced (gpt/update-angle pos v))
              pos))
          pos
          immanted-zones))

(defn- translate-to-canvas
  [point]
  (let [zoom @wb/zoom-ref
        ccords (gpt/multiply canvas-coords zoom)]
    (-> point
        (gpt/subtract ccords)
        (gpt/divide zoom))))

(defn- conditional-align
  [point]
  (if @wb/alignment-ref
    (uds/align-point point)
    (rx/of point)))

(defn- on-init-draw-path
  [shape]
  (letfn [(stoper-event? [[type opts]]
            (or (and (= type :key/down)
                     (= (:key opts) 13))
                (and (= type :mouse/double-click)
                     (true? (:shift? opts)))
                (= type :mouse/right-click)))

          (new-point-event? [[type opts]]
            (and (= type :mouse/click)
                 (false? (:shift? opts))))]

    (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                     (rx/mapcat conditional-align)
                     (rx/map translate-to-canvas))
          stoper (->> (rx/merge
                       (rx/take 1 drawing-stoper)
                       (rx/filter stoper-event? wb/events-s))
                      (rx/take 1))
          firstpos (rx/take 1 mouse)
          stream (->> (rx/take-until stoper mouse)
                      (rx/skip-while #(nil? @drawing-shape))
                      (rx/with-latest-from vector wb/mouse-ctrl-s))
          ptstream (->> (rx/take-until stoper wb/events-s)
                        (rx/filter new-point-event?)
                        (rx/with-latest-from vector mouse)
                        (rx/map second))
          counter (atom 0)]
      (letfn [(append-point [{:keys [type] :as shape} point]
                (let [point (gpt/point point)]
                  (update shape :points conj point)))

              (update-point [{:keys [type points] :as shape} point index]
                (let [point (gpt/point point)
                      points (:points shape)]
                  (assoc-in shape [:points index] point)))

              (on-first-point [point]
                (let [shape (append-point shape point)]
                  (swap! counter inc)
                  (reset! drawing-shape shape)))

              (on-click [point]
                (let [shape (append-point @drawing-shape point)]
                  (swap! counter inc)
                  (reset! drawing-shape shape)))

              (on-assisted-draw [point]
                (let [center (get-in @drawing-shape [:points (dec @counter)])
                      point (as-> point $
                              (gpt/subtract $ center)
                              (align-position (gpt/angle $) $)
                              (gpt/add $ center))]
                  (->> (update-point @drawing-shape point @counter)
                       (reset! drawing-shape))))

              (on-free-draw [point]
                (->> (update-point @drawing-shape point @counter)
                     (reset! drawing-shape)))

              (on-draw [[point ctrl?]]
                (if ctrl?
                  (on-assisted-draw point)
                  (on-free-draw point)))

              (on-end []
                (let [shape @drawing-shape]
                  (rs/emit! (uds/add-shape shape)
                            (udw/select-for-drawing nil)
                            (uds/select-first-shape))
                  (reset! drawing-shape nil)
                  (reset! drawing-position nil)
                  (rlocks/release! :ui/draw)))]

        (rx/subscribe firstpos on-first-point)
        (rx/subscribe ptstream on-click)
        (rx/subscribe stream on-draw nil on-end)))))

(defn- on-init-draw-free-path
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat conditional-align)
                   (rx/map translate-to-canvas))
        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        stream (rx/take-until stoper mouse)]
    (letfn [(simplify-shape [{:keys [points] :as shape}]
              (let [prevnum (count points)
                    points (path/simplify points 0.2)]
                (println "path simplification: previous=" prevnum
                         " current=" (count points))
                (assoc shape :points points)))

            (on-draw [point]
              (let [point (gpt/point point)
                    shape (-> (or @drawing-shape shape)
                              (update :points conj point))]
                (reset! drawing-shape shape)))

            (on-end []
              (let [shape (simplify-shape @drawing-shape)]
                (rs/emit! (uds/add-shape shape)
                          (udw/select-for-drawing nil)
                          (uds/select-first-shape))
                (reset! drawing-shape nil)
                (reset! drawing-position nil)
                (rlocks/release! :ui/draw)))]

      (rx/subscribe stream on-draw nil on-end))))

(defn- on-init-draw-generic
  [shape]
  (let [mouse (->> wb/mouse-viewport-s
                   (rx/mapcat conditional-align)
                   (rx/map translate-to-canvas))
        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        firstpos (rx/take 1 mouse)
        stream (->> (rx/take-until stoper mouse)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]

    (letfn [(on-start [{:keys [x y] :as pt}]
              (let [shape (geom/setup shape {:x1 x :y1 y :x2 x :y2 y})]
                (reset! drawing-shape shape)))

            (on-draw [[pt ctrl?]]
              (reset! drawing-position (assoc pt :lock ctrl?)))
            (on-end []
              (let [shape @drawing-shape
                    shpos @drawing-position
                    shape (geom/resize shape shpos)]
                (rs/emit! (uds/add-shape shape)
                          (udw/select-for-drawing nil)
                          (uds/select-first-shape))
                (reset! drawing-position nil)
                (reset! drawing-shape nil)
                (rlocks/release! :ui/draw)))]
      (rx/subscribe firstpos on-start)
      (rx/subscribe stream on-draw nil on-end))))

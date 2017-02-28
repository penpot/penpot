;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace.drawing
  "Workspace drawing data events and impl."
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [lentes.core :as l]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.refs :as refs]
            [uxbox.main.streams :as streams]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.geom :as geom]
            [uxbox.main.workers :as uwrk]
            [uxbox.main.user-events :as uev]
            [uxbox.main.lenses :as ul]
            [uxbox.util.geom.path :as pth]
            [uxbox.util.geom.point :as gpt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Select for Drawing

(deftype SelectForDrawing [shape]
  ptk/UpdateEvent
  (update [_ state]
    (let [current (l/focus ul/selected-drawing state)]
      (if (or (nil? shape)
              (= shape current))
        (update state :workspace dissoc :drawing :drawing-tool)
        (update state :workspace assoc
                :drawing shape
                :drawing-tool shape)))))

(defn select-for-drawing
  [shape]
  (SelectForDrawing. shape))


;; --- Clear Drawing State

(deftype ClearDrawingState []
  ptk/UpdateEvent
  (update [_ state]
    (update state :workspace dissoc :drawing-tool :drawing)))

(defn clear-drawing-state
  []
  (ClearDrawingState.))

;; -- Start Drawing

(declare on-init-draw)

(deftype StartDrawing [id object]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :drawing-lock] #(if (nil? %) id %)))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [lock (get-in state [:workspace :drawing-lock])]
      (if (= lock id)
        (->> stream
             (rx/filter #(= % ::uev/interrupt))
             (rx/take 1)
             (rx/map (fn [_] #(update % :workspace dissoc :drawing-lock))))
        (rx/empty))))

  ptk/EffectEvent
  (effect [_ state stream]
    (let [lock (get-in state [:workspace :drawing-lock])]
      (when (= lock id)
        (on-init-draw object stream)))))

(defn start-drawing
  [object]
  (let [id (gensym "drawing")]
    (StartDrawing. id object)))

;; --- Initialize Draw Area

(deftype InitializeDrawing [point]
  ptk/UpdateEvent
  (update [_ state]
    (let [shape (get-in state [:workspace :drawing])
          shape (geom/setup shape {:x1 (:x point)
                                   :y1 (:y point)
                                   :x2 (+ (:x point) 2)
                                   :y2 (+ (:y point) 2)})]
      (assoc-in state [:workspace :drawing] shape))))

(defn initialize-drawing
  [point]
  {:pre [(gpt/point? point)]}
  (InitializeDrawing. point))

;; --- Update Draw Area State

(deftype UpdateDrawing [position lock?]
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [id] :as shape} (-> (get-in state [:workspace :drawing])
                                     (geom/shape->rect-shape)
                                     (geom/size))
          result (geom/resize-shape :bottom-right shape position lock?)
          scale (geom/calculate-scale-ratio shape result)
          resize-mtx (geom/generate-resize-matrix :bottom-right shape scale)]
      (assoc-in state [:workspace :modifiers id] {:resize resize-mtx}))))

(defn update-drawing
  [position lock?]
  {:pre [(gpt/point? position) (boolean? lock?)]}
  (UpdateDrawing. position lock?))

;; --- Finish Drawin

(deftype FinishDrawing []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [{:keys [id] :as shape} (get-in state [:workspace :drawing])
          resize-mtx (get-in state [:workspace :modifiers id :resize])
          shape (cond-> shape
                  resize-mtx (geom/transform resize-mtx))]
      (if-not shape
        (rx/empty)
        (rx/of (clear-drawing-state)
               (uds/add-shape shape)
               (uds/select-first-shape)
               ::uev/interrupt)))))

(defn finish-drawing
  []
  (FinishDrawing.))

;; --- Finish Path Drawing

(deftype FinishPathDrawing []
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :drawing :segments] #(vec (butlast %)))))

(defn finish-path-drawing
  []
  (FinishPathDrawing.))

;; --- Insert Drawing Path Point

(deftype InsertDrawingPathPoint [point]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :drawing :segments] (fnil conj []) point)))

(defn insert-drawing-path-point
  [point]
  {:pre [(gpt/point? point)]}
  (InsertDrawingPathPoint. point))

;; --- Update Drawing Path Point

(deftype UpdateDrawingPathPoint [index point]
  ptk/UpdateEvent
  (update [_ state]
    (let [segments (count (get-in state [:workspace :drawing :segments]))
          exists? (< -1 index segments)]
      (cond-> state
        exists? (assoc-in [:workspace :drawing :segments index] point)))))

(defn update-drawing-path-point
  [index point]
  {:pre [(integer? index) (gpt/point? point)]}
  (UpdateDrawingPathPoint. index point))

;; --- Close Drawing Path

(deftype CloseDrawingPath []
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :drawing :close?] true))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of ::uev/interrupt)))

(defn close-drawing-path
  []
  (CloseDrawingPath.))

;; --- Simplify Drawing Path

(deftype SimplifyDrawingPath [tolerance]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :drawing :segments] pth/simplify tolerance)))

(defn simplify-drawing-path
  [tolerance]
  {:pre [(number? tolerance)]}
  (SimplifyDrawingPath. tolerance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Drawing Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

(defn- conditional-align
  [point]
  (if @refs/selected-alignment
    (uwrk/align-point point)
    (rx/of point)))

(defn- translate-to-canvas
  [point]
  (-> point
      (gpt/subtract (gpt/multiply canvas-coords @refs/selected-zoom))
      (gpt/divide @refs/selected-zoom)))

(declare on-init-draw-icon)
(declare on-init-draw-path)
(declare on-init-draw-free-path)
(declare on-init-draw-generic)

(defn- on-init-draw
  "Function execution when draw shape operation is requested.
  This is a entry point for the draw interaction."
  [shape stream]
  (let [stoper (->> stream
                    (rx/filter #(= % ::uev/interrupt))
                    (rx/take 1))]
    (case (:type shape)
      :icon (on-init-draw-icon shape)
      :image (on-init-draw-icon shape)
      :path (if (:free shape)
              (on-init-draw-free-path shape stoper)
              (on-init-draw-path shape stoper))
      (on-init-draw-generic shape stoper))))

(defn- on-init-draw-generic
  [shape stoper]
  (let [stoper (rx/merge stoper (->> streams/events
                                     (rx/filter uev/mouse-up?)
                                     (rx/take 1)))
        start? (volatile! true)
        mouse (->> streams/viewport-mouse-position
                   (rx/take-until stoper)
                   (rx/mapcat conditional-align)
                   (rx/map translate-to-canvas)
                   (rx/with-latest vector streams/mouse-position-ctrl))]

    (letfn [(on-position [[point ctrl?]]
              (if @start?
                (do
                  (st/emit! (initialize-drawing point))
                  (vreset! start? false))
                (st/emit! (update-drawing point ctrl?))))

            (on-finish []
              (if @start?
                (st/emit! ::uev/interrupt)
                (st/emit! (finish-drawing))))]
      (rx/subscribe mouse on-position nil on-finish))))

(defn- on-init-draw-icon
  [{:keys [metadata] :as shape}]
  (let [{:keys [x y]} (gpt/divide @refs/canvas-mouse-position
                                  @refs/selected-zoom)
        {:keys [width height]} metadata
        proportion (/ width height)
        props {:x1 x
               :y1 y
               :x2 (+ x 200)
               :y2 (+ y (/ 200 proportion))}
        shape (geom/setup shape props)]
    (st/emit! (uds/add-shape shape)
              (uds/select-first-shape)
              (select-for-drawing nil)
              ::uev/interrupt)))

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

(defn- get-path-stoper-stream
  ([stoper] (get-path-stoper-stream stoper false))
  ([stoper mouseup?]
   (letfn [(stoper-event? [{:keys [type shift] :as event}]
             (or (and (uev/mouse-event? event)
                      (or (and (= type :double-click) shift)
                          (= type :context-menu)
                          (and mouseup? (= type :up))))
                 (and (uev/keyboard-event? event)
                      (= type :down)
                      (= 13 (:key event)))))]
     (->> (rx/filter stoper-event? streams/events)
          (rx/merge stoper)
          (rx/take 1)
          (rx/share)))))

(defn- get-path-point-stream
  []
  (->> streams/events
       (rx/filter uev/mouse-click?)
       (rx/filter #(false? (:shift %)))))

(defn- on-init-draw-free-path
  [shape stoper]
  (let [stoper (get-path-stoper-stream stoper true)
        mouse (->> streams/viewport-mouse-position
                   (rx/mapcat conditional-align)
                   (rx/map translate-to-canvas))

        stream (rx/take-until stoper mouse)]
    (letfn [(on-draw [point]
              (st/emit! (insert-drawing-path-point point)))
            (on-end []
              (st/emit! (simplify-drawing-path 0.3)
                        (finish-drawing)))]
      (rx/subscribe stream on-draw nil on-end))))

(defn- on-init-draw-path
  [shape stoper]
  (let [last-point (volatile! @refs/canvas-mouse-position)
        stoper (get-path-stoper-stream stoper)
        mouse (->> (rx/sample 10 streams/viewport-mouse-position)
                   (rx/mapcat conditional-align)
                   (rx/map translate-to-canvas))
        points (->> (get-path-point-stream)
                    (rx/with-latest vector mouse)
                    (rx/map second)
                    (rx/take-until stoper))
        counter (rx/merge (rx/scan #(inc %) 1 points) (rx/of 1))
        stream (->> mouse
                    (rx/with-latest vector streams/mouse-position-ctrl)
                    (rx/with-latest vector counter)
                    (rx/map flatten)
                    (rx/take-until stoper))]

    (letfn [(on-point [point]
              (vreset! last-point point)
              (st/emit! (insert-drawing-path-point point)))

            (on-draw [[point ctrl? counter]]
              (if ctrl?
                (on-assisted-draw point counter)
                (on-generic-draw point counter)))

            (on-generic-draw [point counter]
              (st/emit! (update-drawing-path-point counter point)))

            (on-assisted-draw [point counter]
              (let [point (as-> point $
                            (gpt/subtract $ @last-point)
                            (align-position (gpt/angle $) $)
                            (gpt/add $ @last-point))]
                (st/emit! (update-drawing-path-point counter point))))

              (on-finish []
                (st/emit! (finish-path-drawing)
                          (finish-drawing)))]

      ;; Initialize path drawing
      (st/emit! (insert-drawing-path-point @last-point)
                (insert-drawing-path-point @last-point))

      (rx/subscribe points on-point)
      (rx/subscribe stream on-draw nil on-finish))))



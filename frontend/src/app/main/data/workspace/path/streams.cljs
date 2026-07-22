;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.streams
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.main.data.workspace.edition :as-alias dwe]
   [app.main.data.workspace.path.state :as pst]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(defonce drag-threshold 5)

(def ^:private half-pixel-snap-zoom
  "Zoom threshold for half-pixel snapping."
  3)

(defn dragging? [start zoom]
  (fn [current]
    (>= (gpt/distance start current) (/ drag-threshold zoom))))

(defn finish-edition?
  "True for the path edition stop event."
  [event]
  (= (ptk/type event) ::dwe/clear-edition-mode))

(defn to-pixel-snap [position]
  (let [layout      (get @st/state :workspace-layout)
        snap-pixel? (contains? layout :snap-pixel-grid)
        zoom        (get-in @st/state [:workspace-local :zoom] 1)]

    (cond
      (or (not snap-pixel?) (not (gpt/point? position)))
      position

      :else
      (gpt/round-step position (if (> zoom half-pixel-snap-zoom) 0.5 1)))))

(defn drag-stream
  ([to-stream]
   (drag-stream to-stream (rx/empty)))

  ([to-stream not-drag-stream]
   (let [zoom  (get-in @st/state [:workspace-local :zoom] 1)

         start (-> @ms/mouse-position to-pixel-snap)

         stopper (rx/merge
                  (mse/drag-stopper st/stream)
                  (->> st/stream
                       (rx/filter finish-edition?)))

         position-stream
         (->> ms/mouse-position
              (rx/map to-pixel-snap)
              (rx/filter (dragging? start zoom))
              (rx/take 1)
              (rx/take-until stopper))]

     (rx/merge
      (->> position-stream
           (rx/if-empty ::empty)
           (rx/merge-map (fn [value]
                           (if (= value ::empty)
                             not-drag-stream
                             (rx/empty)))))

      (->> position-stream
           (rx/merge-map (fn [] to-stream)))))))

(defn snap-toggled-stream
  []
  (let [get-snap (fn [state]
                   (let [id (pst/get-path-id state)]
                     (dm/get-in state [:workspace-local :edit-path id :snap-toggled])))]
    (-> (l/derived get-snap st/state)
        (rx/from-atom {:emit-current-value? true}))))

(def ^:private node-merge-snap-distance
  "Maximum screen distance for node merge snapping."
  10)

(def ^:private neighboring-cell-offsets
  [[-1 -1] [-1 0] [-1 1]
   [0 -1]  [0 0]  [0 1]
   [1 -1]  [1 0]  [1 1]])

(defn- point-cell
  [point cell-size]
  [(js/Math.floor (/ (:x point) cell-size))
   (js/Math.floor (/ (:y point) cell-size))])

(defn make-node-merge-snap
  "Builds a stationary-node index and returns its merge snap function."
  [start-point selected-points points max-distance]
  (let [selected-points (set selected-points)
        point-index     (reduce
                         (fn [index point]
                           (if (contains? selected-points point)
                             index
                             (update index (point-cell point max-distance) (fnil conj []) point)))
                         {}
                         points)
        closest-target  (fn [closest moved-point]
                          (let [[cell-x cell-y] (point-cell moved-point max-distance)]
                            (reduce
                             (fn [closest [offset-x offset-y]]
                               (reduce
                                (fn [closest target]
                                  (let [distance (gpt/distance moved-point target)]
                                    (if (and (<= distance max-distance)
                                             (or (nil? closest)
                                                 (< distance (first closest))))
                                      [distance (gpt/subtract target moved-point)]
                                      closest)))
                                closest
                                (get point-index [(+ cell-x offset-x) (+ cell-y offset-y)] [])))
                             closest
                             neighboring-cell-offsets)))]
    (fn [position]
      (let [delta   (gpt/subtract position start-point)
            closest (reduce
                     (fn [closest selected-point]
                       (closest-target closest (gpt/add selected-point delta)))
                     nil
                     selected-points)]
        (when (some? closest)
          (gpt/add position (second closest)))))))

(defn move-points-stream
  [start-point selected-points points]

  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        snap-pixel? (contains? (get @st/state :workspace-layout) :snap-pixel-grid)
        ranges (snap/create-ranges points selected-points)
        d-pos (/ snap/snap-path-accuracy zoom)

        ;; Build the merge index once per pixel-snapped gesture.
        merge-distance     (/ node-merge-snap-distance zoom)
        node-merge-snap    (when snap-pixel?
                             (make-node-merge-snap
                              start-point selected-points points merge-distance))

        check-path-snap
        (fn [[position snap-toggled]]
          (if snap-toggled
            (let [delta (gpt/subtract position start-point)
                  moved-points (->> selected-points (mapv #(gpt/add % delta)))
                  snap (snap/get-snap-delta moved-points ranges d-pos)]
              (gpt/add position snap))
            position))

        ;; Node merge snapping takes priority over the pixel grid.
        snap-position
        (fn [[position snap-toggled]]
          (if (gpt/point? position)
            (or (when node-merge-snap
                  (node-merge-snap position))
                (check-path-snap [(to-pixel-snap position) snap-toggled]))
            position))]
    (->> ms/mouse-position
         (rx/with-latest-from (snap-toggled-stream))
         (rx/map snap-position)
         ;; Apply keyboard modifiers without waiting for pointer movement.
         (rx/combine-latest-with ms/keyboard-shift ms/keyboard-alt)
         (rx/map (fn [[position shift? alt?]]
                   (assoc position :shift? shift? :alt? alt?))))))

(defn get-angle [node handler opposite]
  (when (and (some? node) (some? handler) (some? opposite))
    (let [v1 (gpt/to-vec node opposite)
          v2 (gpt/to-vec node handler)
          rot-angle (gpt/angle-with-other v1 v2)
          rot-sign (gpt/angle-sign v1 v2)]
      [rot-angle rot-sign])))

(defn move-handler-stream
  [start-point node handler opposite points]
  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ranges (snap/create-ranges points)
        d-pos (/ snap/snap-path-accuracy zoom)

        [initial-angle] (get-angle node handler opposite)

        check-path-snap
        (fn [[position snap-toggled]]
          (if snap-toggled
            (let [delta (gpt/subtract position start-point)
                  handler (gpt/add handler delta)

                  [rot-angle rot-sign] (get-angle node handler opposite)

                  snap-opposite-angle?
                  (and (some? rot-angle)
                       (or (:alt? position) (> (- 180 initial-angle) 0.1))
                       (<= (- 180 rot-angle) 5))]

              (cond
                snap-opposite-angle?
                (let [rot-handler (gpt/rotate handler node (- 180 (* rot-sign rot-angle)))
                      snap (gpt/to-vec handler rot-handler)]
                  (merge position (gpt/add position snap)))

                :else
                (let [snap (snap/get-snap-delta [handler] ranges d-pos)]
                  (merge position (gpt/add position snap)))))
            position))]

    ;; Keep handler movement off the pixel grid.
    (->> ms/mouse-position
         (rx/filter gpt/point?)
         ;; Apply keyboard modifiers without waiting for pointer movement.
         (rx/combine-latest-with ms/keyboard-shift ms/keyboard-alt ms/keyboard-mod)
         (rx/map (fn [[position shift? alt? mod?]]
                   (assoc position :shift? shift? :alt? alt? :mod? mod?)))
         (rx/with-latest-from (snap-toggled-stream))
         (rx/map check-path-snap))))

(defn position-stream
  [state]
  (let [zoom (get-in state [:workspace-local :zoom] 1)
        d-pos (/ snap/snap-path-accuracy zoom)
        get-content #(pst/get-path % :content)

        content-stream
        (-> (l/derived get-content st/state)
            (rx/from-atom {:emit-current-value? true}))

        ranges-stream
        (->> content-stream
             (rx/filter some?)
             (rx/map path/get-points)
             (rx/map snap/create-ranges))]

    (->> ms/mouse-position
         ;; The subject can hold nil until the pointer enters the viewport
         (rx/filter gpt/point?)
         (rx/map to-pixel-snap)
         (rx/with-latest-from ranges-stream (snap-toggled-stream))
         (rx/map (fn [[position ranges snap-toggled]]
                   (if snap-toggled
                     (let [snap (snap/get-snap-delta [position] ranges d-pos)]
                       (gpt/add position snap))
                     position)))
         ;; Apply Shift without waiting for pointer movement.
         (rx/combine-latest-with ms/keyboard-shift ms/keyboard-alt)
         (rx/map (fn [[position shift? alt?]]
                   (assoc position :shift? shift? :alt? alt?))))))

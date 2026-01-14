;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.streams
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.path.segment :as path.segm]
   [app.main.data.workspace.path.state :as pst]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.mouse :as mse]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(defonce drag-threshold 5)

(defn dragging? [start zoom]
  (fn [current]
    (>= (gpt/distance start current) (/ drag-threshold zoom))))

(defn finish-edition? [event]
  (= (ptk/type event) :app.main.data.workspace.common/clear-edition-mode))

(defn to-pixel-snap [position]
  (let [layout      (get @st/state :workspace-layout)
        snap-pixel? (contains? layout :snap-pixel-grid)]

    (cond
      (or (not snap-pixel?) (not (gpt/point? position)))
      position


      :else
      (gpt/round position))))

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

(defn move-points-stream
  [start-point selected-points points]

  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ranges (snap/create-ranges points selected-points)
        d-pos (/ snap/snap-path-accuracy zoom)

        check-path-snap
        (fn [[position snap-toggled]]
          (if snap-toggled
            (let [delta (gpt/subtract position start-point)
                  moved-points (->> selected-points (mapv #(gpt/add % delta)))
                  snap (snap/get-snap-delta moved-points ranges d-pos)]
              (gpt/add position snap))
            position))]
    (->> ms/mouse-position
         (rx/map to-pixel-snap)
         (rx/with-latest-from (snap-toggled-stream))
         (rx/map check-path-snap)
         (rx/with-latest-from
           (fn [position shift? alt?]
             (assoc position :shift? shift? :alt? alt?))
           ms/mouse-position-shift
           ms/mouse-position-alt))))

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

    (->> ms/mouse-position
         (rx/map to-pixel-snap)
         (rx/with-latest-from
           (fn [position shift? alt?]
             (assoc position :shift? shift? :alt? alt?))
           ms/mouse-position-shift
           ms/mouse-position-alt)
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
             (rx/map path.segm/get-points)
             (rx/map snap/create-ranges))]

    (->> ms/mouse-position
         (rx/map to-pixel-snap)
         (rx/with-latest-from ranges-stream (snap-toggled-stream))
         (rx/map (fn [[position ranges snap-toggled]]
                   (if snap-toggled
                     (let [snap (snap/get-snap-delta [position] ranges d-pos)]
                       (gpt/add position snap))
                     position)))
         (rx/with-latest-from
           (fn [position shift? alt?]
             (assoc position :shift? shift? :alt? alt?))
           ms/mouse-position-shift
           ms/mouse-position-alt))))

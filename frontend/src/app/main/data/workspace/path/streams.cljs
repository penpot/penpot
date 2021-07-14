;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.streams
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.main.data.workspace.path.state :as state]
   [app.main.snap :as snap]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.path.geom :as upg]
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]))

(defonce drag-threshold 5)

(defn dragging? [start zoom]
  (fn [current]
    (>= (gpt/distance start current) (/ drag-threshold zoom))))

(defn finish-edition? [event]
  (= (ptk/type event) :app.main.data.workspace.common/clear-edition-mode))

(defn drag-stream
  ([to-stream]
   (drag-stream to-stream (rx/empty)))

  ([to-stream not-drag-stream]
   (let [start @ms/mouse-position
         zoom  (get-in @st/state [:workspace-local :zoom] 1)
         mouse-up (->> st/stream (rx/filter #(or (finish-edition? %)
                                                 (ms/mouse-up? %))))

         position-stream
         (->> ms/mouse-position
              (rx/take-until mouse-up)
              (rx/filter (dragging? start zoom))
              (rx/take 1))]

     (rx/merge
      (->> position-stream
           (rx/if-empty ::empty)
           (rx/merge-map (fn [value]
                           (if (= value ::empty)
                             not-drag-stream
                             (rx/empty)))))

      (->> position-stream
           (rx/merge-map (fn [] to-stream)))))))

(defn to-dec [num]
  (let [k 50]
    (* (mth/floor (/ num k)) k)))

(defn move-points-stream
  [snap-toggled start-point selected-points points]

  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ranges (snap/create-ranges points selected-points)
        d-pos (/ snap/snap-path-accuracy zoom)

        check-path-snap
        (fn [position]
          (if snap-toggled
            (let [delta (gpt/subtract position start-point)
                  moved-points (->> selected-points (mapv #(gpt/add % delta)))
                  snap (snap/get-snap-delta moved-points ranges d-pos)]
              (gpt/add position snap))
            position))]
    (->> ms/mouse-position
         (rx/map check-path-snap))))

(defn get-angle [node handler opposite]
  (when (and (some? node) (some? handler) (some? opposite))
    (let [v1 (gpt/to-vec node opposite)
          v2 (gpt/to-vec node handler)
          rot-angle (gpt/angle-with-other v1 v2)
          rot-sign (gpt/angle-sign v1 v2)]
      [rot-angle rot-sign])))

(defn move-handler-stream
  [snap-toggled start-point node handler opposite points]

  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ranges (snap/create-ranges points)
        d-pos (/ snap/snap-path-accuracy zoom)

        [initial-angle] (get-angle node handler opposite)

        check-path-snap
        (fn [position]
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
         (rx/with-latest merge (->> ms/mouse-position-shift (rx/map #(hash-map :shift? %))))
         (rx/with-latest merge (->> ms/mouse-position-alt (rx/map #(hash-map :alt? %))))
         (rx/map check-path-snap))))

(defn position-stream
  [snap-toggled _points]
  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        d-pos (/ snap/snap-path-accuracy zoom)
        get-content #(state/get-path % :content)

        content-stream
        (-> (l/derived get-content st/state)
            (rx/from-atom {:emit-current-value? true}))

        ranges-stream
        (->> content-stream
             (rx/map upg/content->points)
             (rx/map snap/create-ranges))]

    (->> ms/mouse-position
         (rx/with-latest vector ranges-stream)
         (rx/map (fn [[position ranges]]
                   (if snap-toggled
                     (let [snap (snap/get-snap-delta [position] ranges d-pos)]
                       (gpt/add position snap))
                     position)))
         (rx/with-latest merge (->> ms/mouse-position-shift (rx/map #(hash-map :shift? %))))
         (rx/with-latest merge (->> ms/mouse-position-alt (rx/map #(hash-map :alt? %)))))))

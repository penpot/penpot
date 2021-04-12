;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.streams
  (:require
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.state :as state]
   [app.common.geom.point :as gpt]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.math :as mth]
   [app.main.snap :as snap]
   [okulary.core :as l]
   [app.util.geom.path :as ugp]))

(defonce drag-threshold 5)

(defn dragging? [start zoom]
  (fn [current]
    (>= (gpt/distance start current) (/ drag-threshold zoom))))

(defn drag-stream
  ([to-stream]
   (drag-stream to-stream (rx/empty)))

  ([to-stream not-drag-stream]
   (let [start @ms/mouse-position
         zoom  (get-in @st/state [:workspace-local :zoom] 1)
         mouse-up (->> st/stream (rx/filter #(ms/mouse-up? %)))

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
  [start-point selected-points points]

  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ranges (snap/create-ranges selected-points points)
        d-pos (/ snap/snap-accuracy zoom)]
    (->> ms/mouse-position
         (rx/map (fn [position]
                   (let [delta (gpt/subtract position start-point)
                         moved-points (->> selected-points (mapv #(gpt/add % delta)))]
                     (gpt/add
                      position
                      (snap/get-snap-delta moved-points ranges d-pos)))))))
  )

(defn move-handler-stream
  [start-point handler points]

  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ranges (snap/create-ranges points)
        d-pos (/ snap/snap-accuracy zoom)]
    (->> ms/mouse-position
         (rx/map (fn [position]
                   (let [delta (gpt/subtract position start-point)
                         handler-position (gpt/add handler delta)]
                     (gpt/add
                      position
                      (snap/get-snap-delta [handler-position] ranges d-pos))))))))

(defn position-stream
  [points]
  (let [zoom (get-in @st/state [:workspace-local :zoom] 1)
        ;; ranges (snap/create-ranges points)
        d-pos (/ snap/snap-accuracy zoom)
        get-content (fn [state] (get-in state (state/get-path state :content)))

        content-stream
        (-> (l/derived get-content st/state)
            (rx/from-atom {:emit-current-value? true}))

        ranges-stream
        (->> content-stream
             (rx/map ugp/content->points)
             (rx/map snap/create-ranges))]

    (->> ms/mouse-position
         (rx/with-latest vector ranges-stream)
         (rx/map (fn [[position ranges]]
                   (let [snap (snap/get-snap-delta [position] ranges d-pos)]
                     #_(prn ">>>" snap)
                     (gpt/add position snap))
                   ))

         (rx/with-latest merge (->> ms/mouse-position-shift (rx/map #(hash-map :shift? %))))
         (rx/with-latest merge (->> ms/mouse-position-alt (rx/map #(hash-map :alt? %)))))))

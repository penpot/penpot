;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.streams
  (:require
   [app.main.data.workspace.path.helpers :as helpers]
   [app.common.geom.point :as gpt]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [beicon.core :as rx]
   [potok.core :as ptk]))

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

(defn position-stream []
  (->> ms/mouse-position
       (rx/with-latest merge (->> ms/mouse-position-shift (rx/map #(hash-map :shift? %))))
       (rx/with-latest merge (->> ms/mouse-position-alt (rx/map #(hash-map :alt? %))))))

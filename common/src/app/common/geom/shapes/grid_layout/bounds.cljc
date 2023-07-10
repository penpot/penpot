;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.bounds
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.grid-layout.layout-data :as ld]
   [app.common.geom.shapes.points :as gpo]))

(defn layout-content-points
  [bounds parent children]
  (let [parent-id (:id parent)
        parent-bounds @(get bounds parent-id)

        hv #(gpo/start-hv parent-bounds %)
        vv #(gpo/start-vv parent-bounds %)

        children (->> children
                      (map #(vector @(get bounds (:id %)) %)))

        {:keys [row-tracks column-tracks]} (ld/calc-layout-data parent children parent-bounds)]
    (d/concat-vec
     (->> row-tracks
          (mapcat #(vector (:start-p %)
                           (gpt/add (:start-p %) (vv (:size %))))))
     (->> column-tracks
          (mapcat #(vector (:start-p %)
                           (gpt/add (:start-p %) (hv (:size %)))))))))

(defn layout-content-bounds
  [bounds {:keys [layout-padding] :as parent} children]

  (let [parent-id (:id parent)
        parent-bounds @(get bounds parent-id)
        
        {pad-top :p1 pad-right :p2 pad-bottom :p3 pad-left :p4} layout-padding
        pad-top    (or pad-top 0)
        pad-right  (or pad-right 0)
        pad-bottom (or pad-bottom 0)
        pad-left   (or pad-left 0)
        
        layout-points (layout-content-points bounds parent children)]

    (if (d/not-empty? layout-points)
      (-> layout-points
          (gpo/merge-parent-coords-bounds parent-bounds)
          (gpo/pad-points (- pad-top) (- pad-right) (- pad-bottom) (- pad-left)))
      ;; Cannot create some bounds from the children so we return the parent's
      parent-bounds)))

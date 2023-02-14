;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.layout-data
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]))

(defn calc-layout-data
  [_parent _children transformed-parent-bounds]
  (let [num-columns 3
        num-rows 2

        height (gpo/height-points transformed-parent-bounds)
        width  (gpo/width-points transformed-parent-bounds)

        row-lines
        (->> (range 0 num-rows)
             (reduce (fn [[result start-dist] _]
                       (let [height (/ height num-rows)]
                         [(conj result {:distance start-dist
                                        :height height})
                          (+ start-dist height)]))

                     [[] 0])
             first)

        column-lines
        (->> (range 0 num-columns)
             (reduce (fn [[result start-dist] _]
                       (let [width (/ width num-columns)]
                         [(conj result {:distance start-dist
                                        :width width})
                          (+ start-dist width)]))
                     [[] 0])
             first)]
    {:columns 3
     :rows 3
     :row-lines row-lines
     :column-lines column-lines}))

(defn get-child-coordinates
  [{:keys [columns]} _child child-idx]
  [;; Row
   (quot child-idx columns)
   ;; column
   (mod child-idx columns)])

(defn get-cell-data
  [grid-data transformed-parent-bounds row col]

  (let [origin (gpo/origin transformed-parent-bounds)
        hv #(gpo/start-hv transformed-parent-bounds %)
        vv #(gpo/start-vv transformed-parent-bounds %)

        {col-dist :distance width :width} (dm/get-in grid-data [:column-lines col])
        {row-dist :distance height :height} (dm/get-in grid-data [:row-lines row])

        start-p
        (-> origin
            (gpt/add (hv col-dist))
            (gpt/add (vv row-dist)))]
    {:start-p  start-p
     :width width
     :height height
     :row row
     :col col}))

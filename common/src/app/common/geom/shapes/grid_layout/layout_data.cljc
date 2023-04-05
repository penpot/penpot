;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.layout-data
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]))

#_(defn set-sample-data
  [parent children]

  (let [parent (assoc parent
                      :layout-grid-columns
                      [{:type :percent :value 25}
                       {:type :percent :value 25}
                       {:type :fixed :value 100}
                       ;;{:type :auto}
                       ;;{:type :flex :value 1}
                       ]

                      :layout-grid-rows
                      [{:type :percent :value 50}
                       {:type :percent :value 50}
                       ;;{:type :fixed :value 100}
                       ;;{:type :auto}
                       ;;{:type :flex :value 1}
                       ])

        num-rows (count (:layout-grid-rows parent))
        num-columns (count (:layout-grid-columns parent))

        layout-grid-cells
        (into
         {}
         (for [[row-idx _row] (d/enumerate (:layout-grid-rows parent))
               [col-idx _col] (d/enumerate (:layout-grid-columns parent))]
           (let [[_bounds shape] (nth children (+ (* row-idx num-columns) col-idx) nil)
                 cell-data {:id (uuid/next)
                            :row (inc row-idx)
                            :column (inc col-idx)
                            :row-span 1
                            :col-span 1
                            :shapes (when shape [(:id shape)])}]
             [(:id cell-data) cell-data])))

        parent (assoc parent :layout-grid-cells layout-grid-cells)]

    [parent children]))

(defn calculate-initial-track-values
  [{:keys [type value]} total-value]

  (case type
    :percent
    (let [value (/ (* total-value value) 100) ]
      value)

    :fixed
    value

    :auto
    0
    ))

(defn calc-layout-data
  [parent _children transformed-parent-bounds]

  (let [height (gpo/height-points transformed-parent-bounds)
        width  (gpo/width-points transformed-parent-bounds)

        ;; Initialize tracks
        column-tracks
        (->> (:layout-grid-columns parent)
             (map (fn [track]
                    (let [initial (calculate-initial-track-values track width)]
                      (assoc track :value initial)))))

        row-tracks
        (->> (:layout-grid-rows parent)
             (map (fn [track]
                    (let [initial (calculate-initial-track-values track height)]
                      (assoc track :value initial)))))

        ;; Go through cells to adjust auto sizes


        ;; Once auto sizes have been calculated we get calculate the `fr` with the remainining size and adjust the size


        ;; Adjust final distances

        acc-track-distance
        (fn [[result next-distance] data]
          (let [result (conj result (assoc data :distance next-distance))
                next-distance (+ next-distance (:value data))]
            [result next-distance]))

        column-tracks
        (->> column-tracks
             (reduce acc-track-distance [[] 0])
             first)

        row-tracks
        (->> row-tracks
             (reduce acc-track-distance [[] 0])
             first)

        shape-cells
        (into {}
              (mapcat (fn [[_ cell]]
                        (->> (:shapes cell)
                             (map #(vector % cell)))))
              (:layout-grid-cells parent))
        ]

    {:row-tracks row-tracks
     :column-tracks column-tracks
     :shape-cells shape-cells}))

(defn get-cell-data
  [{:keys [row-tracks column-tracks shape-cells]} transformed-parent-bounds [_ child]]

  (let [origin (gpo/origin transformed-parent-bounds)
        hv     #(gpo/start-hv transformed-parent-bounds %)
        vv     #(gpo/start-vv transformed-parent-bounds %)

        grid-cell (get shape-cells (:id child))]

    (when (some? grid-cell)
      (let [column (nth column-tracks (dec (:column grid-cell)) nil)
            row (nth row-tracks (dec (:row grid-cell)) nil)

            start-p (-> origin
                        (gpt/add (hv (:distance column)))
                        (gpt/add (vv (:distance row))))]

        (assoc grid-cell :start-p  start-p)))))

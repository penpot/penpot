;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;;   Each track has specified minimum and maximum sizing functions (which may be the same)
;;   - Fixed
;;   - Percent
;;   - Auto
;;   - Flex
;;
;;
;;
;;   Min functions:
;;   - Fixed: value
;;   - Percent: value to pixels
;;   - Auto: auto
;;   - Flex: auto
;;
;;   Max functions:
;;   - Fixed: value
;;   - Percent: value to pixels
;;   - Auto: max-content
;;   - Flex: flex

;; Algorithm
;; - Initialize tracks:
;;   * base = size or 0
;;   * max = size or INF
;;
;; - Resolve intrinsic sizing
;;   1. Shim baseline-aligned items so their intrinsic size contributions reflect their baseline alignment
;;
;;   2. Size tracks to fit non-spanning items
;;   base-size = max (children min contribution) floored 0
;;
;;   3. Increase sizes to accommodate spanning items crossing content-sized tracks
;;
;;   4. Increase sizes to accommodate spanning items crossing flexible tracks:
;;
;;   5. If any track still has an infinite growth limit set its growth limit to its base size.

;;   - Distribute extra space accross spaned tracks
;; - Maximize tracks
;;
;; - Expand flexible tracks
;;   - Find `fr` size
;;
;; - Stretch auto tracks



(ns app.common.geom.shapes.grid-layout.layout-data
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.points :as gpo]
   [app.common.types.shape.layout :as ctl]))

(defn layout-bounds
  [parent shape-bounds]
  (let [[pad-top pad-right pad-bottom pad-left] (ctl/paddings parent)]
    (gpo/pad-points shape-bounds pad-top pad-right pad-bottom pad-left)))

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

(defn calculate-initial-track-size
  [total-value {:keys [type value] :as track}]

  (let [[size max-size]
        (case type
          :percent
          (let [value (/ (* total-value value) 100) ]
            [value value])

          :fixed
          [value value]

          ;; flex, auto
          [0.01 ##Inf])]
    (assoc track :size size :max-size max-size)))


(defn set-auto-base-size
  [track-list children shape-cells type]

  (let [[prop prop-span size-fn]
        (if (= type :column)
           [:column :column-span gpo/width-points]
           [:row :row-span gpo/height-points])]

    (reduce (fn [tracks [child-bounds child-shape]]
              (let [cell (get shape-cells (:id child-shape))
                    idx (dec (get cell prop))
                    track (nth tracks idx)]
                (cond-> tracks
                  (and (= (get cell prop-span) 1) (= :auto (:type track)))
                  (update-in [idx :size] max (size-fn child-bounds)))))
            track-list
            children)))

(defn tracks-total-size
  [track-list]
  (let [calc-tracks-total-size
        (fn [acc {:keys [size]}]
          (+ acc size))]
    (->> track-list (reduce calc-tracks-total-size 0))))

(defn tracks-total-frs
  [track-list]
  (let [calc-tracks-total-frs
        (fn [acc {:keys [type value]}]
          (let [value (max 1 value)]
            (cond-> acc
              (= type :flex)
              (+ value))))]
    (->> track-list (reduce calc-tracks-total-frs 0))))

(defn tracks-total-autos
  [track-list]
  (let [calc-tracks-total-autos
        (fn [acc {:keys [type]}]
          (cond-> acc (= type :auto) (inc)))]
    (->> track-list (reduce calc-tracks-total-autos 0))))

(defn set-fr-value
  [track-list fr-value]
  (->> track-list
       (mapv (fn [{:keys [type value max-size] :as track}]
               (cond-> track
                 (= :flex type)
                 (assoc :size (min (* value fr-value) max-size)))))))

(defn add-auto-size
  [track-list add-size]
  (->> track-list
       (mapv (fn [{:keys [type size max-size] :as track}]
               (cond-> track
                 (= :auto type)
                 (assoc :size (min (+ size add-size) max-size)))))))

(defn calc-layout-data
  [parent children transformed-parent-bounds]

  (let [hv     #(gpo/start-hv transformed-parent-bounds %)
        vv     #(gpo/start-vv transformed-parent-bounds %)

        layout-bounds (layout-bounds parent transformed-parent-bounds)

        bound-height (gpo/height-points layout-bounds)
        bound-width  (gpo/width-points layout-bounds)
        bound-corner (gpo/origin layout-bounds)

        grid-columns (:layout-grid-columns parent)
        grid-rows    (:layout-grid-rows parent)

        [row-gap column-gap] (ctl/gaps parent)

        ;; Map shape->cell
        shape-cells
        (into {}
              (mapcat (fn [[_ cell]]
                        (->> (:shapes cell) (map #(vector % cell)))))
              (:layout-grid-cells parent))


        ;; Initialize tracks
        column-tracks
        (->> grid-columns
             (mapv (partial calculate-initial-track-size bound-width)))

        row-tracks
        (->> grid-rows
             (mapv (partial calculate-initial-track-size bound-height)))

        ;; Go through cells to adjust auto sizes for span=1. Base is the max of its children
        column-tracks (set-auto-base-size column-tracks children shape-cells :column)
        row-tracks    (set-auto-base-size row-tracks children shape-cells :row)


        ;; Adjust multi-spaned cells with no flex columns
        ;; TODO


        ;; Calculate the `fr` unit and adjust the size
        column-total-size (tracks-total-size column-tracks)
        row-total-size    (tracks-total-size row-tracks)

        column-total-gap  (* column-gap (dec (count column-tracks)))
        row-total-gap     (* row-gap (dec (count row-tracks)))

        column-frs        (tracks-total-frs column-tracks)
        row-frs           (tracks-total-frs row-tracks)

        ;; Once auto sizes have been calculated we get calculate the `fr` unit with the remainining size and adjust the size
        free-column-space (- bound-width (+ column-total-size column-total-gap))
        free-row-space    (- bound-height (+ row-total-size row-total-gap))
        column-fr         (/ free-column-space column-frs)
        row-fr            (/ free-row-space row-frs)

        column-tracks (set-fr-value column-tracks column-fr)
        row-tracks (set-fr-value row-tracks row-fr)

        ;; Distribute free space between `auto` tracks
        column-total-size (tracks-total-size column-tracks)
        row-total-size    (tracks-total-size row-tracks)

        free-column-space (- bound-width (+ column-total-size column-total-gap))
        free-row-space    (- bound-height (+ row-total-size row-total-gap))
        column-autos      (tracks-total-autos column-tracks)
        row-autos         (tracks-total-autos row-tracks)
        column-add-auto   (/ free-column-space column-autos)
        row-add-auto      (/ free-row-space row-autos)

        column-tracks (add-auto-size column-tracks column-add-auto)
        row-tracks (add-auto-size row-tracks row-add-auto)

        start-p
        (cond-> bound-corner
          (= :end (:layout-align-content parent))
          (gpt/add (hv (- bound-width (+ column-total-size column-total-gap))))

          (= :center (:layout-align-content parent))
          (gpt/add (hv (/ (- bound-width (+ column-total-size column-total-gap)) 2)))

          (= :end (:layout-justify-content parent))
          (gpt/add (vv (- bound-height (+ row-total-size row-total-gap))))

          (= :center (:layout-justify-content parent))
          (gpt/add (vv (/ (- bound-height (+ row-total-size row-total-gap)) 2))))

        column-tracks
        (->> column-tracks
             (reduce (fn [[tracks start-p] {:keys [size] :as track}]
                       [(conj tracks (assoc track :start-p start-p))
                        (gpt/add start-p (hv (+ size column-gap)))])
                     [[] start-p])
             (first))

        row-tracks
        (->> row-tracks
             (reduce (fn [[tracks start-p] {:keys [size] :as track}]
                       [(conj tracks (assoc track :start-p start-p))
                        (gpt/add start-p (vv (+ size row-gap)))])
                     [[] start-p])
             (first))

        ]

    {:origin start-p
     :layout-bounds layout-bounds
     :row-tracks row-tracks
     :column-tracks column-tracks
     :shape-cells shape-cells

     ;; Convenient informaton for visualization
     :column-total-size column-total-size
     :column-total-gap column-total-gap
     :row-total-size row-total-size
     :row-total-gap row-total-gap
     }))

(defn get-cell-data
  [{:keys [origin row-tracks column-tracks shape-cells]} _transformed-parent-bounds [_ child]]

  (let [grid-cell (get shape-cells (:id child))]

    (when (some? grid-cell)
      (let [column (nth column-tracks (dec (:column grid-cell)) nil)
            row (nth row-tracks (dec (:row grid-cell)) nil)

            column-start-p (:start-p column)
            row-start-p (:start-p row)

            start-p (gpt/add origin
                             (gpt/add
                              (gpt/to-vec origin column-start-p)
                              (gpt/to-vec origin row-start-p)))
            ]

        (assoc grid-cell :start-p  start-p)))))

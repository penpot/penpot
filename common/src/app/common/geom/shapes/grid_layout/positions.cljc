;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.positions
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.grid-layout.layout-data :as ld]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]))

(defn child-modifiers
  [_parent _transformed-parent-bounds _child child-bounds cell-data]
  (ctm/move-modifiers
   (gpt/subtract (:start-p cell-data) (gpo/origin child-bounds))))


(defn line-value
  [[{px :x py :y} {vx :x vy :y}] {:keys [x y]}]
  (let [a vy
        b (- vx)
        c (+ (* (- vy) px) (* vx py))]
    (+ (* a x) (* b y) c)))

(defn is-inside-lines?
  [line-1 line-2 pos]
  (< (* (line-value line-1 pos) (line-value line-2 pos)) 0))

(defn get-position-grid-coord
  [{:keys [layout-bounds row-tracks column-tracks]} position]

  (let [hv #(gpo/start-hv layout-bounds %)
        vv #(gpo/start-vv layout-bounds %)

        ;;make-is-inside-track
        ;;(fn [type]
        ;;  (let [[vfn ofn] (if (= type :column) [vv hv] [hv vv])]
        ;;    (fn is-inside-track? [{:keys [start-p size] :as track}]
        ;;      (let [unit-v    (vfn 1)
        ;;            end-p     (gpt/add start-p (ofn size))]
        ;;        (is-inside-lines? [start-p unit-v] [end-p unit-v]  position)))))

        make-min-distance-track
        (fn [type]
          (let [[vfn ofn] (if (= type :column) [vv hv] [hv vv])]
            (fn [[selected selected-dist] [cur-idx {:keys [start-p size] :as track}]]
              (let [unit-v    (vfn 1)
                    end-p     (gpt/add start-p (ofn size))
                    dist-1    (mth/abs (line-value [start-p unit-v] position))
                    dist-2    (mth/abs (line-value [end-p unit-v] position))]

                (if (or (< dist-1 selected-dist) (< dist-2 selected-dist))
                  [[cur-idx track] (min dist-1 dist-2)]
                  [selected selected-dist])))))

        ;;[col-idx column]
        ;;(->> (d/enumerate column-tracks)
        ;;     (d/seek (comp (make-is-inside-track :column) second)))
        ;;
        ;;[row-idx row]
        ;;(->> (d/enumerate row-tracks)
        ;;     (d/seek (comp (make-is-inside-track :row) second)))


        [col-idx column]
        (->> (d/enumerate column-tracks)
             (reduce (make-min-distance-track :column) [[nil nil] ##Inf])
             (first))

        [row-idx row]
        (->> (d/enumerate row-tracks)
             (reduce (make-min-distance-track :row) [[nil nil] ##Inf])
             (first))
        ]

    (when (and (some? column) (some? row))
      [(inc row-idx) (inc col-idx)])))

(defn get-drop-cell
  [frame-id objects position]

  (let [frame       (get objects frame-id)
        children    (->> (cph/get-immediate-children objects (:id frame))
                         (remove :hidden)
                         (map #(vector (gpo/parent-coords-bounds (:points %) (:points frame)) %)))
        layout-data (ld/calc-layout-data frame children (:points frame))
        position    (gmt/transform-point-center position (gco/center-shape frame) (:transform-inverse frame))]

    (get-position-grid-coord layout-data position)))

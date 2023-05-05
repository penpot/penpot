;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.positions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.grid-layout.layout-data :as ld]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(defn cell-bounds
  [{:keys [origin row-tracks column-tracks layout-bounds column-gap row-gap] :as layout-data} {:keys [row column row-span column-span] :as cell}]

  (let [hv     #(gpo/start-hv layout-bounds %)
        vv     #(gpo/start-vv layout-bounds %)

        span-column-tracks (subvec column-tracks (dec column) (+ (dec column) column-span))
        span-row-tracks (subvec row-tracks (dec row) (+ (dec row) row-span))

        p1
        (gpt/add
         origin
         (gpt/add
          (gpt/to-vec origin (dm/get-in span-column-tracks [0 :start-p]))
          (gpt/to-vec origin (dm/get-in span-row-tracks [0 :start-p]))))

        p2
        (as-> p1  $
          (reduce (fn [p track] (gpt/add p (hv (:size track)))) $ span-column-tracks)
          (gpt/add $ (hv (* column-gap (dec (count span-column-tracks))))))

        p3
        (as-> p2  $
          (reduce (fn [p track] (gpt/add p (vv (:size track)))) $ span-row-tracks)
          (gpt/add $ (vv (* row-gap (dec (count span-row-tracks))))))

        p4
        (as-> p1  $
          (reduce (fn [p track] (gpt/add p (vv (:size track)))) $ span-row-tracks)
          (gpt/add $ (vv (* row-gap (dec (count span-row-tracks))))))]
    [p1 p2 p3 p4]))

(defn calc-fill-width-data
  "Calculates the size and modifiers for the width of an auto-fill child"
  [_parent
   transform
   transform-inverse
   _child
   child-origin child-width
   cell-bounds]

  (let [target-width (max (gpo/width-points cell-bounds) 0.01)
        fill-scale (/ target-width child-width)]
    {:width target-width
     :modifiers (ctm/resize-modifiers (gpt/point fill-scale 1) child-origin transform transform-inverse)}))

(defn calc-fill-height-data
  "Calculates the size and modifiers for the height of an auto-fill child"
  [_parent
   transform transform-inverse
   _child
   child-origin child-height
   cell-bounds]
  (let [target-height (max (gpo/height-points cell-bounds) 0.01)
        fill-scale (/ target-height child-height)]
    {:height target-height
     :modifiers (ctm/resize-modifiers (gpt/point 1 fill-scale) child-origin transform transform-inverse)}))

(defn fill-modifiers
  [parent parent-bounds child child-bounds layout-data cell-data]
  (let [child-origin (gpo/origin child-bounds)
        child-width  (gpo/width-points child-bounds)
        child-height (gpo/height-points child-bounds)

        cell-bounds (cell-bounds layout-data cell-data)

        [_ transform transform-inverse]
        (when (or (ctl/fill-width? child) (ctl/fill-height? child))
          (gtr/calculate-geometry @parent-bounds))

        fill-width
        (when (ctl/fill-width? child)
          (calc-fill-width-data parent transform transform-inverse child child-origin child-width cell-bounds))

        fill-height
        (when (ctl/fill-height? child)
          (calc-fill-height-data parent transform transform-inverse child child-origin child-height cell-bounds))]

    (-> (ctm/empty)
         (cond-> fill-width (ctm/add-modifiers (:modifiers fill-width)))
         (cond-> fill-height (ctm/add-modifiers (:modifiers fill-height))))))

(defn child-modifiers
  [parent parent-bounds child child-bounds layout-data cell-data]

  (let [fill-modifiers (fill-modifiers parent parent-bounds child child-bounds layout-data cell-data)
        position-delta (gpt/subtract (:start-p cell-data) (gpo/origin child-bounds))]
    (-> (ctm/empty)
        (ctm/add-modifiers fill-modifiers)
        (ctm/move position-delta))))


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

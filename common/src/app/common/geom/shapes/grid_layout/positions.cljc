;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.grid-layout.positions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.line :as gl]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.grid-layout.layout-data :as ld]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(defn cell-bounds
  "Retrieves the points that define the bounds for given cell"
  [{:keys [origin row-tracks column-tracks layout-bounds column-gap row-gap] :as layout-data} {:keys [row column row-span column-span] :as cell}]

  (let [hv     #(gpo/start-hv layout-bounds %)
        vv     #(gpo/start-vv layout-bounds %)

        span-column-tracks (d/safe-subvec column-tracks (dec column) (+ (dec column) column-span))
        span-row-tracks (d/safe-subvec row-tracks (dec row) (+ (dec row) row-span))]

    (when (and span-column-tracks span-row-tracks)
      (let [p1
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
        [p1 p2 p3 p4]))))

(defn calc-fill-width-data
  "Calculates the size and modifiers for the width of an auto-fill child"
  [_parent
   transform
   transform-inverse
   child
   child-origin child-width
   cell-bounds]

  (let [target-width (max (- (gpo/width-points cell-bounds) (ctl/child-width-margin child)) 0.01)
        max-width (max (ctl/child-max-width child) 0.01)
        target-width (mth/clamp target-width (ctl/child-min-width child) max-width)
        fill-scale (/ target-width child-width)]
    {:width target-width
     :modifiers (ctm/resize-modifiers (gpt/point fill-scale 1) child-origin transform transform-inverse)}))

(defn calc-fill-height-data
  "Calculates the size and modifiers for the height of an auto-fill child"
  [_parent
   transform transform-inverse
   child
   child-origin child-height
   cell-bounds]
  (let [target-height (max (- (gpo/height-points cell-bounds) (ctl/child-height-margin child)) 0.01)
        max-height (max (ctl/child-max-height child) 0.01)
        target-height (mth/clamp target-height (ctl/child-min-height child) max-height)
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
          (calc-fill-height-data parent transform transform-inverse child child-origin child-height cell-bounds))

        child-width (or (:width fill-width) child-width)
        child-height (or (:height fill-height) child-height)]

    [child-width
     child-height
     (-> (ctm/empty)
         (cond-> fill-width (ctm/add-modifiers (:modifiers fill-width)))
         (cond-> fill-height (ctm/add-modifiers (:modifiers fill-height))))]))

(defn child-position-delta
  [parent child child-bounds child-width child-height layout-data cell-data]
  (if-let [cell-bounds (cell-bounds layout-data cell-data)]
    (let [child-origin (gpo/origin child-bounds)

          align (:layout-align-items parent)
          justify (:layout-justify-items parent)
          align-self (:align-self cell-data)
          justify-self (:justify-self cell-data)

          align-self (when (and align-self (not= align-self :auto)) align-self)
          justify-self (when (and justify-self (not= justify-self :auto)) justify-self)

          align (or align-self align)
          justify (or justify-self justify)

          origin-h (gpo/project-point cell-bounds :h child-origin)
          origin-v (gpo/project-point cell-bounds :v child-origin)
          hv     (partial gpo/start-hv cell-bounds)
          vv     (partial gpo/start-vv cell-bounds)

          [top-m right-m bottom-m left-m] (ctl/child-margins child)

          ;; Adjust alignment/justify
          [from-h to-h]
          (case justify
            :end
            [(gpt/add origin-h (hv child-width))
             (gpt/subtract (nth cell-bounds 1) (hv right-m))]

            :center
            [(gpt/add origin-h (hv (/ child-width 2)))
             (-> (gpo/project-point cell-bounds :h (gpo/center cell-bounds))
                 (gpt/add (hv (/ left-m 2)))
                 (gpt/subtract (hv (/ right-m 2))))]

            [origin-h
             (gpt/add (first cell-bounds) (hv left-m))])

          [from-v to-v]
          (case align
            :end
            [(gpt/add origin-v (vv child-height))
             (gpt/subtract (nth cell-bounds 3) (vv bottom-m))]

            :center
            [(gpt/add origin-v (vv (/ child-height 2)))
             (-> (gpo/project-point cell-bounds :v (gpo/center cell-bounds))
                 (gpt/add (vv top-m))
                 (gpt/subtract (vv bottom-m)))]

            [origin-v
             (gpt/add (first cell-bounds) (vv top-m))])]

      (-> (gpt/point)
          (gpt/add (gpt/to-vec from-h to-h))
          (gpt/add (gpt/to-vec from-v to-v))))
    (gpt/point 0 0)))

(defn child-modifiers
  [parent parent-bounds child child-bounds layout-data cell-data]

  (let [[child-width child-height fill-modifiers]
        (fill-modifiers parent parent-bounds child child-bounds layout-data cell-data)

        position-delta (child-position-delta parent child child-bounds child-width child-height layout-data cell-data)]

    (cond-> (ctm/empty)
      (not (ctl/position-absolute? child))
      (-> (ctm/add-modifiers fill-modifiers)
          (ctm/move position-delta)))))

(defn get-position-grid-coord
  [{:keys [layout-bounds row-tracks column-tracks]} position]

  (let [hv #(gpo/start-hv layout-bounds %)
        vv #(gpo/start-vv layout-bounds %)

        make-is-inside-track
        (fn [type]
          (let [[vfn ofn] (if (= type :column) [vv hv] [hv vv])]
            (fn is-inside-track? [{:keys [start-p size] :as track}]
              (let [unit-v    (vfn 1)
                    end-p     (gpt/add start-p (ofn size))]
                (gl/is-inside-lines? [start-p unit-v] [end-p unit-v]  position)))))

        make-min-distance-track
        (fn [type]
          (let [[vfn ofn] (if (= type :column) [vv hv] [hv vv])]
            (fn [[selected selected-dist] [cur-idx {:keys [start-p size] :as track}]]
              (let [unit-v    (vfn 1)
                    end-p     (gpt/add start-p (ofn size))
                    dist-1    (mth/abs (gl/line-value [start-p unit-v] position))
                    dist-2    (mth/abs (gl/line-value [end-p unit-v] position))]

                (if (or (< dist-1 selected-dist) (< dist-2 selected-dist))
                  [[cur-idx track] (min dist-1 dist-2)]
                  [selected selected-dist])))))

        ;; Check if it's inside a track
        [col-idx column]
        (->> (d/enumerate column-tracks)
             (d/seek (comp (make-is-inside-track :column) second)))

        [row-idx row]
        (->> (d/enumerate row-tracks)
             (d/seek (comp (make-is-inside-track :row) second)))

        ;; If not inside we find the closest start/end line
        [col-idx column]
        (if (some? column)
          [col-idx column]
          (->> (d/enumerate column-tracks)
               (reduce (make-min-distance-track :column) [[nil nil] ##Inf])
               (first)))

        [row-idx row]
        (if (some? row)
          [row-idx row]
          (->> (d/enumerate row-tracks)
               (reduce (make-min-distance-track :row) [[nil nil] ##Inf])
               (first)))]

    (when (and (some? column) (some? row))
      [(inc row-idx) (inc col-idx)])))

(defn get-drop-cell
  [frame-id objects position]

  (let [frame       (get objects frame-id)
        children    (->> (cfh/get-immediate-children objects (:id frame))
                         (remove :hidden)
                         (map #(vector (gpo/parent-coords-bounds (:points %) (:points frame)) %)))

        bounds (d/lazy-map (keys objects) #(gco/shape->points (get objects %)))
        layout-data (ld/calc-layout-data frame (:points frame) children bounds objects)]

    (get-position-grid-coord layout-data position)))

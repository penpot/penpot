;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.points
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.intersect :as gsi]
   [app.common.math :as mth]))

(defn origin
  [points]
  (nth points 0))

(defn hv
  [[p0 p1 _ _]]
  (gpt/to-vec p0 p1))

(defn vv
  [[p0 _ _ p3]]
  (gpt/to-vec p0 p3))

(defn start-hv
  "Horizontal vector from the origin with a magnitude `val`"
  [[p0 p1 _ _] val]
  (-> (gpt/to-vec p0 p1)
      (gpt/unit)
      (gpt/scale val)))

(defn end-hv
  "Horizontal vector from the oposite to the origin in the x axis with a magnitude `val`"
  [[p0 p1 _ _] val]
  (-> (gpt/to-vec p1 p0)
      (gpt/unit)
      (gpt/scale val)))

(defn start-vv
  "Vertical vector from the oposite to the origin in the x axis with a magnitude `val`"
  [[p0 _ _ p3] val]
  (-> (gpt/to-vec p0 p3)
      (gpt/unit)
      (gpt/scale val)))

(defn end-vv
  "Vertical vector from the oposite to the origin in the x axis with a magnitude `val`"
  [[p0 _ _ p3] val]
  (-> (gpt/to-vec p3 p0)
      (gpt/unit)
      (gpt/scale val)))

(defn width-points
  [[p0 p1 _ _]]
  (when (and (some? p0) (some? p1))
    (max 0.01 (gpt/length (gpt/to-vec p0 p1)))))

(defn height-points
  [[p0 _ _ p3]]
  (when (and (some? p0) (some? p3))
    (max 0.01 (gpt/length (gpt/to-vec p0 p3)))))

(defn pad-points
  [[p0 p1 p2 p3 :as points] pad-top pad-right pad-bottom pad-left]
  (when (some? points)
    (let [top-v    (start-vv points pad-top)
          right-v  (end-hv points pad-right)
          bottom-v (end-vv points pad-bottom)
          left-v   (start-hv points pad-left)]

      [(-> p0 (gpt/add left-v)  (gpt/add top-v))
       (-> p1 (gpt/add right-v) (gpt/add top-v))
       (-> p2 (gpt/add right-v) (gpt/add bottom-v))
       (-> p3 (gpt/add left-v)  (gpt/add bottom-v))])))

(defn project-t
  "Given a point and a line returns the parametric t the cross point with the line going through the other axis projected"
  [point [start end] other-axis-vec]

  (let [line-vec (gpt/to-vec start end)
        pr-point (gsi/line-line-intersect point (gpt/add point other-axis-vec) start end)]
    (cond
      (not (mth/almost-zero? (:x line-vec)))
      (/ (- (:x pr-point) (:x start)) (:x line-vec))

      (not (mth/almost-zero? (:y line-vec)))
      (/ (- (:y pr-point) (:y start)) (:y line-vec))

      ;; Vector is almost zero
      :else
      0)))

(defn project-point
  "Project the point into the given axis: `:h` or `:v` means horizontal or vertical axis"
  [[p0 p1 _ p3 :as bounds] axis point]
  (let [[other-vec start end]
        (if (= axis :h)
          [(gpt/to-vec p0 p3) p0 p1]
          [(gpt/to-vec p0 p1) p0 p3])]
    (gsi/line-line-intersect point (gpt/add point other-vec) start end)))

(defn axis-aligned?
  "Check if the points are parallel to the coordinate axis."
  [[p1 p2 _ p4 :as pts]]
  (and (= (count pts) 4)
       (let [hv (gpt/to-vec p1 p2)
             vv (gpt/to-vec p1 p4)]
         (and (mth/almost-zero? (:y hv))
              (mth/almost-zero? (:x vv))
              (> (:x hv) 0)
              (> (:y vv) 0)))))

(defn parent-coords-bounds
  [child-bounds [p1 p2 _ p4 :as parent-bounds]]
  (if (empty? child-bounds)
    parent-bounds

    (if (and (axis-aligned? child-bounds) (axis-aligned? parent-bounds))
      child-bounds

      (let [rh [p1 p2]
            rv [p1 p4]

            hv (gpt/to-vec p1 p2)
            vv (gpt/to-vec p1 p4)

            ph #(gpt/add p1 (gpt/scale hv %))
            pv #(gpt/add p1 (gpt/scale vv %))

            find-boundary-ts
            (fn [[th-min th-max tv-min tv-max] current-point]
              (let [cth (project-t current-point rh vv)
                    ctv (project-t current-point rv hv)]
                [(mth/min th-min cth)
                 (mth/max th-max cth)
                 (mth/min tv-min ctv)
                 (mth/max tv-max ctv)]))

            [th-min th-max tv-min tv-max]
            (->> child-bounds
                 (filter #(and (d/num? (:x %)) (d/num? (:y %))))
                 (reduce find-boundary-ts [##Inf ##-Inf ##Inf ##-Inf]))

            minv-start (pv tv-min)
            minv-end   (gpt/add minv-start hv)
            minh-start (ph th-min)
            minh-end   (gpt/add minh-start vv)

            maxv-start (pv tv-max)
            maxv-end   (gpt/add maxv-start hv)
            maxh-start (ph th-max)
            maxh-end   (gpt/add maxh-start vv)

            i1 (gsi/line-line-intersect minv-start minv-end minh-start minh-end)
            i2 (gsi/line-line-intersect minv-start minv-end maxh-start maxh-end)
            i3 (gsi/line-line-intersect maxv-start maxv-end maxh-start maxh-end)
            i4 (gsi/line-line-intersect maxv-start maxv-end minh-start minh-end)]
        [i1 i2 i3 i4]))))

(defn merge-parent-coords-bounds
  [bounds parent-bounds]
  (parent-coords-bounds (flatten bounds) parent-bounds))

(defn move
  [bounds vector]
  (->> bounds
       (map #(gpt/add % vector))))

(defn center
  [bounds]
  (let [width (width-points bounds)
        height (height-points bounds)
        half-h (start-hv bounds (/ width 2))
        half-v (start-vv bounds (/ height 2))]
    (-> (origin bounds)
        (gpt/add half-h)
        (gpt/add half-v))))

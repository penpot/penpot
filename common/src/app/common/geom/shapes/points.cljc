;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.points
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.intersect :as gsi]))

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
  (gpt/length (gpt/to-vec p0 p1)))

(defn height-points
  [[p0 _ _ p3]]
  (gpt/length (gpt/to-vec p0 p3)))

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



#_(defn parent-coords-rect
  [child-bounds parent-bounds]
  #_(-> child-bounds
      (gco/transform-points (:transform-inverse parent))
      (gpr/points->rect)))

(defn closest-first
  "Reorders the points so the closest to the line start-end is the first"
  [[a b c d] start end]

  (let [da (gpt/point-line-distance a start end)
        db (gpt/point-line-distance b start end)
        dc (gpt/point-line-distance c start end)
        dd (gpt/point-line-distance d start end)]

    (cond
      (and (<= da db) (<= da dc) (<= da dd))
      [a b c d]

      (and (<= db da) (<= db dc) (<= db dd))
      [b c d a]

      (and (<= dc da) (<= dc db) (<= dc dd))
      [c d a b]

      :else
      [d a b c])))

(defn parent-coords-bounds
  [bounds [p1 p2 _ p4]]

  (let [[b1 b2 b3 b4] (closest-first bounds p1 p2)
        hv (gpt/to-vec p1 p2)
        vv (gpt/to-vec p1 p4)

        i1 (gsi/line-line-intersect b1 (gpt/add hv b1) b4 (gpt/add b4 vv))
        i2 (gsi/line-line-intersect b1 (gpt/add hv b1) b2 (gpt/add b2 vv))
        i3 (gsi/line-line-intersect b3 (gpt/add hv b3) b2 (gpt/add b2 vv))
        i4 (gsi/line-line-intersect b3 (gpt/add hv b3) b4 (gpt/add b4 vv))]
    [i1 i2 i3 i4]))

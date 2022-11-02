;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.points
  (:require
   [app.common.geom.point :as gpt]))

(defn origin
  [points]
  (nth points 0))

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

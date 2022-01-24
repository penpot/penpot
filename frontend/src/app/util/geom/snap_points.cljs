;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.geom.snap-points
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]))

(defn- selrect-snap-points [{:keys [x y width height]}]
  #{(gpt/point x y)
    (gpt/point (+ x width) y)
    (gpt/point (+ x width) (+ y height))
    (gpt/point x (+ y height))})

(defn- frame-snap-points [{:keys [x y width height] :as selrect}]
  (into (selrect-snap-points selrect)
        #{(gpt/point (+ x (/ width 2)) y)
          (gpt/point (+ x width) (+ y (/ height 2)))
          (gpt/point (+ x (/ width 2)) (+ y height))
          (gpt/point x (+ y (/ height 2)))}))

(defn shape-snap-points
  [shape]
  (let [shape (gsh/transform-shape shape)]
    (case (:type shape)
      :frame (-> shape :selrect frame-snap-points)
      (into #{(gsh/center-shape shape)} (:points shape)))))

(defn guide-snap-points
  [guide]
  (if (= :x (:axis guide))
    #{(gpt/point (:position guide) 0)}
    #{(gpt/point 0 (:position guide))}))

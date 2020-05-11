;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.geom.snap-points
  (:require
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.util.geom.point :as gpt]))

(defn- frame-snap-points [{:keys [x y width height]}]
  #{(gpt/point x y)
    (gpt/point (+ x (/ width 2)) y)
    (gpt/point (+ x width) y)
    (gpt/point (+ x width) (+ y (/ height 2)))
    (gpt/point (+ x width) (+ y height))
    (gpt/point (+ x (/ width 2)) (+ y height))
    (gpt/point x (+ y height))
    (gpt/point x (+ y (/ height 2)))})

(defn- frame-snap-points-resize [{:keys [x y width height]} handler]
  (case handler
    :top-left (gpt/point x y)
    :top (gpt/point (+ x (/ width 2)) y)
    :top-right (gpt/point (+ x width) y)
    :right (gpt/point (+ x width) (+ y (/ height 2)))
    :bottom-right (gpt/point (+ x width) (+ y height))
    :bottom (gpt/point (+ x (/ width 2)) (+ y height))
    :bottom-left (gpt/point x (+ y height))
    :left (gpt/point x (+ y (/ height 2)))))

(def ^:private handler->point-idx
  {:top-left 0
   :top 0
   :top-right 1
   :right 1
   :bottom-right 2
   :bottom 2
   :bottom-left 3
   :left 3})

(defn shape-snap-points
  [shape]
  (let [modified-path (gsh/transform-apply-modifiers shape)
        shape-center (gsh/center modified-path)]
    (case (:type shape)
      :frame (-> modified-path gsh/shape->rect-shape frame-snap-points)
      (:path :curve) (into #{shape-center} (-> modified-path gsh/shape->rect-shape :segments))
      (into #{shape-center} (-> modified-path :segments)))))

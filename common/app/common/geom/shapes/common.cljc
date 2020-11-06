;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.shapes.common
  (:require
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gpa]
   [app.common.math :as mth]
   [app.common.data :as d]))

;; --- Center

(declare center-rect)
(declare center-path)

(defn center
  "Calculate the center of the shape."
  [shape]
  (case (:type shape)
    :curve (center-path shape)
    :path (center-path shape)
    (center-rect shape)))

(defn- center-rect
  [{:keys [x y width height] :as shape}]
  (gpt/point (+ x (/ width 2)) (+ y (/ height 2))))

(defn- center-path
  [{:keys [segments] :as shape}]
  (let [minx (apply min (map :x segments))
        miny (apply min (map :y segments))
        maxx (apply max (map :x segments))
        maxy (apply max (map :y segments))]
    (gpt/point (/ (+ minx maxx) 2) (/ (+ miny maxy) 2))))

(defn center->rect
  "Creates a rect given a center and a width and height"
  [center width height]
  {:x (- (:x center) (/ width 2))
   :y (- (:y center) (/ height 2))
   :width width
   :height height})


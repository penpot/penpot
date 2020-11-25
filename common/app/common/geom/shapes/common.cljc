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
   [app.common.math :as mth]
   [app.common.data :as d]))

(defn center-rect
  [{:keys [x y width height]}]
  (when (and (mth/finite? x)
             (mth/finite? y)
             (mth/finite? width)
             (mth/finite? height))
    (gpt/point (+ x (/ width 2))
               (+ y (/ height 2)))))

(defn center-selrect
  "Calculate the center of the shape."
  [selrect]
  (center-rect selrect))

(def map-x-xf (comp (map :x) (remove nil?)))
(def map-y-xf (comp (map :y) (remove nil?)))

(defn center-points [points]
  (let [ptx  (into [] map-x-xf points)
        pty  (into [] map-y-xf points)
        minx (reduce min ##Inf ptx)
        miny (reduce min ##Inf pty)
        maxx (reduce max ##-Inf ptx)
        maxy (reduce max ##-Inf pty)]
    (gpt/point (/ (+ minx maxx) 2)
               (/ (+ miny maxy) 2))))

(defn center-shape
  "Calculate the center of the shape."
  [shape]
  (center-rect (:selrect shape)))

(defn make-centered-rect
  "Creates a rect given a center and a width and height"
  [center width height]
  {:x (- (:x center) (/ width 2))
   :y (- (:y center) (/ height 2))
   :width width
   :height height})

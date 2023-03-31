;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.common
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]))

(defn center-rect
  [{:keys [x y width height]}]
  (when (d/num? x y width height)
    (gpt/point (+ x (/ width 2.0))
               (+ y (/ height 2.0)))))

(defn center-selrect
  "Calculate the center of the selrect."
  [selrect]
  (center-rect selrect))

(defn center-points [points]
  (let [ptx  (into [] (keep :x) points)
        pty  (into [] (keep :y) points)
        minx (reduce min ##Inf ptx)
        miny (reduce min ##Inf pty)
        maxx (reduce max ##-Inf ptx)
        maxy (reduce max ##-Inf pty)]
    (gpt/point (/ (+ minx maxx) 2.0)
               (/ (+ miny maxy) 2.0))))

(defn center-bounds [[a b c d]]
  (let [xa (:x a)
        ya (:y a)
        xb (:x b)
        yb (:y b)
        xc (:x c)
        yc (:y c)
        xd (:x d)
        yd (:y d)
        minx (min xa xb xc xd)
        miny (min ya yb yc yd)
        maxx (max xa xb xc xd)
        maxy (max ya yb yc yd)]
    (gpt/point (/ (+ minx maxx) 2.0)
               (/ (+ miny maxy) 2.0))))

(defn center-shape
  "Calculate the center of the shape."
  [shape]
  (center-rect (:selrect shape)))

(defn transform-points
  ([points matrix]
   (transform-points points nil matrix))

  ([points center matrix]
   (if (and (d/not-empty? points) (gmt/matrix? matrix))
     (let [prev (if center (gmt/translate-matrix center) (gmt/matrix))
           post (if center (gmt/translate-matrix (gpt/negate center)) (gmt/matrix))

           tr-point (fn [point]
                      (gpt/transform point (gmt/multiply prev matrix post)))]
       (mapv tr-point points))
     points)))

(defn transform-selrect
  [{:keys [x1 y1 x2 y2] :as sr} matrix]
  (let [[c1 c2] (transform-points [(gpt/point x1 y1) (gpt/point x2 y2)] matrix)]
    (gpr/corners->selrect c1 c2)))

(defn invalid-geometry?
  [{:keys [points selrect]}]

  (or (mth/nan? (:x selrect))
      (mth/nan? (:y selrect))
      (mth/nan? (:width selrect))
      (mth/nan? (:height selrect))
      (some (fn [p]
              (or (mth/nan? (:x p))
                  (mth/nan? (:y p))))
            points)))

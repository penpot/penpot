;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.common
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.record :as cr]))

(def ^:private xf-keep-x (keep #(dm/get-prop % :x)))
(def ^:private xf-keep-y (keep #(dm/get-prop % :y)))

(defn shapes->rect
  "Returns a rect that contains all the shapes and is aware of the
  rotation of each shape. Mainly used for multiple selection."
  [shapes]
  (->> shapes
       (keep (fn [shape]
               (-> (dm/get-prop shape :points)
                   (grc/points->rect))))
       (grc/join-rects)))

(defn points->center
  [points]
  (let [ptx  (into [] xf-keep-x points)
        pty  (into [] xf-keep-y points)
        minx (reduce d/min ##Inf ptx)
        miny (reduce d/min ##Inf pty)
        maxx (reduce d/max ##-Inf ptx)
        maxy (reduce d/max ##-Inf pty)]
    (gpt/point (/ (+ minx maxx) 2.0)
               (/ (+ miny maxy) 2.0))))

(defn shape->center
  "Calculate the center of the shape."
  [shape]
  (grc/rect->center (dm/get-prop shape :selrect)))

(defn transform-points
  ([points matrix]
   (transform-points points nil matrix))

  ([points center matrix]
   (if (and ^boolean (gmt/matrix? matrix)
            ^boolean (seq points))
     (let [prev (if (some? center) (gmt/translate-matrix center) (cr/clone gmt/base))
           post (if (some? center) (gmt/translate-matrix-neg center) gmt/base)
           mtx  (-> prev
                    (gmt/multiply! matrix)
                    (gmt/multiply! post))]
       (mapv #(gpt/transform % mtx) points))
     points)))

(defn transform-selrect
  [selrect matrix]

  (dm/assert!
   "expected valid rect and matrix instances"
   (and (grc/rect? selrect)
        (gmt/matrix? matrix)))

  (let [x1 (dm/get-prop selrect :x1)
        y1 (dm/get-prop selrect :y1)
        x2 (dm/get-prop selrect :x2)
        y2 (dm/get-prop selrect :y2)
        p1 (gpt/point x1 y1)
        p2 (gpt/point x2 y2)
        c1 (gpt/transform! p1 matrix)
        c2 (gpt/transform! p2 matrix)]
    (grc/corners->rect c1 c2)))

(defn invalid-geometry?
  [{:keys [points selrect]}]

  (or ^boolean (mth/nan? (:x selrect))
      ^boolean (mth/nan? (:y selrect))
      ^boolean (mth/nan? (:width selrect))
      ^boolean (mth/nan? (:height selrect))
      ^boolean (some (fn [p]
                       (or ^boolean (mth/nan? (:x p))
                           ^boolean (mth/nan? (:y p))))
                     points)))

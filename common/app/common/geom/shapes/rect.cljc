;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.shapes.rect
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]))

(defn rect->points [{:keys [x y width height]}]
  (assert (number? x))
  (assert (number? y))
  (assert (and (number? width) (> width 0)))
  (assert (and (number? height) (> height 0)))
  [(gpt/point x y)
   (gpt/point (+ x width) y)
   (gpt/point (+ x width) (+ y height))
   (gpt/point x (+ y height))])

(defn points->rect
  [points]
  (let [minx (transduce gco/map-x-xf min ##Inf points)
        miny (transduce gco/map-y-xf min ##Inf points)
        maxx (transduce gco/map-x-xf max ##-Inf points)
        maxy (transduce gco/map-y-xf max ##-Inf points)]
    {:x minx
     :y miny
     :width (- maxx minx)
     :height (- maxy miny)}))

(defn points->selrect [points]
  (let [{:keys [x y width height] :as rect} (points->rect points)]
    (assoc rect
           :x1 x
           :x2 (+ x width)
           :y1 y
           :y2 (+ y height))))

(defn rect->selrect [rect]
  (-> rect rect->points points->selrect))

(defn join-selrects [selrects]
  (let [minx (transduce (comp (map :x1) (remove nil?)) min ##Inf selrects)
        miny (transduce (comp (map :y1) (remove nil?)) min ##Inf selrects)
        maxx (transduce (comp (map :x2) (remove nil?)) max ##-Inf selrects)
        maxy (transduce (comp (map :y2) (remove nil?)) max ##-Inf selrects)]
    {:x minx
     :y miny
     :x1 minx
     :y1 miny
     :x2 maxx
     :y2 maxy
     :width (- maxx minx)
     :height (- maxy miny)}))


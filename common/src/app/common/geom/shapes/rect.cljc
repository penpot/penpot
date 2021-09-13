;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.rect
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]))

(defn rect->points [{:keys [x y width height]}]
  ;; (assert (number? x))
  ;; (assert (number? y))
  ;; (assert (and (number? width) (> width 0)))
  ;; (assert (and (number? height) (> height 0)))
  [(gpt/point x y)
   (gpt/point (+ x width) y)
   (gpt/point (+ x width) (+ y height))
   (gpt/point x (+ y height))])

(defn rect->lines [{:keys [x y width height]}]
  [[(gpt/point x y) (gpt/point (+ x width) y)]
   [(gpt/point (+ x width) y) (gpt/point (+ x width) (+ y height))]
   [(gpt/point (+ x width) (+ y height)) (gpt/point x (+ y height))]
   [(gpt/point x (+ y height)) (gpt/point x y)]])

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

(defn center->rect [center width height]
  (assert (gpt/point center))
  (assert (and (number? width) (> width 0)))
  (assert (and (number? height) (> height 0)))

  {:x (- (:x center) (/ width 2))
   :y (- (:y center) (/ height 2))
   :width width
   :height height})

(defn overlaps-rects?
  "Check for two rects to overlap. Rects won't overlap only if
   one of them is fully to the left or the top"
  [rect-a rect-b]

  (let [x1a (:x rect-a)
        y1a (:y rect-a)
        x2a (+ (:x rect-a) (:width rect-a))
        y2a (+ (:y rect-a) (:height rect-a))

        x1b (:x rect-b)
        y1b (:y rect-b)
        x2b (+ (:x rect-b) (:width rect-b))
        y2b (+ (:y rect-b) (:height rect-b))]

    (and (> x2a x1b)
         (> x2b x1a)
         (> y2a y1b)
         (> y2b y1a))))

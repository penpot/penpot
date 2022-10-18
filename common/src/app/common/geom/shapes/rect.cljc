;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.rect
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]))

(defn make-rect
  [x y width height]
  (when (d/num? x y width height)
    (let [width (max width 0.01)
          height (max height 0.01)]
      {:x x
       :y y
       :width width
       :height height})))

(defn make-selrect
  [x y width height]
  (when (d/num? x y width height)
    (let [width (max width 0.01)
          height (max height 0.01)]
      {:x x
       :y y
       :x1 x
       :y1 y
       :x2 (+ x width)
       :y2 (+ y height)
       :width width
       :height height})))

(defn close-rect?
  [rect1 rect2]
  (and (mth/close? (:x rect1) (:x rect2))
       (mth/close? (:y rect1) (:y rect2))
       (mth/close? (:width rect1) (:width rect2))
       (mth/close? (:height rect1) (:height rect2))))

(defn close-selrect?
  [selrect1 selrect2]
  (and (mth/close? (:x selrect1) (:x selrect2))
       (mth/close? (:y selrect1) (:y selrect2))
       (mth/close? (:x1 selrect1) (:x1 selrect2))
       (mth/close? (:y1 selrect1) (:y1 selrect2))
       (mth/close? (:x2 selrect1) (:x2 selrect2))
       (mth/close? (:y2 selrect1) (:y2 selrect2))
       (mth/close? (:width selrect1) (:width selrect2))
       (mth/close? (:height selrect1) (:height selrect2))))

(defn rect->points [{:keys [x y width height]}]
  (when (d/num? x y)
    (let [width  (max width 0.01)
          height (max height 0.01)]
      [(gpt/point x y)
       (gpt/point (+ x width) y)
       (gpt/point (+ x width) (+ y height))
       (gpt/point x (+ y height))])))

(defn rect->lines [{:keys [x y width height]}]
  (when (d/num? x y)
    (let [width (max width 0.01)
          height (max height 0.01)]
      [[(gpt/point x y) (gpt/point (+ x width) y)]
       [(gpt/point (+ x width) y) (gpt/point (+ x width) (+ y height))]
       [(gpt/point (+ x width) (+ y height)) (gpt/point x (+ y height))]
       [(gpt/point x (+ y height)) (gpt/point x y)]])))

(defn points->rect
  [points]
  (when (d/not-empty? points)
    (let [minx (transduce (keep :x) min ##Inf points)
          miny (transduce (keep :y) min ##Inf points)
          maxx (transduce (keep :x) max ##-Inf points)
          maxy (transduce (keep :y) max ##-Inf points)]
      (when (d/num? minx miny maxx maxy)
        (make-rect minx miny (- maxx minx) (- maxy miny))))))

(defn points->selrect [points]
  (when-let [rect (points->rect points)]
    (let [{:keys [x y width height]} rect]
      (make-selrect x y width height))))

(defn rect->selrect [rect]
  (-> rect rect->points points->selrect))

(defn join-rects [rects]
  (when (d/not-empty? rects)
    (let [minx (transduce (keep :x) min ##Inf rects)
          miny (transduce (keep :y) min ##Inf rects)
          maxx (transduce (keep #(when (and (:x %) (:width %)) (+ (:x %) (:width %))))  max ##-Inf rects)
          maxy (transduce (keep #(when (and (:y %) (:height %))(+ (:y %) (:height %)))) max ##-Inf rects)]
      (when (d/num? minx miny maxx maxy)
        (make-rect minx miny (- maxx minx) (- maxy miny))))))

(defn join-selrects [selrects]
  (when (d/not-empty? selrects)
    (let [minx (transduce (keep :x1) min ##Inf selrects)
          miny (transduce (keep :y1) min ##Inf selrects)
          maxx (transduce (keep :x2) max ##-Inf selrects)
          maxy (transduce (keep :y2) max ##-Inf selrects)]
      (when (d/num? minx miny maxx maxy)
        (make-selrect minx miny (- maxx minx) (- maxy miny))))))

(defn center->rect [{:keys [x y]} width height]
  (when (d/num? x y width height)
    (make-rect (- x (/ width 2))
               (- y (/ height 2))
               width
               height)))

(defn center->selrect [{:keys [x y]} width height]
  (when (d/num? x y width height)
    (make-selrect (- x (/ width 2))
                  (- y (/ height 2))
                  width
                  height)))

(defn s=
  [a b]
  (mth/almost-zero? (- a b)))

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

    (and (or (> x2a x1b)  (s= x2a x1b))
         (or (>= x2b x1a) (s= x2b x1a))
         (or (<= y1b y2a) (s= y1b y2a))
         (or (<= y1a y2b) (s= y1a y2b)))))

(defn contains-point?
  [rect point]
  (assert (gpt/point? point))
  (let [x1 (:x rect)
        y1 (:y rect)
        x2 (+ (:x rect) (:width rect))
        y2 (+ (:y rect) (:height rect))

        px (:x point)
        py (:y point)]

    (and (or (> px x1) (s= px x1))
         (or (< px x2) (s= px x2))
         (or (> py y1) (s= py y1))
         (or (< py y2) (s= py y2)))))

(defn contains-selrect?
  "Check if a selrect sr2 is contained inside sr1"
  [sr1 sr2]
  (and (>= (:x1 sr2) (:x1 sr1))
       (<= (:x2 sr2) (:x2 sr1))
       (>= (:y1 sr2) (:y1 sr1))
       (<= (:y2 sr2) (:y2 sr1))))


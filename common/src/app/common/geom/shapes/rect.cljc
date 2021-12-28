;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.rect
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]))

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

(defn join-rects [rects]
  (let [minx (transduce (comp (map :x) (remove nil?)) min ##Inf rects)
        miny (transduce (comp (map :y) (remove nil?)) min ##Inf rects)
        maxx (transduce (comp (map #(+ (:x %) (:width %))) (remove nil?)) max ##-Inf rects)
        maxy (transduce (comp (map #(+ (:y %) (:height %))) (remove nil?)) max ##-Inf rects)]
    {:x minx
     :y miny
     :width (- maxx minx)
     :height (- maxy miny)}))

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

(defn round-selrect
  [selrect]
  (-> selrect
      (update :x mth/round)
      (update :y mth/round)
      (update :width mth/round)
      (update :height mth/round)))

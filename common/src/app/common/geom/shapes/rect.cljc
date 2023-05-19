;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.rect
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.transit :as t]
   [app.common.math :as mth]))

(defrecord Rect [x y width height])

#?(:clj
   (fres/add-handlers!
    {:name "penpot/geom/rect"
     :class Rect
     :wfn fres/write-map-like
     :rfn (comp map->Rect fres/read-map-like)}))

(t/add-handlers!
 {:id "rect"
  :class Rect
  :wfn #(into {} %)
  :rfn map->Rect})

;; FIXME: optimize access using static props

(defn make-rect
  ([p1 p2]
   (let [xp1 (:x p1)
         yp1 (:y p1)
         xp2 (:x p2)
         yp2 (:y p2)
         x1  (min xp1 xp2)
         y1  (min yp1 yp2)
         x2  (max xp1 xp2)
         y2  (max yp1 yp2)]
     (make-rect x1 y1 (- x2 x1) (- y2 y1))))

  ([x y width height]
   (when (d/num? x y width height)
     (let [width (max width 0.01)
           height (max height 0.01)]
       (map->Rect
        {:x x
         :y y
         :width width
         :height height})))))

(defn make-selrect
  [x y width height]
  (when (d/num? x y width height)
    (let [width (max width 0.01)
          height (max height 0.01)]
      (map->Rect {:x x
                  :y y
                  :x1 x
                  :y1 y
                  :x2 (+ x width)
                  :y2 (+ y height)
                  :width width
                  :height height}))))

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
  (when-let [points (seq points)]
    (loop [minx ##Inf
           miny ##Inf
           maxx ##-Inf
           maxy ##-Inf
           pts  points]
      (if-let [pt (first pts)]
        (let [x (dm/get-prop pt :x)
              y (dm/get-prop pt :y)]
          (recur (min minx x)
                 (min miny y)
                 (max maxx x)
                 (max maxy y)
                 (rest pts)))
        (when (d/num? minx miny maxx maxy)
          (make-rect minx miny (- maxx minx) (- maxy miny)))))))

(defn bounds->rect
  [[{ax :x ay :y} {bx :x by :y} {cx :x cy :y} {dx :x dy :y}]]
  (let [minx (min ax bx cx dx)
        miny (min ay by cy dy)
        maxx (max ax bx cx dx)
        maxy (max ay by cy dy)]
    (when (d/num? minx miny maxx maxy)
      (make-rect minx miny (- maxx minx) (- maxy miny)))))

(defn squared-points
  [points]
  (when (d/not-empty? points)
    (let [minx (transduce (keep :x) min ##Inf points)
          miny (transduce (keep :y) min ##Inf points)
          maxx (transduce (keep :x) max ##-Inf points)
          maxy (transduce (keep :y) max ##-Inf points)]
      (when (d/num? minx miny maxx maxy)
        [(gpt/point minx miny)
         (gpt/point maxx miny)
         (gpt/point maxx maxy)
         (gpt/point minx maxy)]))))

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

(defn corners->selrect
  ([p1 p2]
   (corners->selrect (:x p1) (:y p1) (:x p2) (:y p2)))
  ([xp1 yp1 xp2 yp2]
   (make-selrect (min xp1 xp2) (min yp1 yp2) (abs (- xp1 xp2)) (abs (- yp1 yp2)))))

(defn clip-selrect
  [{:keys [x1 y1 x2 y2] :as sr} bounds]
  (when (some? sr)
    (let [{bx1 :x1 by1 :y1 bx2 :x2 by2 :y2} (rect->selrect bounds)]
      (corners->selrect (max bx1 x1) (max by1 y1) (min bx2 x2) (min by2 y2)))))

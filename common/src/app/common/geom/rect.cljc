;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.rect
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.record :as rc]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.transit :as t]))

(rc/defrecord Rect [x y width height x1 y1 x2 y2])

(defn rect?
  [o]
  (instance? Rect o))

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

(defn make-rect
  ([] (make-rect 0 0 0.01 0.01))
  ([data]
   (if (rect? data)
     data
     (let [{:keys [x y width height]} data]
       (make-rect (d/nilv x 0)
                  (d/nilv y 0)
                  (d/nilv width 0.01)
                  (d/nilv height 0.01)))))

  ([p1 p2]
   (dm/assert!
    "expected `p1` and `p2` to be points"
    (and (gpt/point? p1)
         (gpt/point? p2)))

   (let [xp1 (dm/get-prop p1 :x)
         yp1 (dm/get-prop p1 :y)
         xp2 (dm/get-prop p2 :x)
         yp2 (dm/get-prop p2 :y)
         x1  (mth/min xp1 xp2)
         y1  (mth/min yp1 yp2)
         x2  (mth/max xp1 xp2)
         y2  (mth/max yp1 yp2)]
     (make-rect x1 y1 (- x2 x1) (- y2 y1))))

  ([x y width height]
   (if (d/num? x y width height)
     (let [w (mth/max width 0.01)
           h (mth/max height 0.01)]
       (pos->Rect x y w h x y (+ x w) (+ y h)))
     (make-rect))))

(def ^:private schema:rect-attrs
  [:map {:title "RectAttrs"}
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:height ::sm/safe-number]
   [:x1 ::sm/safe-number]
   [:y1 ::sm/safe-number]
   [:x2 ::sm/safe-number]
   [:y2 ::sm/safe-number]])

(defn- rect-generator
  []
  (->> (sg/tuple (sg/small-double)
                 (sg/small-double)
                 (sg/small-double)
                 (sg/small-double))
       (sg/fmap #(apply make-rect %))))

(defn- decode-rect
  [o]
  (if (map? o)
    (map->Rect o)
    o))

(defn- rect->json
  [o]
  (if (rect? o)
    (into {} o)
    o))

(def schema:rect
  [:and {:error/message "errors.invalid-rect"
         :gen/gen (rect-generator)
         :decode/json {:leave decode-rect}
         :encode/json rect->json}
   schema:rect-attrs
   [:fn rect?]])

(def valid-rect?
  (sm/validator schema:rect))

(sm/register! ::rect schema:rect)

(def empty-rect
  (make-rect 0 0 0.01 0.01))

(defn update-rect
  [rect type]
  (case type
    :size
    (let [x (dm/get-prop rect :x)
          y (dm/get-prop rect :y)
          w (dm/get-prop rect :width)
          h (dm/get-prop rect :height)]
      (assoc rect
             :x2 (+ x w)
             :y2 (+ y h)))

    :corners
    (let [x1 (dm/get-prop rect :x1)
          y1 (dm/get-prop rect :y1)
          x2 (dm/get-prop rect :x2)
          y2 (dm/get-prop rect :y2)]
      (assoc rect
             :x (mth/min x1 x2)
             :y (mth/min y1 y2)
             :width (mth/abs (- x2 x1))
             :height (mth/abs (- y2 y1))))

    ;; FIXME: looks unused
    :position
    (let [x (dm/get-prop rect :x)
          y (dm/get-prop rect :y)
          w (dm/get-prop rect :width)
          h (dm/get-prop rect :height)]
      (assoc rect
             :x1 x
             :y1 y
             :x2 (+ x w)
             :y2 (+ y h)))))

(defn update-rect!
  [rect type]
  (case type
    (:size :position)
    (let [x (dm/get-prop rect :x)
          y (dm/get-prop rect :y)
          w (dm/get-prop rect :width)
          h (dm/get-prop rect :height)]
      (assoc rect
             :x1 x
             :y1 y
             :x2 (+ x w)
             :y2 (+ y h)))

    :corners
    (let [x1 (dm/get-prop rect :x1)
          y1 (dm/get-prop rect :y1)
          x2 (dm/get-prop rect :x2)
          y2 (dm/get-prop rect :y2)]
      (assoc rect
             :x (mth/min x1 x2)
             :y (mth/min y1 y2)
             :width (mth/abs (- x2 x1))
             :height (mth/abs (- y2 y1))))))

(defn close-rect?
  [rect1 rect2]

  (dm/assert!
   "expected two rects"
   (and (rect? rect1)
        (rect? rect2)))

  (and ^boolean (mth/close? (dm/get-prop rect1 :x)
                            (dm/get-prop rect2 :x))
       ^boolean (mth/close? (dm/get-prop rect1 :y)
                            (dm/get-prop rect2 :y))
       ^boolean (mth/close? (dm/get-prop rect1 :width)
                            (dm/get-prop rect2 :width))
       ^boolean (mth/close? (dm/get-prop rect1 :height)
                            (dm/get-prop rect2 :height))))

(defn rect->points
  [rect]
  (dm/assert!
   "expected rect instance"
   (rect? rect))

  (let [x (dm/get-prop rect :x)
        y (dm/get-prop rect :y)
        w (dm/get-prop rect :width)
        h (dm/get-prop rect :height)]
    (when (d/num? x y)
      (let [w (mth/max w 0.01)
            h (mth/max h 0.01)]
        [(gpt/point x y)
         (gpt/point (+ x w) y)
         (gpt/point (+ x w) (+ y h))
         (gpt/point x (+ y h))]))))

(defn rect->point
  "Extract the position part of the rect"
  [rect]
  (gpt/point (dm/get-prop rect :x)
             (dm/get-prop rect :y)))

(defn rect->center
  [rect]
  (dm/assert! (rect? rect))
  (let [x (dm/get-prop rect :x)
        y (dm/get-prop rect :y)
        w (dm/get-prop rect :width)
        h (dm/get-prop rect :height)]
    (when (d/num? x y w h)
      (gpt/point (+ x (/ w 2.0))
                 (+ y (/ h 2.0))))))

(defn rect->lines
  [rect]
  (dm/assert! (rect? rect))

  (let [x (dm/get-prop rect :x)
        y (dm/get-prop rect :y)
        w (dm/get-prop rect :width)
        h (dm/get-prop rect :height)]
    (when (d/num? x y)
      (let [w (mth/max w 0.01)
            h (mth/max h 0.01)]
        [[(gpt/point x y) (gpt/point (+ x w) y)]
         [(gpt/point (+ x w) y) (gpt/point (+ x w) (+ y h))]
         [(gpt/point (+ x w) (+ y h)) (gpt/point x (+ y h))]
         [(gpt/point x (+ y h)) (gpt/point x y)]]))))

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
          (recur (double (mth/min minx x))
                 (double (mth/min miny y))
                 (double (mth/max maxx x))
                 (double (mth/max maxy y))
                 (rest pts)))
        (when (d/num? minx miny maxx maxy)
          (make-rect minx miny (- maxx minx) (- maxy miny)))))))

;; FIXME: measure performance
(defn bounds->rect
  [[pa pb pc pd]]
  (let [ax   (dm/get-prop pa :x)
        ay   (dm/get-prop pa :y)
        bx   (dm/get-prop pb :x)
        by   (dm/get-prop pb :y)
        cx   (dm/get-prop pc :x)
        cy   (dm/get-prop pc :y)
        dx   (dm/get-prop pd :x)
        dy   (dm/get-prop pd :y)
        minx (mth/min ax bx cx dx)
        miny (mth/min ay by cy dy)
        maxx (mth/max ax bx cx dx)
        maxy (mth/max ay by cy dy)]
    (when (d/num? minx miny maxx maxy)
      (make-rect minx miny (- maxx minx) (- maxy miny)))))

(def ^:private xf-keep-x (keep #(dm/get-prop % :x)))
(def ^:private xf-keep-y (keep #(dm/get-prop % :y)))
(def ^:private xf-keep-x2 (keep #(dm/get-prop % :x2)))
(def ^:private xf-keep-y2 (keep #(dm/get-prop % :y2)))

(defn squared-points
  [points]
  (when (d/not-empty? points)
    (let [minx (transduce xf-keep-x d/min ##Inf points)
          miny (transduce xf-keep-y d/min ##Inf points)
          maxx (transduce xf-keep-x2 d/max ##-Inf points)
          maxy (transduce xf-keep-y2 d/max ##-Inf points)]
      (when (d/num? minx miny maxx maxy)
        [(gpt/point minx miny)
         (gpt/point maxx miny)
         (gpt/point maxx maxy)
         (gpt/point minx maxy)]))))

(defn join-rects [rects]
  (when (seq rects)
    (let [minx (transduce xf-keep-x d/min ##Inf rects)
          miny (transduce xf-keep-y d/min ##Inf rects)
          maxx (transduce xf-keep-x2 d/max ##-Inf rects)
          maxy (transduce xf-keep-y2 d/max ##-Inf rects)]
      (when (d/num? minx miny maxx maxy)
        (make-rect minx miny (- maxx minx) (- maxy miny))))))

(defn center->rect
  ([point size]
   (center->rect point size size))
  ([point w h]
   (when (some? point)
     (let [x (dm/get-prop point :x)
           y (dm/get-prop point :y)]
       (when (d/num? x y w h)
         (make-rect (- x (/ w 2))
                    (- y (/ h 2))
                    w
                    h))))))

(defn s=
  [a b]
  (mth/almost-zero? (- a b)))

(defn overlaps-rects?
  "Check for two rects to overlap. Rects won't overlap only if
   one of them is fully to the left or the top"
  [rect-a rect-b]
  (let [x1a (dm/get-prop rect-a :x)
        y1a (dm/get-prop rect-a :y)
        x2a (+ x1a (dm/get-prop rect-a :width))
        y2a (+ y1a (dm/get-prop rect-a :height))

        x1b (dm/get-prop rect-b :x)
        y1b (dm/get-prop rect-b :y)
        x2b (+ x1b (dm/get-prop rect-b :width))
        y2b (+ y1b (dm/get-prop rect-b :height))]

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

(defn contains-rect?
  "Check if a rect srb is contained inside sra"
  [sra srb]
  (let [ax1 (dm/get-prop sra :x1)
        ax2 (dm/get-prop sra :x2)
        ay1 (dm/get-prop sra :y1)
        ay2 (dm/get-prop sra :y2)
        bx1 (dm/get-prop srb :x1)
        bx2 (dm/get-prop srb :x2)
        by1 (dm/get-prop srb :y1)
        by2 (dm/get-prop srb :y2)]
    (and (>= bx1 ax1)
         (<= bx2 ax2)
         (>= by1 ay1)
         (<= by2 ay2))))

(defn corners->rect
  ([p1 p2]
   (corners->rect (:x p1) (:y p1) (:x p2) (:y p2)))
  ([xp1 yp1 xp2 yp2]
   (make-rect (mth/min xp1 xp2)
              (mth/min yp1 yp2)
              (abs (- xp1 xp2))
              (abs (- yp1 yp2)))))

(defn clip-rect
  [selrect bounds]
  (when (rect? selrect)
    (dm/assert! (rect? bounds))
    (let [x1  (dm/get-prop selrect :x1)
          y1  (dm/get-prop selrect :y1)
          x2  (dm/get-prop selrect :x2)
          y2  (dm/get-prop selrect :y2)
          bx1 (dm/get-prop bounds :x1)
          by1 (dm/get-prop bounds :y1)
          bx2 (dm/get-prop bounds :x2)
          by2 (dm/get-prop bounds :y2)]
      (corners->rect (mth/max bx1 x1)
                     (mth/max by1 y1)
                     (mth/min bx2 x2)
                     (mth/min by2 y2)))))
(defn fix-aspect-ratio
  [bounds aspect-ratio]
  (if aspect-ratio
    (let [width (dm/get-prop bounds :width)
          height (dm/get-prop bounds :height)
          target-height (* width aspect-ratio)
          target-width (* height (/ 1 aspect-ratio))]
      (cond-> bounds
        (> target-height height)
        (-> (assoc :height target-height)
            (update :y - (/ (- target-height height) 2)))

        (< target-height height)
        (-> (assoc :width target-width)
            (update :x - (/ (- target-width width) 2)))))
    bounds))

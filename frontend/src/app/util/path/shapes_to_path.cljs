;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.shapes-to-path
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.util.path.commands :as pc]))

(def bezier-circle-c 0.551915024494)
(def dissoc-attrs [:x :y :width :height
                   :rx :ry :r1 :r2 :r3 :r4
                   :medata])
(def allowed-transform-types #{:rect
                               :circle
                               :image})

(defn make-corner-arc
  "Creates a curvle corner for border radius"
  [from to corner radius]
  (let [x (case corner
            :top-left (:x from)
            :top-right (- (:x from) radius)
            :bottom-right (- (:x to) radius)
            :bottom-left (:x to))

        y (case corner
            :top-left (- (:y from) radius)
            :top-right (:y from)
            :bottom-right (- (:y to) (* 2 radius))
            :bottom-left (- (:y to) radius))

        width  (* radius 2)
        height (* radius 2)

        c   bezier-circle-c
        c1x (+ x (* (/ width 2)  (- 1 c)))
        c2x (+ x (* (/ width 2)  (+ 1 c)))
        c1y (+ y (* (/ height 2) (- 1 c)))
        c2y (+ y (* (/ height 2) (+ 1 c)))

        h1 (case corner
             :top-left     (assoc from :y c1y)
             :top-right    (assoc from :x c2x)
             :bottom-right (assoc from :y c2y)
             :bottom-left  (assoc from :x c1x))

        h2 (case corner
             :top-left     (assoc to :x c1x)
             :top-right    (assoc to :y c1y)
             :bottom-right (assoc to :x c2x)
             :bottom-left  (assoc to :y c2y))]

    (pc/make-curve-to to h1 h2)))

(defn circle->path
  "Creates the bezier curves to approximate a circle shape"
  [x y width height]
  (let [mx (+ x (/ width 2))
        my (+ y (/ height 2))
        ex (+ x width)
        ey (+ y height)

        p1  (gpt/point mx y)
        p2  (gpt/point ex my)
        p3  (gpt/point mx ey)
        p4  (gpt/point x my)

        c   bezier-circle-c
        c1x (+ x (* (/ width 2)  (- 1 c)))
        c2x (+ x (* (/ width 2)  (+ 1 c)))
        c1y (+ y (* (/ height 2) (- 1 c)))
        c2y (+ y (* (/ height 2) (+ 1 c)))]

    [(pc/make-move-to p1)
     (pc/make-curve-to p2 (assoc p1 :x c2x) (assoc p2 :y c1y))
     (pc/make-curve-to p3 (assoc p2 :y c2y) (assoc p3 :x c2x))
     (pc/make-curve-to p4 (assoc p3 :x c1x) (assoc p4 :y c2y))
     (pc/make-curve-to p1 (assoc p4 :y c1y) (assoc p1 :x c1x))]))

(defn rect->path
  "Creates a bezier curve that approximates a rounded corner rectangle"
  [x y width height r1 r2 r3 r4]
  (let [p1 (gpt/point x (+ y r1))
        p2 (gpt/point (+ x r1) y)

        p3 (gpt/point (+ width x (- r2)) y)
        p4 (gpt/point (+ width x) (+ y r2))

        p5 (gpt/point (+ width x) (+ height y (- r3)))
        p6 (gpt/point (+ width x (- r3)) (+ height y))

        p7 (gpt/point (+ x r4) (+ height y))
        p8 (gpt/point x (+ height y (- r4)))]
    (-> []
        (conj (pc/make-move-to p1))
        (cond-> (not= p1 p2)
          (conj (make-corner-arc p1 p2 :top-left r1)))
        (conj (pc/make-line-to p3))
        (cond-> (not= p3 p4)
          (conj (make-corner-arc p3 p4 :top-right r2)))
        (conj (pc/make-line-to p5))
        (cond-> (not= p5 p6)
          (conj (make-corner-arc p5 p6 :bottom-right r3)))
        (conj (pc/make-line-to p7))
        (cond-> (not= p7 p8)
          (conj (make-corner-arc p7 p8 :bottom-left r4)))
        (conj (pc/make-line-to p1)))))

(defn convert-to-path
  "Transforms the given shape to a path"
  [{:keys [type x y width height r1 r2 r3 r4 rx metadata] :as shape}]

  (if (contains? allowed-transform-types type)
    (let [r1 (or r1 rx 0)
          r2 (or r2 rx 0)
          r3 (or r3 rx 0)
          r4 (or r4 rx 0)

          new-content
          (case type
            :circle
            (circle->path x y width height)
            (rect->path x y width height r1 r2 r3 r4))

          ;; Apply the transforms that had the shape
          transform (:transform shape)
          new-content (cond-> new-content
                        (some? transform)
                        (gsp/transform-content (gmt/transform-in (gsh/center-shape shape) transform)))]

      (-> shape
          (d/without-keys dissoc-attrs)
          (assoc :type :path)
          (assoc :content new-content)
          (cond-> (= :image type)
            (assoc :fill-image metadata))))
    ;; Do nothing if the shape is not of a correct type
    shape))


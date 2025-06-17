;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.shape-to-path
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.corners :as gso]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as path.impl]
   [app.common.types.path.segment :as segm]
   [app.common.types.shape.radius :as ctsr]))

(def ^:const ^:private bezier-circle-c
  0.551915024494)

(def ^:private dissoc-attrs
  [:x :y :width :height
   :rx :ry :r1 :r2 :r3 :r4
   :metadata])

(defn without-position-attrs
  [shape]
  (d/without-keys shape dissoc-attrs))

(defn- make-corner-arc
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

    (helpers/make-curve-to to h1 h2)))

(defn- circle->path
  "Creates the bezier curves to approximate a circle shape"
  [{:keys [x y width height]}]
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

    [(helpers/make-move-to p1)
     (helpers/make-curve-to p2 (assoc p1 :x c2x) (assoc p2 :y c1y))
     (helpers/make-curve-to p3 (assoc p2 :y c2y) (assoc p3 :x c2x))
     (helpers/make-curve-to p4 (assoc p3 :x c1x) (assoc p4 :y c2y))
     (helpers/make-curve-to p1 (assoc p4 :y c1y) (assoc p1 :x c1x))]))

(defn- draw-rounded-rect-path
  ([x y width height r]
   (draw-rounded-rect-path x y width height r r r r))

  ([x y width height r1 r2 r3 r4]
   (let [p1 (gpt/point x (+ y r1))
         p2 (gpt/point (+ x r1) y)

         p3 (gpt/point (+ width x (- r2)) y)
         p4 (gpt/point (+ width x) (+ y r2))

         p5 (gpt/point (+ width x) (+ height y (- r3)))
         p6 (gpt/point (+ width x (- r3)) (+ height y))

         p7 (gpt/point (+ x r4) (+ height y))
         p8 (gpt/point x (+ height y (- r4)))]
     (-> []
         (conj (helpers/make-move-to p1))
         (cond-> (not= p1 p2)
           (conj (make-corner-arc p1 p2 :top-left r1)))
         (conj (helpers/make-line-to p3))
         (cond-> (not= p3 p4)
           (conj (make-corner-arc p3 p4 :top-right r2)))
         (conj (helpers/make-line-to p5))
         (cond-> (not= p5 p6)
           (conj (make-corner-arc p5 p6 :bottom-right r3)))
         (conj (helpers/make-line-to p7))
         (cond-> (not= p7 p8)
           (conj (make-corner-arc p7 p8 :bottom-left r4)))
         (conj (helpers/make-line-to p1))))))

(defn- rect->path
  "Creates a bezier curve that approximates a rounded corner rectangle"
  [{:keys [x y width height] :as shape}]
  (case (ctsr/radius-mode shape)
    :radius-1
    (let [radius (gso/shape-corners-1 shape)]
      (draw-rounded-rect-path x y width height radius))

    :radius-4
    (let [[r1 r2 r3 r4] (gso/shape-corners-4 shape)]
      (draw-rounded-rect-path x y width height r1 r2 r3 r4))

    []))

(declare convert-to-path)

;; FIXME: this looks unnecesary because penpot already normalizes all
;; path content to be absolute. There are no relative segments on
;; penpot.
(defn- fix-first-relative
  "Fix an issue with the simplify commands not changing the first relative"
  [content]
  (let [head (first content)]
    (cond-> content
      (and head (:relative head))
      (update 0 assoc :relative false))))

(defn- group-to-path
  [group objects]
  (let [xform (comp (map (d/getf objects))
                    (map #(convert-to-path % objects)))

        child-as-paths (into [] xform (:shapes group))
        head (peek child-as-paths)
        head-data (select-keys head bool/style-properties)
        content (into []
                      (comp (filter cfh/path-shape?)
                            (map :content)
                            (map vec)
                            (mapcat fix-first-relative))
                      child-as-paths)]
    (-> group
        (assoc :type :path)
        (assoc :content content)
        (merge head-data)
        (d/without-keys dissoc-attrs))))

(defn- bool-to-path
  [shape objects]

  (let [children
        (->> (:shapes shape)
             (map (d/getf objects))
             (map #(convert-to-path % objects)))

        bool-type
        (:bool-type shape)

        content
        (bool/calculate-content bool-type (map :content children))]

    (-> shape
        (assoc :type :path)
        (assoc :content content)
        (dissoc :bool-type)
        (d/without-keys dissoc-attrs))))

(defn convert-to-path
  "Transforms the given shape to a path shape"
  [shape objects]
  (assert (map? objects))
  ;; FIXME: add check-objects-like
  ;; FIXME: add check-shape ?

  (let [type (dm/get-prop shape :type)]

    (case type
      (:group :frame)
      (group-to-path shape objects)

      :bool
      (bool-to-path shape objects)

      (:rect :circle :image :text)
      (let [content
            (if (= type :circle)
              (circle->path shape)
              (rect->path shape))

            content
            (path.impl/from-plain content)

            ;; Apply the transforms that had the shape
            transform
            (cond-> (:transform shape (gmt/matrix))
              (:flip-x shape) (gmt/scale (gpt/point -1 1))
              (:flip-y shape) (gmt/scale (gpt/point 1 -1)))

            content
            (cond-> content
              (some? transform)
              (segm/transform-content (gmt/transform-in (gco/shape->center shape) transform)))]

        (-> shape
            (assoc :type :path)
            (assoc :content content)
            (cond-> (= :image type)
              (assoc :fill-image (get shape :metadata)))
            (d/without-keys dissoc-attrs)))

      ;; For the rest return the plain shape
      shape)))

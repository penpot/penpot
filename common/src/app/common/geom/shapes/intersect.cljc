;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.intersect
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.text :as gte]
   [app.common.math :as mth]
   [app.common.types.path.segment :as path.segm]))

(defn orientation
  "Given three ordered points gives the orientation
  (clockwise, counterclock or coplanar-line)"
  [p1 p2 p3]
  (let [{x1 :x y1 :y} p1
        {x2 :x y2 :y} p2
        {x3 :x y3 :y} p3
        v (- (* (- y2 y1) (- x3 x2))
             (* (- y3 y2) (- x2 x1)))]
    (cond
      (pos? v) ::clockwise
      (neg? v) ::counter-clockwise
      :else    ::coplanar)))

(defn on-segment?
  "Given three colinear points p, q, r checks if q lies on segment pr"
  [{qx :x qy :y} {px :x py :y} {rx :x ry :y}]
  (and (<= qx (mth/max px rx))
       (>= qx (mth/min px rx))
       (<= qy (mth/max py ry))
       (>= qy (mth/min py ry))))

;; Based on solution described here
;; https://www.geeksforgeeks.org/check-if-two-given-line-segments-intersect/
(defn intersect-segments?
  "Given two segments A<pa1,pa2> and B<pb1,pb2> defined by two points.
  Checks if they intersects."
  [[p1 q1] [p2 q2]]
  (let [o1 (orientation p1 q1 p2)
        o2 (orientation p1 q1 q2)
        o3 (orientation p2 q2 p1)
        o4 (orientation p2 q2 q1)]

    (or
     ;; General case
     (and (not= o1 o2) (not= o3 o4))

     ;; p1, q1 and p2 colinear and p2 lies on p1q1
     (and (= o1 :coplanar) ^boolean (on-segment? p2 p1 q1))

     ;; p1, q1 and q2 colinear and q2 lies on p1q1
     (and (= o2 :coplanar) ^boolean (on-segment? q2 p1 q1))

     ;; p2, q2 and p1 colinear and p1 lies on p2q2
     (and (= o3 :coplanar) ^boolean (on-segment? p1 p2 q2))

     ;; p2, q2 and p1 colinear and q1 lies on p2q2
     (and (= o4 :coplanar) ^boolean (on-segment? q1 p2 q2)))))

(defn points->lines
  "Given a set of points for a polygon will return
  the lines that define it"
  ([points]
   (points->lines points true))

  ([points closed?]
   (map vector points
        (cond-> (rest points)
          (true? closed?)
          (concat (list (first points)))))))

(defn intersects-lines?
  "Checks if two sets of lines intersect in any point"
  [lines-a lines-b]

  (loop [cur-line (first lines-a)
         pending  (rest lines-a)]
    (if-not cur-line
      ;; There is no line intersecting polygon b
      false

      ;; Check if any of the segments intersect
      (if (->> lines-b
               (some #(intersect-segments? cur-line %)))
        true
        (recur (first pending) (rest pending))))))

(defn intersect-ray?
  "Checks the intersection between segment qr and a ray
  starting in point p with an angle of 0 degrees"
  [{px :x py :y} [{x1 :x y1 :y} {x2 :x y2 :y}]]

  (if (or (and (<= y1 py) (>  y2 py))
          (and (>  y1 py) (<= y2 py)))

    ;; calculate the edge-ray intersect x-coord
    (let [vt (/ (- py y1) (- y2 y1))
          ix (+ x1 (* vt (- x2 x1)))]
      (< px ix))

    false))

(defn is-point-inside-evenodd?
  "Check if the point P is inside the polygon defined by `points`"
  [p lines]
  ;; Even-odd algorithm
  ;; Cast a ray from the point in any direction and count the intersections
  ;; if it's odd the point is inside the polygon
  (->> lines
       (filterv #(intersect-ray? p %))
       (count)
       (odd?)))

(defn- next-windup
  "Calculates the next windup number for the nonzero algorithm"
  [wn {px :x py :y} [{x1 :x y1 :y} {x2 :x y2 :y}]]

  (let [line-side (- (* (- x2 x1) (- py y1))
                     (* (- px x1) (- y2 y1)))]
    (if (<= y1 py)
      ;; Upward crossing
      (if (and  (> y2 py) (> line-side 0)) (inc wn) wn)

      ;; Downward crossing
      (if (and (<= y2 py) (< line-side 0)) (dec wn) wn))))

(defn is-point-inside-nonzero?
  "Check if the point P is inside the polygon defined by `points`"
  [p lines]
  ;; Non-zero winding number
  ;; Calculates the winding number

  (loop [wn 0
         line  (first lines)
         lines (rest lines)]

    (if line
      (let [wn (next-windup wn p line)]
        (recur wn (first lines) (rest lines)))
      (not= wn 0))))

;; A intersects with B
;; Three possible cases:
;;   1) A is inside of B
;;   2) B is inside of A
;;   3) A intersects B
;;   4) A is outside of B
;;
;; . check point in A is inside B => case 1 or 3 otherwise discard 1
;; . check point in B is inside A => case 2 or 3 otherwise discard 2
;; . check if intersection otherwise case 4
;;
(defn overlaps-rect-points?
  "Checks if the given rect intersects with the selrect"
  [rect points]

  (let [rect-points  (grc/rect->points rect)
        rect-lines   (points->lines rect-points)
        points-lines (points->lines points)]

    (or (is-point-inside-evenodd? (first rect-points) points-lines)
        (is-point-inside-evenodd? (first points) rect-lines)
        (intersects-lines? rect-lines points-lines))))

(defn overlaps-path?
  "Checks if the given rect overlaps with the path in any point"
  [shape rect include-content?]

  (when (d/not-empty? (:content shape))
    (let [;; If paths are too complex the intersection is too expensive
          ;; we fallback to check its bounding box otherwise the performance penalty
          ;; is too big
          ;; TODO: Look for ways to optimize this operation
          simple? (> (count (:content shape)) 100)

          rect-points  (grc/rect->points rect)
          rect-lines   (points->lines rect-points)
          path-lines   (if simple?
                         (points->lines (:points shape))
                         (path.segm/path->lines shape))
          start-point (-> shape :content (first) :params (gpt/point))]

      (or (intersects-lines? rect-lines path-lines)
          (if include-content?
            (or (is-point-inside-nonzero? (first rect-points) path-lines)
                (is-point-inside-nonzero? start-point rect-lines))
            false)))))

(defn is-point-inside-ellipse?
  "checks if a point is inside an ellipse"
  [point {:keys [cx cy rx ry transform]}]

  (let [center (gpt/point cx cy)
        transform (when (some? transform) (gmt/transform-in center transform))
        {px :x py :y} (if (some? transform) (gpt/transform point transform) point)
        ;; Ellipse inequality formula
        ;; https://en.wikipedia.org/wiki/Ellipse#Shifted_ellipse
        v (+ (/ (mth/sq (- px cx))
                (mth/sq rx))
             (/ (mth/sq (- py cy))
                (mth/sq ry)))]
    (<= v 1)))

(defn intersects-line-ellipse?
  "Checks whether a single line intersects with the given ellipse"
  [[{x1 :x y1 :y} {x2 :x y2 :y}] {:keys [cx cy rx ry]}]

  ;; Given the ellipse inequality after inserting the line parametric equations
  ;; we resolve t and gives us a quadratic formula
  ;; The result of this quadratic will give us a value of T that needs to be
  ;; between 0-1 to be in the segment

  (let [a (+ (/ (mth/sq (- x2 x1))
                (mth/sq rx))
             (/ (mth/sq (- y2 y1))
                (mth/sq ry)))

        b (+ (/ (- (* 2 x1 (- x2 x1))
                   (* 2 cx (- x2 x1)))
                (mth/sq rx))
             (/ (- (* 2 y1 (- y2 y1))
                   (* 2 cy (- y2 y1)))
                (mth/sq ry)))

        c (+ (/ (+ (mth/sq x1)
                   (mth/sq cx)
                   (* -2 x1 cx))
                (mth/sq rx))
             (/ (+ (mth/sq y1)
                   (mth/sq cy)
                   (* -2 y1 cy))
                (mth/sq ry))
             -1)

        ;; B^2 - 4AC
        determ (- (mth/sq b) (* 4 a c))]

    (if (mth/almost-zero? a)
      ;; If a=0 we need to calculate the linear solution
      (when-not (mth/almost-zero? b)
        (let [t (/ (- c) b)]
          (and (>= t 0) (<= t 1))))

      (when (>= determ 0)
        (let [t1 (/ (+ (- b) (mth/sqrt determ)) (* 2 a))
              t2 (/ (- (- b) (mth/sqrt determ)) (* 2 a))]
          (or (and (>= t1 0) (<= t1 1))
              (and (>= t2 0) (<= t2 1))))))))

(defn intersects-lines-ellipse?
  "Checks if a set of lines intersect with an ellipse in any point"
  [rect-lines {:keys [cx cy transform] :as ellipse-data}]
  (let [center (gpt/point cx cy)
        transform (when (some? transform) (gmt/transform-in center transform))]
    (some (fn [[p1 p2]]
            (let [p1 (if (some? transform) (gpt/transform p1 transform) p1)
                  p2 (if (some? transform) (gpt/transform p2 transform) p2)]
              (intersects-line-ellipse? [p1 p2] ellipse-data))) rect-lines)))

(defn overlaps-ellipse?
  "Checks if the given rect overlaps with an ellipse"
  [shape rect]

  (let [rect-points  (grc/rect->points rect)
        rect-lines   (points->lines rect-points)
        {:keys [x y width height]} shape

        center (gpt/point (+ x (/ width 2))
                          (+ y (/ height 2)))

        ellipse-data {:cx (:x center)
                      :cy (:y center)
                      :rx (/ width 2)
                      :ry (/ height 2)
                      :transform (:transform-inverse shape)}]

    (or (is-point-inside-evenodd? center rect-lines)
        (is-point-inside-ellipse? (first rect-points) ellipse-data)
        (intersects-lines-ellipse? rect-lines ellipse-data))))

(defn overlaps-text?
  [{:keys [position-data] :as shape} rect]

  (if (and (some? position-data) (d/not-empty? position-data))
    (let [center (gco/shape->center shape)

          transform-rect
          (fn [rect-points]
            (gco/transform-points rect-points center (:transform shape)))]

      (->> position-data
           (map (comp transform-rect
                      grc/rect->points
                      gte/position-data->rect))
           (some #(overlaps-rect-points? rect %))))
    (overlaps-rect-points? rect (:points shape))))

(defn overlaps?
  "General case to check for overlapping between shapes and a rectangle"
  [shape rect]
  (let [swidth (/ (or (:stroke-width shape) 0) 2)
        rect   (-> rect
                   (update :x - swidth)
                   (update :y - swidth)
                   (update :width + (* 2 swidth))
                   (update :height + (* 2 swidth)))]
    (or (not shape)
        (cond
          (cfh/path-shape? shape)
          (and (overlaps-rect-points? rect (:points shape))
               (overlaps-path? shape rect true))

          (cfh/circle-shape? shape)
          (and (overlaps-rect-points? rect (:points shape))
               (overlaps-ellipse? shape rect))

          (cfh/text-shape? shape)
          (overlaps-text? shape rect)

          :else
          (overlaps-rect-points? rect (:points shape))))))

(defn has-point-rect?
  [rect point]
  (let [lines (grc/rect->lines rect)]
    (is-point-inside-evenodd? point lines)))

(defn slow-has-point?
  [shape point]
  (let [lines (points->lines (dm/get-prop shape :points))]
    (is-point-inside-evenodd? point lines)))

(defn fast-has-point?
  [shape point]
  (let [x1 (dm/get-prop shape :x)
        y1 (dm/get-prop shape :y)
        x2 (+ x1 (dm/get-prop shape :width))
        y2 (+ y1 (dm/get-prop shape :height))
        px (dm/get-prop point :x)
        py (dm/get-prop point :y)]
    (and (>= px x1)
         (<= px x2)
         (>= py y1)
         (<= py y2))))

(defn has-point?
  [shape point]
  (if (or ^boolean (cfh/path-shape? shape)
          ^boolean (cfh/bool-shape? shape)
          ^boolean (cfh/circle-shape? shape))
    (slow-has-point? shape point)
    (fast-has-point? shape point)))

(defn rect-contains-shape?
  [rect shape]
  (->> shape
       :points
       (every? (partial has-point-rect? rect))))


(defn line-line-intersect
  "Calculates the interesection point for two lines given by the points a-b and b-c"
  [a b c d]

  (let [;; Line equation representation: ax + by + c = 0
        a1 (- (:y b) (:y a))
        b1 (- (:x a) (:x b))
        c1 (+ (* a1 (:x a)) (* b1 (:y a)))

        a2 (- (:y d) (:y c))
        b2 (- (:x c) (:x d))
        c2 (+ (* a2 (:x c)) (* b2 (:y c)))

        ;; Cramer's rule
        det (- (* a1 b2) (* a2 b1))
        det (if (mth/almost-zero? det) 0.001 det)

        x (/ (- (* b2 c1) (* b1 c2)) det)
        y (/ (- (* c2 a1) (* c1 a2)) det)]

    (gpt/point x y)))

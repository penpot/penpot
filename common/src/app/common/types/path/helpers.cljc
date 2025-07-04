;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.helpers
  "A collection of path internal helpers that does not depend on other
  path related namespaces.

  This NS allows separate context-less/dependency-less helpers from
  other path related namespaces and make proper domain-specific
  namespaces without incurrying on circular depedency cycles."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]))

(def ^:const curve-curve-precision 0.1)
(def ^:const curve-range-precision 2)

(defn s= [a b]
  (mth/almost-zero? (- a b)))

(defn make-move-to [to]
  {:command :move-to
   :params {:x (:x to)
            :y (:y to)}})

(defn make-line-to [to]
  {:command :line-to
   :params {:x (:x to)
            :y (:y to)}})

(defn make-curve-params
  ([point]
   (make-curve-params point point point))
  ([point handler]
   (make-curve-params point handler point))
  ([point h1 h2]
   {:x (:x point)
    :y (:y point)
    :c1x (:x h1)
    :c1y (:y h1)
    :c2x (:x h2)
    :c2y (:y h2)}))

(defn update-curve-to
  [command h1 h2]
  (let [params {:x (-> command :params :x)
                :y (-> command :params :y)
                :c1x (:x h1)
                :c1y (:y h1)
                :c2x (:x h2)
                :c2y (:y h2)}]
    (-> command
        (assoc :command :curve-to)
        (assoc :params params))))

(defn make-curve-to
  [to h1 h2]
  {:command :curve-to
   :params (make-curve-params to h1 h2)})

(defn prefix->coords [prefix]
  (case prefix
    :c1 [:c1x :c1y]
    :c2 [:c2x :c2y]
    nil))

(defn- closest-angle
  [angle]
  (cond
    (or  (> angle 337.5)  (<= angle 22.5))  0
    (and (> angle 22.5)   (<= angle 67.5))  45
    (and (> angle 67.5)   (<= angle 112.5)) 90
    (and (> angle 112.5)	(<= angle 157.5)) 135
    (and (> angle 157.5)	(<= angle 202.5)) 180
    (and (> angle 202.5)	(<= angle 247.5)) 225
    (and (> angle 247.5)	(<= angle 292.5)) 270
    (and (> angle 292.5)	(<= angle 337.5)) 315))

(defn position-fixed-angle
  [point from-point]
  (if (and from-point point)
    (let [angle (mod (+ 360 (- (gpt/angle point from-point))) 360)
          to-angle (closest-angle angle)
          distance (gpt/distance point from-point)]
      (gpt/angle->point from-point (mth/radians to-angle) distance))
    point))

(defn segment->point
  ([segment] (segment->point segment :x))
  ([segment coord]
   (when-let [params (not-empty (get segment :params))]
     (case coord
       :c1 (gpt/point (get params :c1x)
                      (get params :c1y))
       :c2 (gpt/point (get params :c2x)
                      (get params :c2y))
       (gpt/point (get params :x)
                  (get params :y))))))

(defn command->line
  ([segment]
   (command->line segment (:prev segment)))
  ([segment prev]
   [prev (segment->point segment)]))

(defn command->bezier
  ([segment]
   (command->bezier segment (:prev segment)))
  ([segment prev]
   [prev
    (segment->point segment)
    (gpt/point (-> segment :params :c1x) (-> segment :params :c1y))
    (gpt/point (-> segment :params :c2x) (-> segment :params :c2y))]))

(declare curve-extremities)
(declare curve-values)

(defn command->selrect
  ([command]
   (command->selrect command (:prev command)))

  ([command prev-point]
   (let [points (case (:command command)
                  :move-to [(segment->point command)]

                  ;; If it's a line we add the beginning point and endpoint
                  :line-to [prev-point (segment->point command)]

                  ;; We return the bezier extremities
                  :curve-to (into [prev-point (segment->point command)]
                                  (let [curve [prev-point
                                               (segment->point command)
                                               (segment->point command :c1)
                                               (segment->point command :c2)]]
                                    (->> (curve-extremities curve)
                                         (mapv #(curve-values curve %)))))
                  [])]
     (grc/points->rect points))))

(defn line-values
  [[from-p to-p] t]
  (let [move-v (-> (gpt/to-vec from-p to-p)
                   (gpt/scale t))]
    (gpt/add from-p move-v)))

(defn line-windup
  [[from-p to-p :as l] t]
  (let [p (line-values l t)
        cy (:y p)
        ay (:y to-p)
        by (:y from-p)]
    (cond
      (and (> (- cy ay) 0) (not (s= cy ay)))  1
      (and (< (- cy ay) 0) (not (s= cy ay))) -1
      (< (- cy by) 0)  1
      (> (- cy by) 0) -1
      :else            0)))

;; https://medium.com/@Acegikmo/the-ever-so-lovely-b%C3%A9zier-curve-eb27514da3bf
;; https://en.wikipedia.org/wiki/Bernstein_polynomial
(defn curve-values
  "Parametric equation for cubic beziers. Given a start and end and
  two intermediate points returns points for values of t.
  If you draw t on a plane you got the bezier cube"
  ([[start end h1 h2] t]
   (curve-values start end h1 h2 t))

  ([start end h1 h2 t]
   (let [t2 (* t t) ;; t square
         t3 (* t2 t) ;; t cube

         start-v (+ (- t3) (* 3 t2) (* -3 t) 1)
         h1-v    (+ (* 3 t3) (* -6 t2) (* 3 t))
         h2-v    (+ (* -3 t3) (* 3 t2))
         end-v   t3

         coord-v (fn [coord]
                   (+ (* (coord start) start-v)
                      (* (coord h1)    h1-v)
                      (* (coord h2)    h2-v)
                      (* (coord end)   end-v)))]

     (gpt/point (coord-v :x) (coord-v :y)))))

(defn solve-roots*
  "Solvers a quadratic or cubic equation given by the parameters a b c d.

  Implemented as reduction algorithm (this helps implemement
  derivative algorithms that does not require intermediate results
  thanks to transducers."
  [result conj a b c d]
  (let [sqrt-b2-4ac (mth/sqrt (- (* b b) (* 4 a c)))]
    (cond
      ;; No solutions
      (and ^boolean (mth/almost-zero? d)
           ^boolean (mth/almost-zero? a)
           ^boolean (mth/almost-zero? b))
      result

      ;; Linear solution
      (and ^boolean (mth/almost-zero? d)
           ^boolean (mth/almost-zero? a))
      (conj result (/ (- c) b))

      ;; Quadratic
      ^boolean
      (mth/almost-zero? d)
      (-> result
          (conj (/ (+ (- b) sqrt-b2-4ac)
                   (* 2 a)))
          (conj (/ (- (- b) sqrt-b2-4ac)
                   (* 2 a))))

      ;; Cubic
      :else
      (let [a (/ a d)
            b (/ b d)
            c (/ c d)

            p (/ (- (* 3 b) (* a a)) 3)
            q (/ (+ (* 2 a a a) (* -9 a b) (* 27 c)) 27)

            p3 (/ p 3)
            q2 (/ q 2)
            discriminant (+ (* q2 q2) (* p3 p3 p3))]

        (cond
          (< discriminant 0)
          (let [mp3 (/ (- p) 3)
                mp33 (* mp3 mp3 mp3)
                r (mth/sqrt mp33)
                t (/ (- q) (* 2 r))
                cosphi (cond (< t -1) -1
                             (> t 1) 1
                             :else t)
                phi (mth/acos cosphi)
                crtr (mth/cubicroot r)
                t1 (* 2 crtr)
                root1 (- (* t1 (mth/cos (/ phi 3))) (/ a 3))
                root2 (- (* t1 (mth/cos (/ (+ phi (* 2 mth/PI)) 3))) (/ a 3))
                root3 (- (* t1 (mth/cos (/ (+ phi (* 4 mth/PI)) 3))) (/ a 3))]

            (-> result
                (conj root1)
                (conj root2)
                (conj root3)))

          ^boolean
          (mth/almost-zero? discriminant)
          (let [u1 (if (< q2 0) (mth/cubicroot (- q2)) (- (mth/cubicroot q2)))
                root1 (- (* 2 u1) (/ a 3))
                root2 (- (- u1) (/ a 3))]
            (-> result
                (conj root1)
                (conj root2)))

          :else
          (let [sd (mth/sqrt discriminant)
                u1 (mth/cubicroot (- sd q2))
                v1 (mth/cubicroot (+ sd q2))
                root (- u1 v1 (/ a 3))]
            (conj result root)))))))





;; https://trans4mind.com/personal_development/mathematics/polynomials/cubicAlgebra.htm
(defn- solve-roots
  "Solvers a quadratic or cubic equation given by the parameters a b c d"
  ([a b c] (solve-roots a b c 0))
  ([a b c d] (solve-roots* [] conj a b c d)))

;; https://pomax.github.io/bezierinfo/#extremities
(defn curve-extremities
  "Calculates the extremities by solving the first derivative for a cubic
  bezier and then solving the quadratic formula"
  ([[start end h1 h2]]
   (curve-extremities start end h1 h2))

  ([start end h1 h2]

   (let [coords [[(:x start) (:x h1) (:x h2) (:x end)]
                 [(:y start) (:y h1) (:y h2) (:y end)]]

         coord->tvalue
         (fn [[c0 c1 c2 c3]]
           (let [a (+ (* -3 c0) (*   9 c1) (* -9 c2) (* 3 c3))
                 b (+ (*  6 c0) (* -12 c1) (* 6 c2))
                 c (+ (*  3 c1) (*  -3 c0))]

             (solve-roots a b c)))]
     (->> coords
          (mapcat coord->tvalue)

          ;; Only values in the range [0, 1] are valid
          (filterv #(and (> % 0.01) (< % 0.99)))))))

(defn calculate-curve-extremities
  "Calculates the extremities by solving the first derivative for a
  cubic bezier and then solving the quadratic formula"
  [start end h1 h2]
  (let [start-x (dm/get-prop start :x)
        h1-x    (dm/get-prop h1 :x)
        h2-x    (dm/get-prop h2 :x)
        end-x   (dm/get-prop end :x)
        start-y (dm/get-prop start :y)
        h1-y    (dm/get-prop h1 :y)
        h2-y    (dm/get-prop h2 :y)
        end-y   (dm/get-prop end :y)

        xform
        (comp
         (filter #(and (> % 0.01) (< % 0.99)))
         (map (fn [t]
                (let [t2      (* t t)  ;; t square
                      t3      (* t2 t) ;; t cube
                      start-v (+ (- t3) (* 3 t2) (* -3 t) 1)
                      h1-v    (+ (* 3 t3) (* -6 t2) (* 3 t))
                      h2-v    (+ (* -3 t3) (* 3 t2))
                      end-v   t3]
                  (gpt/point
                   (+ (* start-x start-v)
                      (* h1-x    h1-v)
                      (* h2-x    h2-v)
                      (* end-x   end-v))
                   (+ (* start-y start-v)
                      (* h1-y    h1-v)
                      (* h2-y    h2-v)
                      (* end-y   end-v)))))))

        conj*
        (xform conj!)

        process-curve
        (fn [result c0 c1 c2 c3]
          (let [a (+ (* -3 c0) (*   9 c1) (* -9 c2) (* 3 c3))
                b (+ (*  6 c0) (* -12 c1) (* 6 c2))
                c (+ (*  3 c1) (*  -3 c0))]
            (solve-roots* result conj* a b c 0)))]

    (-> (transient [])
        (process-curve start-x h1-x h2-x end-x)
        (process-curve start-y h1-y h2-y end-y)
        (persistent!))))

(defn curve-tangent
  "Retrieve the tangent vector to the curve in the point `t`"
  [[start end h1 h2] t]

  (let [coords [[(:x start) (:x h1) (:x h2) (:x end)]
                [(:y start) (:y h1) (:y h2) (:y end)]]

        solve-derivative
        (fn [[c0 c1 c2 c3]]
          ;; Solve B'(t) given t to retrieve the value for the
          ;; first derivative
          (let [t2 (* t t)]
            (+ (* c0 (+ (* -3 t2) (*   6 t) -3))
               (* c1 (+ (*  9 t2) (* -12 t)  3))
               (* c2 (+ (* -9 t2) (*   6 t)))
               (* c3 (* 3 t2)))))

        [x y] (->> coords (mapv solve-derivative))

        ;; normalize value
        d (mth/hypot x y)]

    (if (mth/almost-zero? d)
      (gpt/point 0 0)
      (gpt/point (/ x d) (/ y d)))))

(defn curve-windup
  [curve t]

  (let [tangent (curve-tangent curve t)]
    (cond
      (> (:y tangent) 0) -1
      (< (:y tangent) 0)  1
      :else               0)))

(def ^:private ^:const num-segments 10)

(defn curve->lines
  "Transform the bezier curve given by the parameters into a series of straight lines
  defined by the constant num-segments"
  [start end h1 h2]
  (let [offset (/ 1 num-segments)
        tp (fn [t] (curve-values start end h1 h2 t))]
    (loop [from 0
           result []]

      (let [to (min 1 (+ from offset))
            line [(tp from) (tp to)]
            result (conj result line)]

        (if (>= to 1)
          result
          (recur to result))))))

(defn curve-split
  "Splits a curve into two at the given parametric value `t`.
  Calculates the Casteljau's algorithm intermediate points"
  ([[start end h1 h2] t]
   (curve-split start end h1 h2 t))

  ([start end h1 h2 t]
   (let [p1 (gpt/lerp start h1 t)
         p2 (gpt/lerp h1 h2 t)
         p3 (gpt/lerp h2 end t)
         p4 (gpt/lerp p1 p2 t)
         p5 (gpt/lerp p2 p3 t)
         sp (gpt/lerp p4 p5 t)]
     [[start sp  p1 p4]
      [sp    end p5 p3]])))

(defn split-line-to
  "Given a point and a line-to command will create a two new line-to commands
  that will split the original line into two given a value between 0-1"
  [from-p segment t-val]
  (let [to-p (segment->point segment)
        sp (gpt/lerp from-p to-p t-val)]
    [(make-line-to sp) segment]))

(defn split-curve-to
  "Given the point and a curve-to command will split the curve into two new
  curve-to commands given a value between 0-1"
  [from-p segment t-val]
  (let [params (:params segment)
        end (gpt/point (:x params) (:y params))
        h1 (gpt/point (:c1x params) (:c1y params))
        h2 (gpt/point (:c2x params) (:c2y params))
        [[_ to1 h11 h21]
         [_ to2 h12 h22]] (curve-split from-p end h1 h2 t-val)]
    [(make-curve-to to1 h11 h21)
     (make-curve-to to2 h12 h22)]))

(defn subcurve-range
  "Given a curve returns a new curve between the values t1-t2"
  ([[start end h1 h2] [t1 t2]]
   (subcurve-range start end h1 h2 t1 t2))

  ([[start end h1 h2] t1 t2]
   (subcurve-range start end h1 h2 t1 t2))

  ([start end h1 h2 t1 t2]
   ;; Make sure that t2 is greater than t1
   (let [[t1 t2] (if (< t1 t2) [t1 t2] [t2 t1])
         t2' (/ (- t2 t1) (- 1 t1))
         [_ curve'] (curve-split start end h1 h2 t1)]
     (first (curve-split curve' t2')))))

(defn split-line-to-ranges
  "Splits a line into several lines given the points in `values`
  for example (split-line-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the line into 4 lines"
  [from-p segment values]
  (let [values (->> values (filter #(and (> % 0) (< % 1))))]
    (if (empty? values)
      [segment]
      (let [to-p (segment->point segment)
            values-set (->> (conj values 1) (into (sorted-set)))]
        (->> values-set
             (mapv (fn [val]
                     (-> (gpt/lerp from-p to-p val)
                         #_(gpt/round 2)
                         (make-line-to)))))))))

(defn split-curve-to-ranges
  "Splits a curve into several curves given the points in `values`
  for example (split-curve-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the curve into 4 curves that draw the same curve"
  [from-p segment values]

  (let [values (->> values (filter #(and (> % 0) (< % 1))))]
    (if (empty? values)
      [segment]
      (let [to-p (segment->point segment)
            params (:params segment)
            h1 (gpt/point (:c1x params) (:c1y params))
            h2 (gpt/point (:c2x params) (:c2y params))

            values-set (->> (conj values 0 1) (into (sorted-set)))]

        (->> (d/with-prev values-set)
             (rest)
             (mapv
              (fn [[t1 t0]]
                (let [[_ to-p h1' h2'] (subcurve-range from-p to-p h1 h2 t0 t1)]
                  (make-curve-to (-> to-p #_(gpt/round 2)) h1' h2')))))))))


(defn- get-line-tval
  [[{x1 :x y1 :y} {x2 :x y2 :y}] {:keys [x y]}]
  (cond
    (and (s= x1 x2) (s= y1 y2))
    ##Inf

    (s= x1 x2)
    (/ (- y y1) (- y2 y1))

    :else
    (/ (- x x1) (- x2 x1))))

(defn- curve-range->rect
  [curve from-t to-t]

  (let [[from-p to-p :as curve] (subcurve-range curve from-t to-t)
        extremes (->> (curve-extremities curve)
                      (mapv #(curve-values curve %)))]
    (grc/points->rect (into [from-p to-p] extremes))))

(defn line-has-point?
  "Using the line equation we put the x value and check if matches with
  the given Y. If it does the point is inside the line"
  [point [from-p to-p]]
  (let [{x1 :x y1 :y} from-p
        {x2 :x y2 :y} to-p
        {px :x py :y} point

        m  (when-not (s= x1 x2) (/ (- y2 y1) (- x2 x1)))
        vy (when (some? m) (+ (* m px) (* (- m) x1) y1))]

    ;; If x1 = x2 there is no slope, to see if the point is in the line
    ;; only needs to check the x is the same
    (or (and (s= x1 x2) (s= px x1))
        (and (some? vy) (s= py vy)))))

(defn segment-has-point?
  "Using the line equation we put the x value and check if matches with
  the given Y. If it does the point is inside the line"
  [point line]

  (and (line-has-point? point line)
       (let [t (get-line-tval line point)]
         (and (or (> t 0) (s= t 0))
              (or (< t 1) (s= t 1))))))

(defn curve-has-point?
  [point curve]
  (letfn [(check-range [from-t to-t]
            (let [r (curve-range->rect curve from-t to-t)]
              (when (grc/contains-point? r point)
                (if (s= from-t to-t)
                  (< (gpt/distance (curve-values curve from-t) point) 0.1)

                  (let [half-t (+ from-t (/ (- to-t from-t) 2.0))]
                    (or (check-range from-t half-t)
                        (check-range half-t to-t)))))))]

    (check-range 0 1)))

(defn curve-roots
  "Uses cardano algorithm to find the roots for a cubic bezier"
  ([[start end h1 h2] coord]
   (curve-roots start end h1 h2 coord))

  ([start end h1 h2 coord]

   (let [coords [[(get start coord) (get h1 coord) (get h2 coord) (get end coord)]]

         coord->tvalue
         (fn [[pa pb pc pd]]

           (let [a (+ (* 3 pa) (* -6 pb) (* 3 pc))
                 b (+ (* -3 pa) (* 3 pb))
                 c pa
                 d (+ (- pa) (* 3 pb) (* -3 pc) pd)]

             (solve-roots a b c d)))]
     (->> coords
          (mapcat coord->tvalue)
          ;; Only values in the range [0, 1] are valid
          (filterv #(and (>= % 0) (<= % 1)))))))


(defn line-line-crossing
  [[from-p1 to-p1 :as l1] [from-p2 to-p2 :as l2]]

  (let [{x1 :x y1 :y} from-p1
        {x2 :x y2 :y} to-p1

        {x3 :x y3 :y} from-p2
        {x4 :x y4 :y} to-p2

        nx (- (* (- x3 x4) (- (* x1 y2) (* y1 x2)))
              (* (- x1 x2) (- (* x3 y4) (* y3 x4))))

        ny (- (* (- y3 y4) (- (* x1 y2) (* y1 x2)))
              (* (- y1 y2) (- (* x3 y4) (* y3 x4))))

        d  (- (* (- x1 x2) (- y3 y4))
              (* (- y1 y2) (- x3 x4)))]

    (cond
      (not (mth/almost-zero? d))
      ;; Coordinates in the line. We calculate the tvalue that will
      ;; return 0-1 as a percentage in the segment
      (let [cross-p (gpt/point (/ nx d) (/ ny d))
            t1 (get-line-tval l1 cross-p)
            t2 (get-line-tval l2 cross-p)]
        [t1 t2])

      ;; If they are parallels they could define the same line
      (line-has-point? from-p2 l1) [(get-line-tval l1 from-p2) 0]
      (line-has-point? to-p2   l1) [(get-line-tval l1 to-p2)   1]
      (line-has-point? to-p1   l2) [1 (get-line-tval l2 to-p1)]
      (line-has-point? from-p1 l2) [0 (get-line-tval l2 from-p1)]

      :else
      nil)))

(defn line-line-intersect
  [l1 l2]

  (let [[l1-t l2-t] (line-line-crossing l1 l2)]
    (when (and (some? l1-t) (some? l2-t)
               (or (> l1-t 0) (s= l1-t 0))
               (or (< l1-t 1) (s= l1-t 1))
               (or (> l2-t 0) (s= l2-t 0))
               (or (< l2-t 1) (s= l2-t 1)))
      [[l1-t] [l2-t]])))

;; FIXME: check private flag
(defn line-curve-crossing
  [[from-p1 to-p1]
   [from-p2 to-p2 h1-p2 h2-p2]]

  (let [theta (-> (mth/atan2 (- (:y to-p1) (:y from-p1))
                             (- (:x to-p1) (:x from-p1)))
                  (mth/degrees))

        transform (-> (gmt/matrix)
                      (gmt/rotate (- theta))
                      (gmt/translate (gpt/negate from-p1)))

        c2' [(gpt/transform from-p2 transform)
             (gpt/transform to-p2 transform)
             (gpt/transform h1-p2 transform)
             (gpt/transform h2-p2 transform)]]

    (curve-roots c2' :y)))

(defn line-curve-intersect
  [l1 c2]

  (let [curve-ts (->> (line-curve-crossing l1 c2)
                      (filterv
                       (fn [curve-t]
                         (let [curve-t (if (mth/almost-zero? curve-t) 0 curve-t)
                               curve-v (curve-values c2 curve-t)
                               line-t (get-line-tval l1 curve-v)]
                           (and (>= curve-t 0) (<= curve-t 1)
                                (>= line-t 0) (<= line-t 1))))))

        ;; Intersection line-curve points
        intersect-ps (->> curve-ts
                          (mapv #(curve-values c2 %)))

        line-ts (->> intersect-ps
                     (mapv #(get-line-tval l1 %)))]

    [line-ts curve-ts]))

(defn ray-overlaps?
  [ray-point {selrect :selrect}]
  (and (or (> (:y ray-point) (:y1 selrect))
           (mth/almost-zero? (- (:y ray-point) (:y1 selrect))))
       (or (< (:y ray-point) (:y2 selrect))
           (mth/almost-zero? (- (:y ray-point) (:y2 selrect))))))

(defn ray-line-intersect
  [point [a b :as line]]

  ;; If the ray is parallel to the line there will be no crossings
  (let [ray-line [point (gpt/point (inc (:x point)) (:y point))]
        ;; Rays fail when fall just in a vertex so we move a bit upward
        ;; because only want to use this for insideness
        a (if (and (some? a) (s= (:y a) (:y point))) (update a :y + 10) a)
        b (if (and (some? b) (s= (:y b) (:y point))) (update b :y + 10) b)
        [ray-t line-t] (line-line-crossing ray-line [a b])]

    (when (and (some? line-t) (some? ray-t)
               (> ray-t 0)
               (or (> line-t 0) (s= line-t 0))
               (or (< line-t 1) (s= line-t 1)))
      [[(line-values line line-t)
        (line-windup line line-t)]])))

(defn ray-curve-intersect
  [ray-line curve]

  (let [curve-ts (->> (line-curve-crossing ray-line curve)
                      (filterv #(let [curve-v (curve-values curve %)
                                      curve-tg (curve-tangent curve %)
                                      curve-tg-angle (gpt/angle curve-tg)
                                      ray-t (get-line-tval ray-line curve-v)]
                                  (and (> ray-t 0)
                                       (> (mth/abs (- curve-tg-angle 180)) 0.01)
                                       (> (mth/abs (- curve-tg-angle 0)) 0.01)))))]
    (->> curve-ts
         (mapv #(vector (curve-values curve %)
                        (curve-windup curve %))))))

(defn curve-curve-intersect
  [c1 c2]

  (letfn [(check-range [c1-from c1-to c2-from c2-to]
            (let [r1 (curve-range->rect c1 c1-from c1-to)
                  r2 (curve-range->rect c2 c2-from c2-to)]

              (when (grc/overlaps-rects? r1 r2)
                (let [p1 (curve-values c1 c1-from)
                      p2 (curve-values c2 c2-from)]

                  (if (< (gpt/distance p1 p2) curve-curve-precision)
                    [{:p1 p1
                      :p2 p2
                      :d  (gpt/distance p1 p2)
                      :t1 (mth/precision c1-from 4)
                      :t2 (mth/precision c2-from 4)}]

                    (let [c1-half (+ c1-from (/ (- c1-to c1-from) 2))
                          c2-half (+ c2-from (/ (- c2-to c2-from) 2))

                          ts-1 (check-range c1-from c1-half c2-from c2-half)
                          ts-2 (check-range c1-from c1-half c2-half c2-to)
                          ts-3 (check-range c1-half c1-to c2-from c2-half)
                          ts-4 (check-range c1-half c1-to c2-half c2-to)]

                      (d/concat-vec ts-1 ts-2 ts-3 ts-4)))))))

          (remove-close-ts [{cp1 :p1 cp2 :p2}]
            (fn [{:keys [p1 p2]}]
              (and (>= (gpt/distance p1 cp1) curve-range-precision)
                   (>= (gpt/distance p2 cp2) curve-range-precision))))

          (process-ts [ts]
            (loop [current (first ts)
                   pending (rest ts)
                   c1-ts   []
                   c2-ts   []]

              (if (nil? current)
                [c1-ts c2-ts]

                (let [pending (->> pending (filter (remove-close-ts current)))
                      c1-ts (conj c1-ts (:t1 current))
                      c2-ts (conj c2-ts (:t2 current))]
                  (recur (first pending)
                         (rest pending)
                         c1-ts
                         c2-ts)))))]

    (->> (check-range 0 1 0 1)
         (sort-by :d)
         (process-ts))))

(defn is-point-in-geom-data?
  [point content-geom]

  (let [ray-line [point (gpt/point (inc (:x point)) (:y point))]

        cast-ray
        (fn [data]
          (case (:command data)
            :line-to
            (ray-line-intersect point (:geom data))

            :curve-to
            (ray-curve-intersect ray-line (:geom data))

            #_:default []))]

    (->> content-geom
         (filter (partial ray-overlaps? point))
         (mapcat cast-ray)
         (map second)
         (reduce +)
         (not= 0))))

(defn is-point-in-border?
  [point content]

  (letfn [(inside-border? [segment]
            (case (:command segment)
              :line-to  (segment-has-point?  point (command->line segment))
              :curve-to (curve-has-point? point (command->bezier segment))
              #_:else   false))]

    (some inside-border? content)))



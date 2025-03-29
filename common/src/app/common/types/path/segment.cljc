;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path.segment
  "A collection of helpers for work with plain segment type"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [app.common.types.path.subpath :as subpath]
   [clojure.set :as set]))

(defn get-point
  "Get a point for a segment"
  ([prev-pos {:keys [relative params] :as segment}]
   (let [{:keys [x y] :or {x (:x prev-pos) y (:y prev-pos)}} params]
     (if relative
       (-> prev-pos (update :x + x) (update :y + y))
       (get-point segment))))

  ([segment]
   (when segment
     (let [{:keys [x y]} (:params segment)]
       (gpt/point x y)))))

(defn make-move-to [to]
  {:command :move-to
   :relative false
   :params {:x (:x to)
            :y (:y to)}})

(defn make-line-to [to]
  {:command :line-to
   :relative false
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
   :relative false
   :params (make-curve-params to h1 h2)})

(defn update-handler
  [command prefix point]
  (let [[cox coy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
    (-> command
        (assoc-in [:params cox] (:x point))
        (assoc-in [:params coy] (:y point)))))

(defn get-handler [{:keys [params] :as command} prefix]
  (let [cx (d/prefix-keyword prefix :x)
        cy (d/prefix-keyword prefix :y)]
    (when (and command
               (contains? params cx)
               (contains? params cy))
      (gpt/point (get params cx)
                 (get params cy)))))


;; FIXME: rename segments->handlers
(defn content->handlers
  "Retrieve a map where for every point will retrieve a list of
  the handlers that are associated with that point.
  point -> [[index, prefix]]"
  [content]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-cmd pre-cmd]]]
                 (if (and pre-cmd (= :curve-to (:command cur-cmd)))
                   (let [cur-pos (get-point cur-cmd)
                         pre-pos (get-point pre-cmd)]
                     (-> [[pre-pos [index :c1]]
                          [cur-pos [index :c2]]]))
                   [])))

       (group-by first)
       (d/mapm #(mapv second %2))))

(defn point-indices
  [content point]
  (->> (d/enumerate content)
       (filter (fn [[_ cmd]] (= point (get-point cmd))))
       (mapv (fn [[index _]] index))))

(defn handler-indices
  "Return an index where the key is the positions and the values the handlers"
  [content point]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-cmd pre-cmd]]]
                 (if (and (some? pre-cmd) (= :curve-to (:command cur-cmd)))
                   (let [cur-pos (get-point cur-cmd)
                         pre-pos (get-point pre-cmd)]
                     (cond-> []
                       (= pre-pos point) (conj [index :c1])
                       (= cur-pos point) (conj [index :c2])))
                   [])))))

(defn opposite-index
  "Calculates the opposite index given a prefix and an index"
  [content index prefix]

  (let [point (if (= prefix :c2)
                (get-point (nth content index))
                (get-point (nth content (dec index))))

        point->handlers (content->handlers content)

        handlers (->> point
                      (point->handlers)
                      (filter (fn [[ci cp]] (and (not= index ci) (not= prefix cp)))))]

    (cond
      (= (count handlers) 1)
      (->> handlers first)

      (and (= :c1 prefix) (= (count content) index))
      [(dec index) :c2]

      :else nil)))


(defn get-commands
  "Returns the commands involving a point with its indices"
  [content point]
  (->> (d/enumerate content)
       (filterv (fn [[_ cmd]] (= (get-point cmd) point)))))


(defn prefix->coords [prefix]
  (case prefix
    :c1 [:c1x :c1y]
    :c2 [:c2x :c2y]
    nil))

(defn handler->point [content index prefix]
  (when (and (some? index)
             (some? prefix)
             (contains? content index))
    (let [[cx cy] (prefix->coords prefix)]
      (if (= :curve-to (get-in content [index :command]))
        (gpt/point (get-in content [index :params cx])
                   (get-in content [index :params cy]))

        (gpt/point (get-in content [index :params :x])
                   (get-in content [index :params :y]))))))

(defn handler->node [content index prefix]
  (if (= prefix :c1)
    (get-point (get content (dec index)))
    (get-point (get content index))))

(def ^:const curve-curve-precision 0.1)
(def ^:const curve-range-precision 2)

(defn s= [a b]
  (mth/almost-zero? (- a b)))

(defn calculate-opposite-handler
  "Given a point and its handler, gives the symmetric handler"
  [point handler]
  (let [handler-vector (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate handler-vector))))

(defn opposite-handler
  "Calculates the coordinates of the opposite handler"
  [point handler]
  (let [phv (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate phv))))

(defn opposite-handler-keep-distance
  "Calculates the coordinates of the opposite handler but keeping the old distance"
  [point handler old-opposite]
  (let [old-distance (gpt/distance point old-opposite)
        phv (gpt/to-vec point handler)
        phv2 (gpt/multiply
              (gpt/unit (gpt/negate phv))
              (gpt/point old-distance))]
    (gpt/add point phv2)))

(defn content->points
  "Returns the points in the given content"
  [content]
  (letfn [(segment->point [seg]
            (let [params (get seg :params)
                  x      (get params :x)
                  y      (get params :y)]
              (when (d/num? x y)
                (gpt/point x y))))]
    (some->> (seq content)
             (into [] (keep segment->point)))))

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

;; https://trans4mind.com/personal_development/mathematics/polynomials/cubicAlgebra.htm
(defn- solve-roots
  "Solvers a quadratic or cubic equation given by the parameters a b c d"
  ([a b c]
   (solve-roots a b c 0))

  ([a b c d]
   (let [sqrt-b2-4ac (mth/sqrt (- (* b b) (* 4 a c)))]
     (cond
       ;; No solutions
       (and (mth/almost-zero? d) (mth/almost-zero? a) (mth/almost-zero? b))
       []

       ;; Linear solution
       (and (mth/almost-zero? d) (mth/almost-zero? a))
       [(/ (- c) b)]

       ;; Quadratic
       (mth/almost-zero? d)
       [(/ (+ (- b) sqrt-b2-4ac)
           (* 2 a))
        (/ (- (- b) sqrt-b2-4ac)
           (* 2 a))]

       ;; Cubic
       :else
       (let [a (/ a d)
             b (/ b d)
             c (/ c d)

             p  (/ (- (* 3 b) (* a a)) 3)
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

             [root1 root2 root3])

           (mth/almost-zero? discriminant)
           (let [u1 (if (< q2 0) (mth/cubicroot (- q2)) (- (mth/cubicroot q2)))
                 root1 (- (* 2 u1) (/ a 3))
                 root2 (- (- u1) (/ a 3))]
             [root1 root2])

           :else
           (let [sd (mth/sqrt discriminant)
                 u1 (mth/cubicroot (- sd q2))
                 v1 (mth/cubicroot (+ sd q2))
                 root (- u1 v1 (/ a 3))]
             [root])))))))

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

;; FIXME: looks very similar to get-point
(defn command->point
  ([command]
   (command->point command nil))

  ([command coord]
   (let [params (:params command)
         xkey (case coord
                :c1 :c1x
                :c2 :c2x
                :x)
         ykey (case coord
                :c1 :c1y
                :c2 :c2y
                :y)
         x (get params xkey)
         y (get params ykey)]
     (when (and (some? x) (some? y))
       (gpt/point x y)))))

(defn command->line
  ([cmd]
   (command->line cmd (:prev cmd)))
  ([cmd prev]
   [prev (command->point cmd)]))

(defn command->bezier
  ([cmd]
   (command->bezier cmd (:prev cmd)))
  ([cmd prev]
   [prev
    (command->point cmd)
    (gpt/point (-> cmd :params :c1x) (-> cmd :params :c1y))
    (gpt/point (-> cmd :params :c2x) (-> cmd :params :c2y))]))

(defn command->selrect
  ([command]
   (command->selrect command (:prev command)))

  ([command prev-point]
   (let [points (case (:command command)
                  :move-to [(command->point command)]

                  ;; If it's a line we add the beginning point and endpoint
                  :line-to [prev-point (command->point command)]

                  ;; We return the bezier extremities
                  :curve-to (into [prev-point (command->point command)]
                                  (let [curve [prev-point
                                               (command->point command)
                                               (command->point command :c1)
                                               (command->point command :c2)]]
                                    (->> (curve-extremities curve)
                                         (mapv #(curve-values curve %)))))
                  [])]
     (grc/points->rect points))))

(defn content->selrect [content]
  (let [extremities
        (loop [points #{}
               from-p nil
               move-p nil
               content (seq content)]
          (if content
            (let [last-p (last content)
                  content (if (= :move-to (:command last-p))
                            (butlast content)
                            content)
                  command (first content)
                  to-p    (command->point command)

                  [from-p move-p command-pts]
                  (case (:command command)
                    :move-to    [to-p   to-p   (when to-p [to-p])]
                    :close-path [move-p move-p (when move-p [move-p])]
                    :line-to    [to-p   move-p (when (and from-p to-p) [from-p to-p])]
                    :curve-to   [to-p   move-p
                                 (let [c1 (command->point command :c1)
                                       c2 (command->point command :c2)
                                       curve [from-p to-p c1 c2]]
                                   (when (and from-p to-p c1 c2)
                                     (into [from-p to-p]
                                           (->> (curve-extremities curve)
                                                (map #(curve-values curve %))))))]
                    [to-p move-p []])]

              (recur (apply conj points command-pts) from-p move-p (next content)))
            points))

        ;; We haven't found any extremes so we turn the commands to points
        extremities
        (if (empty? extremities)
          (->> content (keep command->point))
          extremities)]

    ;; If no points are returned we return an empty rect.
    (if (d/not-empty? extremities)
      (grc/points->rect extremities)
      (grc/make-rect))))

(defn move-content [content move-vec]
  (let [dx (:x move-vec)
        dy (:y move-vec)

        set-tr
        (fn [params px py]
          (cond-> params
            (d/num? dx)
            (update px + dx)

            (d/num? dy)
            (update py + dy)))

        transform-params
        (fn [{:keys [x y c1x c1y c2x c2y] :as params}]
          (cond-> params
            (d/num? x y)   (set-tr :x :y)
            (d/num? c1x c1y) (set-tr :c1x :c1y)
            (d/num? c2x c2y) (set-tr :c2x :c2y)))

        update-command
        (fn [command]
          (update command :params transform-params))]

    (->> content
         (into [] (map update-command)))))

(defn transform-content
  [content transform]
  (if (some? transform)
    (let [set-tr
          (fn [params px py]
            (let [tr-point (-> (gpt/point (get params px) (get params py))
                               (gpt/transform transform))]
              (assoc params
                     px (:x tr-point)
                     py (:y tr-point))))

          transform-params
          (fn [{:keys [x c1x c2x] :as params}]
            (cond-> params
              (some? x)   (set-tr :x :y)
              (some? c1x) (set-tr :c1x :c1y)
              (some? c2x) (set-tr :c2x :c2y)))]

      (into []
            (map #(update % :params transform-params))
            content))
    content))

(defn segments->content
  ([segments]
   (segments->content segments false))

  ([segments closed?]
   (let [initial (first segments)
         lines (rest segments)]

     (d/concat-vec
      [{:command :move-to
        :params (select-keys initial [:x :y])}]

      (->> lines
           (map #(hash-map :command :line-to
                           :params (select-keys % [:x :y]))))

      (when closed?
        [{:command :close-path}])))))

(defonce num-segments 10)

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

(defn path->lines
  "Given a path returns a list of lines that approximate the path"
  [shape]
  (loop [command (first (:content shape))
         pending (rest (:content shape))
         result []
         last-start nil
         prev-point nil]

    (if-let [{:keys [command params]} command]
      (let [point (if (= :close-path command)
                    last-start
                    (gpt/point params))

            result (case command
                     :line-to  (conj result [prev-point point])
                     :curve-to (let [h1 (gpt/point (:c1x params) (:c1y params))
                                     h2 (gpt/point (:c2x params) (:c2y params))]
                                 (into result (curve->lines prev-point point h1 h2)))
                     :move-to  (cond-> result
                                 last-start (conj [prev-point last-start]))
                     result)
            last-start (if (= :move-to command)
                         point
                         last-start)]
        (recur (first pending)
               (rest pending)
               result
               last-start
               point))

      (conj result [prev-point last-start]))))

(defonce path-closest-point-accuracy 0.01)
(defn curve-closest-point
  [position start end h1 h2]
  (let [d (memoize (fn [t] (gpt/distance position (curve-values start end h1 h2 t))))]
    (loop [t1 0
           t2 1]
      (if (<= (mth/abs (- t1 t2)) path-closest-point-accuracy)
        (-> (curve-values start end h1 h2 t1)
            ;; store the segment info
            (with-meta {:t t1 :from-p start :to-p end}))

        (let [ht  (+ t1 (/ (- t2 t1) 2))
              ht1 (+ t1 (/ (- t2 t1) 4))
              ht2 (+ t1 (/ (* 3 (- t2 t1)) 4))

              [t1 t2] (cond
                        (< (d ht1) (d ht2))
                        [t1 ht]

                        (< (d ht2) (d ht1))
                        [ht t2]

                        (and (< (d ht) (d t1)) (< (d ht) (d t2)))
                        [ht1 ht2]

                        (< (d t1) (d t2))
                        [t1 ht]

                        :else
                        [ht t2])]
          (recur t1 t2))))))

(defn line-closest-point
  "Point on line"
  [position from-p to-p]

  (let [e1 (gpt/to-vec from-p to-p)
        e2 (gpt/to-vec from-p position)

        len2 (+ (mth/sq (:x e1)) (mth/sq (:y e1)))
        t (/ (gpt/dot e1 e2) len2)]

    (if (and (>= t 0) (<= t 1) (not (mth/almost-zero? len2)))
      (-> (gpt/add from-p (gpt/scale e1 t))
          (with-meta {:t t
                      :from-p from-p
                      :to-p to-p}))

      ;; There is no perpendicular projection in the line so the closest
      ;; point will be one of the extremes
      (if (<= (gpt/distance position from-p) (gpt/distance position to-p))
        from-p
        to-p))))

(defn path-closest-point
  "Given a path and a position"
  [shape position]

  (let [point+distance
        (fn [[cur-cmd prev-cmd]]
          (let [from-p (command->point prev-cmd)
                to-p   (command->point cur-cmd)
                h1 (gpt/point (get-in cur-cmd [:params :c1x])
                              (get-in cur-cmd [:params :c1y]))
                h2 (gpt/point (get-in cur-cmd [:params :c2x])
                              (get-in cur-cmd [:params :c2y]))
                point
                (case (:command cur-cmd)
                  :line-to
                  (line-closest-point position from-p to-p)

                  :curve-to
                  (curve-closest-point position from-p to-p h1 h2)

                  nil)]
            (when point
              [point (gpt/distance point position)])))

        find-min-point (fn [[min-p min-dist :as acc] [cur-p cur-dist :as cur]]
                         (if (and (some? acc) (or (not cur) (<= min-dist cur-dist)))
                           [min-p min-dist]
                           [cur-p cur-dist]))]

    (->> (:content shape)
         (d/with-prev)
         (map point+distance)
         (reduce find-min-point)
         (first))))

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

(defn line-line-intersect
  [l1 l2]

  (let [[l1-t l2-t] (line-line-crossing l1 l2)]
    (when (and (some? l1-t) (some? l2-t)
               (or (> l1-t 0) (s= l1-t 0))
               (or (< l1-t 1) (s= l1-t 1))
               (or (> l2-t 0) (s= l2-t 0))
               (or (< l2-t 1) (s= l2-t 1)))
      [[l1-t] [l2-t]])))

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

(defn curve->rect
  [[from-p to-p :as curve]]
  (let [extremes (->> (curve-extremities curve)
                      (mapv #(curve-values curve %)))]
    (grc/points->rect (into [from-p to-p] extremes))))


(defn is-point-in-border?
  [point content]

  (letfn [(inside-border? [cmd]
            (case (:command cmd)
              :line-to  (segment-has-point?  point (command->line cmd))
              :curve-to (curve-has-point? point (command->bezier cmd))
              #_:else   false))]

    (->> content
         (some inside-border?))))

(defn close-content
  [content]
  (into []
        (mapcat :data)
        (->> content
             (subpath/close-subpaths)
             (subpath/get-subpaths))))

(defn ray-overlaps?
  [ray-point {selrect :selrect}]
  (and (or (> (:y ray-point) (:y1 selrect))
           (mth/almost-zero? (- (:y ray-point) (:y1 selrect))))
       (or (< (:y ray-point) (:y2 selrect))
           (mth/almost-zero? (- (:y ray-point) (:y2 selrect))))))

(defn content->geom-data
  [content]

  (->> content
       (close-content)
       (filter #(not= (= :line-to (:command %))
                      (= :curve-to (:command %))))
       (mapv (fn [segment]
               {:command (:command segment)
                :segment segment
                :geom (if (= :line-to (:command segment))
                        (command->line segment)
                        (command->bezier segment))
                :selrect (command->selrect segment)}))))

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

(defn split-line-to
  "Given a point and a line-to command will create a two new line-to commands
  that will split the original line into two given a value between 0-1"
  [from-p cmd t-val]
  (let [to-p (command->point cmd)
        sp (gpt/lerp from-p to-p t-val)]
    [(make-line-to sp) cmd]))

(defn split-curve-to
  "Given the point and a curve-to command will split the curve into two new
  curve-to commands given a value between 0-1"
  [from-p cmd t-val]
  (let [params (:params cmd)
        end (gpt/point (:x params) (:y params))
        h1 (gpt/point (:c1x params) (:c1y params))
        h2 (gpt/point (:c2x params) (:c2y params))
        [[_ to1 h11 h21]
         [_ to2 h12 h22]] (curve-split from-p end h1 h2 t-val)]
    [(make-curve-to to1 h11 h21)
     (make-curve-to to2 h12 h22)]))

(defn split-line-to-ranges
  "Splits a line into several lines given the points in `values`
  for example (split-line-to-ranges p c [0 0.25 0.5 0.75 1] will split
  the line into 4 lines"
  [from-p cmd values]
  (let [values (->> values (filter #(and (> % 0) (< % 1))))]
    (if (empty? values)
      [cmd]
      (let [to-p (command->point cmd)
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
  [from-p cmd values]

  (let [values (->> values (filter #(and (> % 0) (< % 1))))]
    (if (empty? values)
      [cmd]
      (let [to-p (command->point cmd)
            params (:params cmd)
            h1 (gpt/point (:c1x params) (:c1y params))
            h2 (gpt/point (:c2x params) (:c2y params))

            values-set (->> (conj values 0 1) (into (sorted-set)))]

        (->> (d/with-prev values-set)
             (rest)
             (mapv
              (fn [[t1 t0]]
                (let [[_ to-p h1' h2'] (subcurve-range from-p to-p h1 h2 t0 t1)]
                  (make-curve-to (-> to-p #_(gpt/round 2)) h1' h2')))))))))


(defn- remove-line-curves
  "Remove all curves that have both handlers in the same position that the
  beginning and end points. This makes them really line-to commands"
  [content]
  (let [with-prev (d/enumerate (d/with-prev content))
        process-command
        (fn [content [index [command prev]]]

          (let [cur-point (get-point command)
                pre-point (get-point prev)
                handler-c1 (get-handler command :c1)
                handler-c2 (get-handler command :c2)]
            (if (and (= :curve-to (:command command))
                     (= cur-point handler-c2)
                     (= pre-point handler-c1))
              (assoc content index {:command :line-to
                                    :params (into {} cur-point)})
              content)))]

    (reduce process-command content with-prev)))

(defn make-corner-point
  "Changes the content to make a point a 'corner'"
  [content point]
  (let [handlers (-> (content->handlers content)
                     (get point))
        change-content
        (fn [content [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> content
                (assoc-in [index :params cx] (:x point))
                (assoc-in [index :params cy] (:y point)))))]
    (as-> content $
      (reduce change-content $ handlers)
      (remove-line-curves $))))


(defn- line->curve
  [from-p segment]

  (let [to-p (get-point segment)

        v (gpt/to-vec from-p to-p)
        d (gpt/distance from-p to-p)

        dv1 (-> (gpt/normal-left v)
                (gpt/scale (/ d 3)))

        h1 (gpt/add from-p dv1)

        dv2 (-> (gpt/to-vec to-p h1)
                (gpt/unit)
                (gpt/scale (/ d 3)))

        h2 (gpt/add to-p dv2)]
    (-> segment
        (assoc :command :curve-to)
        (update :params (fn [params]
                          ;; ensure plain map
                          (-> (into {} params)
                              (assoc :c1x (:x h1))
                              (assoc :c1y (:y h1))
                              (assoc :c2x (:x h2))
                              (assoc :c2y (:y h2))))))))

(defn is-curve?
  [content point]
  (let [handlers (-> (content->handlers content)
                     (get point))
        handler-points (map #(handler->point content (first %) (second %)) handlers)]
    (some #(not= point %) handler-points)))

(def ^:private xf:mapcat-points
  (comp
   (mapcat #(vector (:next-p %) (:prev-p %)))
   (remove nil?)))

(defn make-curve-point
  "Changes the content to make the point a 'curve'. The handlers will be positioned
  in the same vector that results from the previous->next points but with fixed length."
  [content point]

  (let [indices (point-indices content point)
        vectors (map (fn [index]
                       (let [segment (nth content index)
                             prev-i (dec index)
                             prev (when (not (= :move-to (:command segment)))
                                    (get content prev-i))
                             next-i (inc index)
                             next (get content next-i)

                             next (when (not (= :move-to (:command next)))
                                    next)]
                         {:index index
                          :prev-i (when (some? prev) prev-i)
                          :prev-c prev
                          :prev-p (get-point prev)
                          :next-i (when (some? next) next-i)
                          :next-c next
                          :next-p (get-point next)
                          :segment segment}))
                     indices)

        points (into #{} xf:mapcat-points vectors)]

    (if (= (count points) 2)
      (let [v1 (gpt/to-vec (first points) point)
            v2 (gpt/to-vec (first points) (second points))
            vp (gpt/project v1 v2)
            vh (gpt/subtract v1 vp)

            add-curve
            (fn [content {:keys [index prev-p next-p next-i]}]
              (let [cur-segment (get content index)
                    next-segment (get content next-i)

                    ;; New handlers for prev-point and next-point
                    prev-h (when (some? prev-p) (gpt/add prev-p vh))
                    next-h (when (some? next-p) (gpt/add next-p vh))

                    ;; Correct 1/3 to the point improves the curve
                    prev-correction (when (some? prev-h) (gpt/scale (gpt/to-vec prev-h point) (/ 1 3)))
                    next-correction (when (some? next-h) (gpt/scale (gpt/to-vec next-h point) (/ 1 3)))

                    prev-h (when (some? prev-h) (gpt/add prev-h prev-correction))
                    next-h (when (some? next-h) (gpt/add next-h next-correction))]
                (cond-> content
                  (and (= :line-to (:command cur-segment)) (some? prev-p))
                  (update index update-curve-to prev-p prev-h)

                  (and (= :line-to (:command next-segment)) (some? next-p))
                  (update next-i update-curve-to next-h next-p)

                  (and (= :curve-to (:command cur-segment)) (some? prev-p))
                  (update index update-handler :c2 prev-h)

                  (and (= :curve-to (:command next-segment)) (some? next-p))
                  (update next-i update-handler :c1 next-h))))]

        (reduce add-curve content vectors))

      (let [add-curve
            (fn [content {:keys [index segment prev-p next-c next-i]}]
              (cond-> content
                (= :line-to (:command segment))
                (update index #(line->curve prev-p %))

                (= :curve-to (:command segment))
                (update index #(line->curve prev-p %))

                (= :line-to (:command next-c))
                (update next-i #(line->curve point %))

                (= :curve-to (:command next-c))
                (update next-i #(line->curve point %))))]
        (reduce add-curve content vectors)))))

;; FIXME: revisit the impl of this function
(defn get-segments
  "Given a content and a set of points return all the segments in the path
  that uses the points"
  [content points]
  (let [point-set (set points)]

    (loop [segments    []
           prev-point  nil
           start-point nil
           index       0
           cur-cmd     (first content)
           content     (rest content)]

      (let [command     (:command cur-cmd)
            close-path? (= command :close-path)
            move-to?    (= command :move-to)

            ;; Close-path makes a segment from the last point to the initial path point
            cur-point (if close-path?
                        start-point
                        (get-point cur-cmd))

            ;; If there is a move-to we don't have a segment
            prev-point (if move-to?
                         nil
                         prev-point)

            ;; We update the start point
            start-point (if move-to?
                          cur-point
                          start-point)

            is-segment? (and (some? prev-point)
                             (contains? point-set prev-point)
                             (contains? point-set cur-point))

            segments (cond-> segments
                       is-segment?
                       (conj {:start prev-point
                              :end cur-point
                              :cmd cur-cmd
                              :index index}))]

        (if (some? cur-cmd)
          (recur segments
                 cur-point
                 start-point
                 (inc index)
                 (first content)
                 (rest content))

          segments)))))

(defn split-segments
  "Given a content creates splits commands between points with new segments"
  [content points value]

  (let [split-command
        (fn [{:keys [start end cmd index]}]
          (case (:command cmd)
            :line-to [index (split-line-to start cmd value)]
            :curve-to [index (split-curve-to start cmd value)]
            :close-path [index [(make-line-to (gpt/lerp start end value)) cmd]]
            nil))

        cmd-changes
        (->> (get-segments content points)
             (into {} (comp (map split-command)
                            (filter (comp not nil?)))))

        process-segments
        (fn [[index command]]
          (if (contains? cmd-changes index)
            (get cmd-changes index)
            [command]))]

    (into [] (mapcat process-segments) (d/enumerate content))))

(defn remove-nodes
  "Removes from content the points given. Will try to reconstruct the paths
  to keep everything consistent"
  [content points]

  (if (empty? points)
    content

    (let [content (d/with-prev content)]

      (loop [result []
             last-handler nil
             [cur-cmd prev-cmd] (first content)
             content (rest content)]

        (if (nil? cur-cmd)
          ;; The result with be an array of arrays were every entry is a subpath
          (->> result
               ;; remove empty and only 1 node subpaths
               (filter #(> (count %) 1))
               ;; flatten array-of-arrays plain array
               (flatten)
               (into []))

          (let [move? (= :move-to (:command cur-cmd))
                curve? (= :curve-to (:command cur-cmd))

                ;; When the old command was a move we start a subpath
                result (if move? (conj result []) result)

                subpath (peek result)

                point (get-point cur-cmd)

                old-prev-point (get-point prev-cmd)
                new-prev-point (get-point (peek subpath))

                remove? (contains? points point)


                ;; We store the first handler for the first curve to be removed to
                ;; use it for the first handler of the regenerated path
                cur-handler (cond
                              (and (not last-handler) remove? curve?)
                              (select-keys (:params cur-cmd) [:c1x :c1y])

                              (not remove?)
                              nil

                              :else
                              last-handler)

                cur-cmd (cond-> cur-cmd
                          ;; If we're starting a subpath and it's not a move make it a move
                          (and (not move?) (empty? subpath))
                          (assoc :command :move-to
                                 :params (select-keys (:params cur-cmd) [:x :y]))

                          ;; If have a curve the first handler will be relative to the previous
                          ;; point. We change the handler to the new previous point
                          (and curve? (seq subpath) (not= old-prev-point new-prev-point))
                          (update :params merge last-handler))

                head-idx (dec (count result))

                result (cond-> result
                         (not remove?)
                         (update head-idx conj cur-cmd))]
            (recur result
                   cur-handler
                   (first content)
                   (rest content))))))))

(defn join-nodes
  "Creates new segments between points that weren't previously"
  [content points]

  (let [segments-set (into #{}
                           (map (juxt :start :end))
                           (get-segments content points))

        create-line-command (fn [point other]
                              [(make-move-to point)
                               (make-line-to other)])

        not-segment? (fn [point other] (and (not (contains? segments-set [point other]))
                                            (not (contains? segments-set [other point]))))

        new-content (->> (d/map-perm create-line-command not-segment? points)
                         (flatten)
                         (into []))]

    (into content new-content)))


(defn separate-nodes
  "Removes the segments between the points given"
  [content points]

  (let [content (d/with-prev content)]
    (loop [result []
           [cur-cmd prev-cmd] (first content)
           content (rest content)]

      (if (nil? cur-cmd)
        (->> result
             (filter #(> (count %) 1))
             (flatten)
             (into []))

        (let [prev-point (get-point prev-cmd)
              cur-point (get-point cur-cmd)

              cur-cmd (cond-> cur-cmd
                        (and (contains? points prev-point)
                             (contains? points cur-point))

                        (assoc :command :move-to
                               :params (select-keys (:params cur-cmd) [:x :y])))

              move? (= :move-to (:command cur-cmd))

              result (if move? (conj result []) result)
              head-idx (dec (count result))

              result (-> result
                         (update head-idx conj cur-cmd))]
          (recur result
                 (first content)
                 (rest content)))))))


(defn- add-to-set
  "Given a list of sets adds the value to the target set"
  [set-list target value]
  (->> set-list
       (mapv (fn [it]
               (cond-> it
                 (= it target) (conj value))))))

(defn- join-sets
  "Given a list of sets join two sets in the list into a new one"
  [set-list target other]
  (conj (->> set-list
             (filterv #(and (not= % target)
                            (not= % other))))
        (set/union target other)))

(defn- group-segments [segments]
  (loop [result []
         {point-a :start point-b :end :as segment} (first segments)
         segments (rest segments)]

    (if (nil? segment)
      result

      (let [set-a (d/seek #(contains? % point-a) result)
            set-b (d/seek #(contains? % point-b) result)

            result (cond-> result
                     (and (nil? set-a) (nil? set-b))
                     (conj #{point-a point-b})

                     (and (some? set-a) (nil? set-b))
                     (add-to-set set-a point-b)

                     (and (nil? set-a) (some? set-b))
                     (add-to-set set-b point-a)

                     (and (some? set-a) (some? set-b) (not= set-a set-b))
                     (join-sets set-a set-b))]
        (recur result
               (first segments)
               (rest segments))))))

(defn- calculate-merge-points [group-segments points]
  (let [index-merge-point (fn [group] (vector group (gpt/center-points group)))
        index-group (fn [point] (vector point (d/seek #(contains? % point) group-segments)))

        group->merge-point (into {} (map index-merge-point) group-segments)
        point->group (into {} (map index-group) points)]
    (d/mapm #(group->merge-point %2) point->group)))

;; TODO: Improve the replace for curves
(defn- replace-points
  "Replaces the points in a path for its merge-point"
  [content point->merge-point]
  (let [replace-command
        (fn [cmd]
          (let [point (get-point cmd)]
            (if (contains? point->merge-point point)
              (let [merge-point (get point->merge-point point)]
                (-> cmd (update :params assoc :x (:x merge-point) :y (:y merge-point))))
              cmd)))]
    (->> content
         (mapv replace-command))))

(defn merge-nodes
  "Reduces the contiguous segments in points to a single point"
  [content points]
  (let [point->merge-point (-> content
                               (get-segments points)
                               (group-segments)
                               (calculate-merge-points points))]
    (-> content
        (separate-nodes points)
        (replace-points point->merge-point))))

(defn content-center
  [content]
  (-> content
      content->selrect
      grc/rect->center))

(defn content->points+selrect
  "Given the content of a shape, calculate its points and selrect"
  [shape content]
  (let [{:keys [flip-x flip-y]} shape
        transform
        (cond-> (:transform shape (gmt/matrix))
          flip-x (gmt/scale (gpt/point -1 1))
          flip-y (gmt/scale (gpt/point 1 -1)))

        transform-inverse
        (cond-> (gmt/matrix)
          flip-x (gmt/scale (gpt/point -1 1))
          flip-y (gmt/scale (gpt/point 1 -1))
          :always (gmt/multiply (:transform-inverse shape (gmt/matrix))))

        center (or (some-> (dm/get-prop shape :selrect) grc/rect->center)
                   (content-center content))

        base-content (transform-content
                      content
                      (gmt/transform-in center transform-inverse))

        ;; Calculates the new selrect with points given the old center
        points (-> (content->selrect base-content)
                   (grc/rect->points)
                   (gco/transform-points center transform))

        points-center (gco/points->center points)

        ;; Points is now the selrect but the center is different so we can create the selrect
        ;; through points
        selrect (-> points
                    (gco/transform-points points-center transform-inverse)
                    (grc/points->rect))]

    [points selrect]))

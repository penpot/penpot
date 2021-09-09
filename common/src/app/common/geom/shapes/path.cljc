;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.path
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]))

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

       ;; Cuadratic
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

           (= discriminant 0)
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
          (filterv #(and (> % 0.01) (< % 0.99)))))))

(defn command->point
  ([command] (command->point command nil))
  ([{params :params} coord]
   (let [prefix (if coord (name coord) "")
         xkey (keyword (str prefix "x"))
         ykey (keyword (str prefix "y"))
         x (get params xkey)
         y (get params ykey)]
     (gpt/point x y))))

(defn content->selrect [content]
  (let [calc-extremities
        (fn [command prev]
          (case (:command command)
            :move-to [(command->point command)]

            ;; If it's a line we add the beginning point and endpoint
            :line-to [(command->point prev)
                      (command->point command)]

            ;; We return the bezier extremities
            :curve-to (d/concat
                       [(command->point prev)
                        (command->point command)]
                       (let [curve [(command->point prev)
                                    (command->point command)
                                    (command->point command :c1)
                                    (command->point command :c2)]]
                         (->> (curve-extremities curve)
                              (mapv #(curve-values curve %)))))
            []))

        extremities (mapcat calc-extremities
                            content
                            (d/concat [nil] content))

        selrect (gpr/points->selrect extremities)]

    (-> selrect
        (update :width #(if (mth/almost-zero? %) 1 %))
        (update :height #(if (mth/almost-zero? %) 1 %)))))

(defn move-content [content move-vec]
  (let [set-tr (fn [params px py]
                 (let [tr-point (-> (gpt/point (get params px) (get params py))
                                    (gpt/add move-vec))]
                   (assoc params
                          px (:x tr-point)
                          py (:y tr-point))))

        transform-params
        (fn [{:keys [x c1x c2x] :as params}]
          (cond-> params
            (not (nil? x))   (set-tr :x :y)
            (not (nil? c1x)) (set-tr :c1x :c1y)
            (not (nil? c2x)) (set-tr :c2x :c2y)))]

    (mapv #(update % :params transform-params) content)))

(defn transform-content
  [content transform]
  (let [set-tr (fn [params px py]
                 (let [tr-point (-> (gpt/point (get params px) (get params py))
                                    (gpt/transform transform))]
                   (assoc params
                          px (:x tr-point)
                          py (:y tr-point))))

        transform-params
        (fn [{:keys [x c1x c2x] :as params}]
          (cond-> params
            (not (nil? x))   (set-tr :x :y)
            (not (nil? c1x)) (set-tr :c1x :c1y)
            (not (nil? c2x)) (set-tr :c2x :c2y)))]

    (mapv #(update % :params transform-params) content)))

(defn segments->content
  ([segments]
   (segments->content segments false))

  ([segments closed?]
   (let [initial (first segments)
         lines (rest segments)]

     (d/concat [{:command :move-to
                 :params (select-keys initial [:x :y])}]
               (->> lines
                    (mapv #(hash-map :command :line-to
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
                         last-start)
            ]
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

  (let [e1 (gpt/to-vec from-p to-p )
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


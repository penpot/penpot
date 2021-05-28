;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.path
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.data :as d]))

(defn content->points [content]
  (->> content
       (map #(when (-> % :params :x) (gpt/point (-> % :params :x) (-> % :params :y))))
       (remove nil?)
       (into [])))

;; https://medium.com/@Acegikmo/the-ever-so-lovely-b%C3%A9zier-curve-eb27514da3bf
;; https://en.wikipedia.org/wiki/Bernstein_polynomial
(defn curve-values
  "Parametric equation for cubic beziers. Given a start and end and
  two intermediate points returns points for values of t.
  If you draw t on a plane you got the bezier cube"
  [start end h1 h2 t]

  (let [t2 (* t t)  ;; t square
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

    (gpt/point (coord-v :x) (coord-v :y))))

(defn curve-split
  "Splits a curve into two at the given parametric value `t`.
  Calculates the Casteljau's algorithm intermediate points"
  [start end h1 h2 t]

  (let [p1 (gpt/line-val start h1 t)
        p2 (gpt/line-val h1 h2 t)
        p3 (gpt/line-val h2 end t)
        p4 (gpt/line-val p1 p2 t)
        p5 (gpt/line-val p2 p3 t)
        sp (gpt/line-val p4 p5 t)]
    [[start sp  p1 p4]
     [sp    end p5 p3]]))

;; https://pomax.github.io/bezierinfo/#extremities
(defn curve-extremities
  "Given a cubic bezier cube finds its roots in t. This are the extremities
  if we calculate its values for x, y we can find a bounding box for the curve."
  [start end h1 h2]

  (let [coords [[(:x start) (:x h1) (:x h2) (:x end)]
                [(:y start) (:y h1) (:y h2) (:y end)]]

        coord->tvalue
        (fn [[c0 c1 c2 c3]]

          (let [a (+ (* -3 c0) (*   9 c1) (* -9 c2) (* 3 c3))
                b (+ (*  6 c0) (* -12 c1) (* 6 c2))
                c (+ (*  3 c1) (*  -3 c0))

                sqrt-b2-4ac (mth/sqrt (- (* b b) (* 4 a c)))]

            (cond
              (and (mth/almost-zero? a)
                   (not (mth/almost-zero? b)))
              ;; When the term a is close to zero we have a linear equation
              [(/ (- c) b)]

              ;; If a is not close to zero return the two roots for a cuadratic 
              (not (mth/almost-zero? a))
              [(/ (+ (- b) sqrt-b2-4ac)
                  (* 2 a))
               (/ (- (- b) sqrt-b2-4ac)
                  (* 2 a))]

              ;; If a and b close to zero we can't find a root for a constant term
              :else
              [])))]
    (->> coords
         (mapcat coord->tvalue)

         ;; Only values in the range [0, 1] are valid
         (filter #(and (>= % 0) (<= % 1)))

         ;; Pass t-values to actual points
         (map #(curve-values start end h1 h2 %)))
    ))

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
                       (curve-extremities (command->point prev)
                                          (command->point command)
                                          (command->point command :c1)
                                          (command->point command :c2)))
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

(defn transform-content [content transform]
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

  (let [point+distance (fn [[cur-cmd prev-cmd]]
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

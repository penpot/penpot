;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.bool
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.intersect :as gsi]
   [app.common.geom.shapes.path :as gpp]
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.util.path.geom :as upg]
   [app.util.path.subpaths :as ups]))

(def ^:const curve-curve-precision 0.1)

(defn curve->rect
  [[from-p to-p :as curve]]
  (let [extremes (->> (gpp/curve-extremities curve)
                      (mapv #(gpp/curve-values curve %)))]
    (gpr/points->rect (into [from-p to-p] extremes))))

(defn curve-range->rect
  [curve from-t to-t]

  (let [[from-p to-p :as curve] (gpp/subcurve-range curve from-t to-t)
        extremes (->> (gpp/curve-extremities curve)
                      (mapv #(gpp/curve-values curve %)))]
    (gpr/points->rect (into [from-p to-p] extremes))))

(defn line+point->tvalue
  [[{x1 :x y1 :y} {x2 :x y2 :y}] {:keys [x y]}]
  (if (mth/almost-zero? (- x2 x1))
    (/ (- y y1) (- y2 y1))
    (/ (- x x1) (- x2 x1))))

(defn line-line-intersect
  [[from-p1 to-p1] [from-p2 to-p2]]

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

    (when-not (mth/almost-zero? d)
      ;; ix,iy are the coordinates in the line. We calculate the
      ;; tvalue that will return 0-1 as a percentage in the segment
      
      (let [ix (/ nx d)
            iy (/ ny d)
            t1 (if (mth/almost-zero? (- x2 x1))
                 (/ (- iy y1) (- y2 y1))
                 (/ (- ix x1) (- x2 x1)))
            t2 (if (mth/almost-zero? (- x4 x3))
                 (/ (- iy y3) (- y4 y3))
                 (/ (- ix x3) (- x4 x3)))]

        (when (and (> t1 0) (< t1 1)
                   (> t2 0) (< t2 1))
          [[t1] [t2]])))))

(defn line-curve-intersect
  [[from-p1 to-p1 :as l1]
   [from-p2 to-p2 h1-p2 h2-p2 :as c2]]


  (let [theta (-> (mth/atan2 (- (:y to-p1) (:y from-p1))
                             (- (:x to-p1) (:x from-p1)))
                  (mth/degrees))

        transform (-> (gmt/matrix)
                      (gmt/rotate (- theta))
                      (gmt/translate (gpt/negate from-p1)))

        c2' [(gpt/transform from-p2 transform)
             (gpt/transform to-p2 transform)
             (gpt/transform h1-p2 transform)
             (gpt/transform h2-p2 transform)]

        ;; Curve intersections as t-values
        curve-ts (->> (gpp/curve-roots c2' :y)
                      (filterv #(let [curve-v (gpp/curve-values c2 %)
                                      line-t (line+point->tvalue l1 curve-v)]
                                  (and (> line-t 0.001) (< line-t 0.999)))))

        ;; Intersection line-curve points
        intersect-ps (->> curve-ts
                          (mapv #(gpp/curve-values c2 %)))
        
        line-ts (->> intersect-ps
                     (mapv #(line+point->tvalue l1 %)))]

    [line-ts curve-ts]))

(defn curve-curve-intersect
  [c1 c2]

  (letfn [(remove-close-ts [ts]
            (loop [current (first ts)
                   pending (rest ts)
                   acc     nil
                   result  []]
              (if (nil? current)
                result
                (if (and (some? acc)
                         (< (mth/abs (- current acc)) 0.01))
                  (recur (first pending)
                         (rest pending)
                         acc
                         result)

                  (recur (first pending)
                         (rest pending)
                         current
                         (conj result current))))))

          (check-range [c1-from c1-to c2-from c2-to]
            (let [r1 (curve-range->rect c1 c1-from c1-to)
                  r2 (curve-range->rect c2 c2-from c2-to)]

              (when (gsi/overlaps-rects? r1 r2)
                (if (< (gpt/distance (gpp/curve-values c1 c1-from)
                                     (gpp/curve-values c2 c2-from))
                       curve-curve-precision)
                  [(sorted-set (mth/precision c1-from 4))
                   (sorted-set (mth/precision c2-from 4))]

                  (let [c1-half (+ c1-from (/ (- c1-to c1-from) 2))
                        c2-half (+ c2-from (/ (- c2-to c2-from) 2))

                        [c1-ts-1 c2-ts-1] (check-range c1-from c1-half c2-from c2-half)
                        [c1-ts-2 c2-ts-2] (check-range c1-from c1-half c2-half c2-to)
                        [c1-ts-3 c2-ts-3] (check-range c1-half c1-to c2-from c2-half)
                        [c1-ts-4 c2-ts-4] (check-range c1-half c1-to c2-half c2-to)]

                    [(into (sorted-set) (d/concat [] c1-ts-1 c1-ts-2 c1-ts-3 c1-ts-4))
                     (into (sorted-set) (d/concat [] c2-ts-1 c2-ts-2 c2-ts-3 c2-ts-4))])))))]

    (let [[c1-ts c2-ts] (check-range 0.005 0.995 0.005 0.995)
          c1-ts (remove-close-ts c1-ts)
          c2-ts (remove-close-ts c2-ts)]
      [c1-ts c2-ts])))

(defn- line-to->line
  [cmd]
  [(:prev cmd) (gpp/command->point cmd)])

(defn- curve-to->bezier
  [cmd]
  [(:prev cmd)
   (gpp/command->point cmd)
   (gpt/point (-> cmd :params :c1x) (-> cmd :params :c1y))
   (gpt/point (-> cmd :params :c2x) (-> cmd :params :c2y))])

(defn- split-command
  [cmd values]
  (case (:command cmd)
    :line-to  (upg/split-line-to-ranges (:prev cmd) cmd values)
    :curve-to (upg/split-curve-to-ranges (:prev cmd) cmd values)
    [cmd]))

(defn split [seg-1 seg-2]
  (let [[ts-seg-1 ts-seg-2]
        (cond
          (and (= :line-to (:command seg-1))
               (= :line-to (:command seg-2)))
          (line-line-intersect (line-to->line seg-1) (line-to->line seg-2))

          (and (= :line-to (:command seg-1))
               (= :curve-to (:command seg-2)))
          (line-curve-intersect (line-to->line seg-1) (curve-to->bezier seg-2))
          
          (and (= :curve-to (:command seg-1))
               (= :line-to (:command seg-2)))
          (let [[seg-2' seg-1']
                (line-curve-intersect (line-to->line seg-2) (curve-to->bezier seg-1))]
            ;; Need to reverse because we send the arguments reversed
            [seg-1' seg-2'])
          
          (and (= :curve-to (:command seg-1))
               (= :curve-to (:command seg-2)))
          (curve-curve-intersect (curve-to->bezier seg-1) (curve-to->bezier seg-2))

          :else
          [[] []])]
    
    [(split-command seg-1 ts-seg-1)
     (split-command seg-2 ts-seg-2)]))

(defn add-previous
  ([content]
   (add-previous content nil))
  ([content first]
   (->> (d/with-prev content)
        (mapv (fn [[cmd prev]]
                (cond-> cmd
                  (and (nil? prev) (some? first))
                  (assoc :prev first)

                  (some? prev)
                  (assoc :prev (gpp/command->point prev))))))))

(defn content-intersect-split
  "Given two path contents will return the intersect between them"
  [content-a content-b]

  (let [content-a (add-previous content-a)
        content-b (add-previous content-b)]
    (if (or (empty? content-a) (empty? content-b))
      [content-a content-b]

      (loop [current       (first content-a)
             pending       (rest content-a)
             content-b     content-b
             new-content-a []]

        (if (not (some? current))
          [new-content-a content-b]

          (let [[new-current new-pending new-content-b]

                (loop [current      current
                       pending      pending
                       other        (first content-b)
                       head-content []
                       tail-content (rest content-b)]

                  (if (not (some? other))
                    ;; Finished recorring second content
                    [current pending head-content]

                    ;; We split the current
                    (let [[new-as new-bs] (split current other)
                          new-as (add-previous new-as (:prev current))
                          new-bs (add-previous new-bs (:prev other))]
                      
                      (if (> (count new-as) 1)
                        ;; We add the new-a's to the stack and change the b then we iterate to the top
                        (recur (first new-as)
                               (d/concat [] (rest new-as) pending)
                               (first tail-content)
                               (d/concat [] head-content new-bs)
                               (rest tail-content))

                        ;; No current segment-segment split we continue searching
                        (recur current
                               pending
                               (first tail-content)
                               (conj head-content other)
                               (rest tail-content))))))]

            (recur (first new-pending)
                   (rest new-pending)
                   new-content-b
                   (conj new-content-a new-current))))))))


(defn create-union [content-a content-b]
  (d/concat
   []
   content-a
   (ups/reverse-content content-b)))

(defn create-difference [content-a content-b]
  (d/concat
   []
   content-a
   (ups/reverse-content content-b)))

(defn create-intersection [content-a content-b]
  (d/concat
   []
   content-a
   (ups/reverse-content content-b)))


(defn create-exclusion [content-a content-b]
  (d/concat
   []
   content-a
   (ups/reverse-content content-b)))

(defn content-bool
  [bool-type content-a content-b]

  (let [[content-a' content-b'] (content-intersect-split content-a content-b)]
    (case bool-type
      :union        (create-union        content-a' content-b')
      :difference   (create-difference   content-a' content-b')
      :intersection (create-intersection content-a' content-b')
      :exclusion    (create-exclusion    content-a' content-b'))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.snap
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages :as cp]
   [app.common.uuid :refer [zero]]
   [app.main.refs :as refs]
   [app.main.worker :as uw]
   [app.util.geom.snap-points :as sp]
   [app.util.range-tree :as rt]
   [beicon.core :as rx]
   [clojure.set :as set]))

(def ^:const snap-accuracy 5)
(def ^:const snap-path-accuracy 10)
(def ^:const snap-distance-accuracy 10)

(defn- remove-from-snap-points
  [remove-id?]
  (fn [query-result]
    (->> query-result
         (map (fn [[value data]] [value (remove (comp remove-id? second) data)]))
         (filter (fn [[_ data]] (seq data))))))

(defn- flatten-to-points
  [query-result]
  (mapcat (fn [[_ data]] (map (fn [[point _]] point) data)) query-result))

(defn- calculate-distance [query-result point coord]
  (->> query-result
       (map (fn [[value _]] [(mth/abs (- value (coord point))) [(coord point) value]]))))

(defn- get-min-distance-snap [points coord]
  (fn [query-result]
    (->> points
         (mapcat #(calculate-distance query-result % coord))
         (apply min-key first)
         second)))

(defn snap-frame-id [shapes]
  (let [frames (into #{} (map :frame-id shapes))]
    (cond
      ;; Only shapes from one frame. The common is the only one
      (= 0 (count frames)) (first frames)

      ;; Frames doesn't contain zero. So we take the first frame
      (not (frames zero)) (-> shapes first :frame-id)

      ;; Otherwise the root frame is the common
      :else zero)))

(defn get-snap-points [page-id frame-id filter-shapes point coord]
  (let [value (get point coord)]
    (->> (uw/ask! {:cmd :snaps/range-query
                   :page-id page-id
                   :frame-id frame-id
                   :coord coord
                   :ranges [[(- value 0.5) (+ value 0.5)]]})
         (rx/first)
         (rx/map (remove-from-snap-points filter-shapes))
         (rx/map flatten-to-points))))

(defn- search-snap
  [page-id frame-id points coord filter-shapes zoom]
  (let [snap-accuracy (/ snap-accuracy zoom)
        ranges (->> points
                    (map coord)
                    (mapv #(vector (- % snap-accuracy)
                                   (+ % snap-accuracy))))]
    (->> (uw/ask! {:cmd :snaps/range-query
                   :page-id page-id
                   :frame-id frame-id
                   :coord coord
                   :ranges ranges})
         (rx/first)
         (rx/map (remove-from-snap-points filter-shapes))
         (rx/map (get-min-distance-snap points coord)))))

(defn snap->vector [[[from-x to-x] [from-y to-y]]]
  (when (or from-x to-x from-y to-y)
    (let [from (gpt/point (or from-x 0) (or from-y 0))
          to   (gpt/point (or to-x 0)   (or to-y 0))]
      (gpt/to-vec from to))))

(defn- closest-snap
  [page-id frame-id points filter-shapes zoom]
  (let [snap-x (search-snap page-id frame-id points :x filter-shapes zoom)
        snap-y (search-snap page-id frame-id points :y filter-shapes zoom)]
    (->> (rx/combine-latest snap-x snap-y)
         (rx/map snap->vector))))


(defn sr-distance [coord sr1 sr2]
  (let [c1 (if (= coord :x) :x1 :y1)
        c2 (if (= coord :x) :x2 :y2)
        dist (- (c1 sr2) (c2 sr1))]
    dist))

(defn overlap? [coord sr1 sr2]
  (let [c1 (if (= coord :x) :y1 :x1)
        c2 (if (= coord :x) :y2 :x2)
        s1c1 (c1 sr1)
        s1c2 (c2 sr1)
        s2c1 (c1 sr2)
        s2c2 (c2 sr2)]
    (or (and (>= s2c1 s1c1) (<= s2c1 s1c2))
        (and (>= s2c2 s1c1) (<= s2c2 s1c2))
        (and (>= s1c1 s2c1) (<= s1c1 s2c2))
        (and (>= s1c2 s2c1) (<= s1c2 s2c2)))))

(defn calculate-snap [coord selrect shapes-lt shapes-gt zoom]
  (let [snap-distance-accuracy (/ snap-distance-accuracy zoom)
        dist-lt (fn [other] (sr-distance coord (:selrect other) selrect))
        dist-gt (fn [other] (sr-distance coord selrect (:selrect other)))

        ;; Calculates the snap distance when in the middle of two shapes
        between-snap
        (fn [[sh-lt sh-gt]]
          ;; To calculate the middle snap.
          ;; Given x, the distance to a left shape and y to a right shape
          ;; x - v = y + v  =>  v = (x - y)/2
          ;; v will be the vector that we need to move the shape so it "snaps"
          ;; in the middle
          (/ (- (dist-gt sh-gt)
                (dist-lt sh-lt)) 2))

        ;; Calculates the distance between all the shapes given as argument
        inner-distance
        (fn [selrects]
          (->> selrects
               (sort-by coord)
               (d/map-perm #(sr-distance coord %1 %2)
                           #(overlap? coord %1 %2)
                           #{})))

        best-snap
        (fn [acc val]
          ;; Using a number is faster than accessing the variable.
          ;; Keep up to date with `snap-distance-accuracy`
          (if (and (<= val snap-distance-accuracy) (>= val (- snap-distance-accuracy)))
            (min acc val)
            acc))

        ;; Distance between the elements in an area, these are the snap
        ;; candidates to either side
        lt-cand (inner-distance (mapv :selrect shapes-lt))
        gt-cand (inner-distance (mapv :selrect shapes-gt))

        ;; Distance between the elements to either side and the current shape
        ;; this is the distance that will "snap"
        lt-dist (into #{} (map dist-lt) shapes-lt)
        gt-dist (into #{} (map dist-gt) shapes-gt)

        ;; Calculate the snaps, we need to reverse depending on area
        lt-snap (d/join lt-cand lt-dist -)
        gt-snap (d/join gt-dist gt-cand -)

        ;; Calculate snap-between
        between-snap (->> (d/join shapes-lt shapes-gt)
                          (map between-snap))

        ;; Search the minimum snap
        snap-list (-> [] (d/concat lt-snap) (d/concat gt-snap) (d/concat between-snap))

        min-snap (reduce best-snap ##Inf snap-list)]

    (if (mth/finite? min-snap) [0 min-snap] nil)))

(defn search-snap-distance [selrect coord shapes-lt shapes-gt zoom]
  (->> (rx/combine-latest shapes-lt shapes-gt)
       (rx/map (fn [[shapes-lt shapes-gt]]
                 (calculate-snap coord selrect shapes-lt shapes-gt zoom)))))

(defn select-shapes-area
  [page-id shapes objects area-selrect]
  (->> (uw/ask! {:cmd :selection/query
                 :page-id page-id
                 :frame-id (->> shapes first :frame-id)
                 :include-frames? true
                 :rect area-selrect})
       (rx/map #(cp/clean-loops objects %))
       (rx/map #(set/difference % (into #{} (map :id shapes))))
       (rx/map (fn [ids] (map #(get objects %) ids)))))

(defn closest-distance-snap
  [page-id shapes objects zoom movev]
  (let [frame-id (snap-frame-id shapes)
        frame (get objects frame-id)
        selrect (->> shapes (map #(gsh/move % movev)) gsh/selection-rect)]
    (->> (rx/of (vector frame selrect))
         (rx/merge-map
          (fn [[frame selrect]]
            (let [areas (->> (gsh/selrect->areas (or (:selrect frame)
                                                     (gsh/rect->selrect @refs/vbox)) selrect)
                             (d/mapm #(select-shapes-area page-id shapes objects %2)))
                  snap-x (search-snap-distance selrect :x (:left areas) (:right areas) zoom)
                  snap-y (search-snap-distance selrect :y (:top areas) (:bottom areas) zoom)]
              (rx/combine-latest snap-x snap-y))))
         (rx/map snap->vector))))

(defn closest-snap-point
  [page-id shapes layout zoom point]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        filter-shapes (fn [id] (if (= id :layout)
                                 (or (not (contains? layout :display-grid))
                                     (not (contains? layout :snap-grid)))
                                 (or (filter-shapes id)
                                     (not (contains? layout :dynamic-alignment)))))]
    (->> (closest-snap page-id frame-id [point] filter-shapes zoom)
         (rx/map #(or % (gpt/point 0 0)))
         (rx/map #(gpt/add point %)))))

(defn closest-snap-move
  [page-id shapes objects layout zoom movev]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        filter-shapes (fn [id] (if (= id :layout)
                                 (or (not (contains? layout :display-grid))
                                     (not (contains? layout :snap-grid)))
                                 (or (filter-shapes id)
                                     (not (contains? layout :dynamic-alignment)))))
        shape (if (> (count shapes) 1)
                (->> shapes (map gsh/transform-shape) gsh/selection-rect (gsh/setup {:type :rect}))
                (->> shapes (first)))

        shapes-points (->> shape
                           (sp/shape-snap-points)
                           ;; Move the points in the translation vector
                           (map #(gpt/add % movev)))]

    (->> (rx/merge (closest-snap page-id frame-id shapes-points filter-shapes zoom)
                   (when (contains? layout :dynamic-alignment)
                     (closest-distance-snap page-id shapes objects zoom movev)))
         (rx/reduce gpt/min)
         (rx/map #(or % (gpt/point 0 0))))))


;;; PATH SNAP

(defn create-ranges
  ([points]
   (create-ranges points #{}))

  ([points selected-points]
   (let [selected-points (or selected-points #{})

         into-tree
         (fn [coord]
           (fn [tree point]
             (rt/insert tree (get point coord) point)))

         make-ranges
         (fn [coord]
           (->> points
                (filter (comp not selected-points))
                (reduce (into-tree coord) (rt/make-tree))))]

     {:x (make-ranges :x)
      :y (make-ranges :y)})))

(defn query-delta-point [ranges point precision]
  (let [query-coord
        (fn [point coord]
          (let [pval (get point coord)]
            (->> (rt/range-query (get ranges coord) (- pval precision) (+ pval precision))
                 ;; We save the distance to the point and add the matching point to the points
                 (mapv (fn [[value points]]
                         [(- value pval)
                          (->> points (mapv #(vector point %)))])))))]
    {:x (query-coord point :x)
     :y (query-coord point :y)}))

(defn merge-matches
  ([] {:x nil :y nil})
  ([matches other]
   (let [merge-coord
         (fn [matches other]

           (let [matches (into {} matches)
                 other   (into {} other)
                 keys    (set/union (set (keys matches))
                                    (set (keys other)))]
             (into {}
                   (map (fn [key]
                          [key
                           (d/concat [] (get matches key []) (get other key []))]))
                   keys)))]

     (-> matches
         (update :x merge-coord (:x other))
         (update :y merge-coord (:y other))))))

(defn min-match
  [default matches]
  (let [get-min
        (fn [[cur-val :as current] [other-val :as other]]
          (if (< (mth/abs cur-val) (mth/abs other-val))
            current
            other))

        min-match-coord
        (fn [matches]
          (if (seq matches)
            (->> matches (reduce get-min))
            default))]

    (-> matches
        (update :x min-match-coord)
        (update :y min-match-coord))))

(defn get-snap-delta-match
  [points ranges accuracy]
  (assert vector? points)

  (->> points
       (mapv #(query-delta-point ranges % accuracy))
       (reduce merge-matches)
       (min-match [0 nil])))

(defn get-snap-delta
  [points ranges accuracy]
  (-> (get-snap-delta-match points ranges accuracy)
      (update :x first)
      (update :y first)
      (gpt/point)))


(defn correct-snap-point
  "Snaps a position given an old snap to a different position. We use this to provide a temporal
  snap while the new is being processed."
  [[position [snap-pos snap-delta]]]
  (if (some? snap-delta)
    (let [dx (if (not= 0 (:x snap-delta))
               (- (+ (:x snap-pos) (:x snap-delta)) (:x position))
               0)
          dy (if (not= 0 (:y snap-delta))
               (- (+ (:y snap-pos) (:y snap-delta)) (:y position))
               0)]

      (cond-> position
        (<= (mth/abs dx) snap-accuracy) (update :x + dx)
        (<= (mth/abs dy) snap-accuracy) (update :y + dy)))
    position))

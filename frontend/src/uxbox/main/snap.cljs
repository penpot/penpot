;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.snap
  (:require
   [clojure.set :as set]
   [beicon.core :as rx]
   [uxbox.common.uuid :refer [zero]]
   [uxbox.common.math :as mth]
   [uxbox.common.data :as d]
   [uxbox.common.geom.point :as gpt]
   [uxbox.common.geom.shapes :as gsh]
   [uxbox.main.worker :as uw]
   [uxbox.main.refs :as refs]
   [uxbox.util.geom.snap-points :as sp]))

(def ^:private snap-accuracy 5)
(def ^:private snap-distance-accuracy 10)

(defn- remove-from-snap-points [remove-id?]
  (fn [query-result]
    (->> query-result
         (map (fn [[value data]] [value (remove (comp remove-id? second) data)]))
         (filter (fn [[_ data]] (not (empty? data)))))))

(defn- flatten-to-points
  [query-result]
  (mapcat (fn [[v data]] (map (fn [[point _]] point) data)) query-result))

(defn- calculate-distance [query-result point coord]
  (->> query-result
       (map (fn [[value data]] [(mth/abs (- value (coord point))) [(coord point) value]]))))

(defn- get-min-distance-snap [points coord]
  (fn [query-result]
    (->> points
         (mapcat #(calculate-distance query-result % coord))
         (apply min-key first)
         second)))

(defn- snap-frame-id [shapes]
  (let [frames (into #{} (map :frame-id shapes))]
    (cond
      ;; Only shapes from one frame. The common is the only one
      (= 0 (count frames)) (first frames)

      ;; Frames doesn't contain zero. So we take the first frame
      (not (frames zero)) (-> shapes first :frame-id)

      ;; Otherwise the root frame is the common
      :else zero)))

(defn get-snap-points [page-id frame-id filter-shapes point coord]
  (let [value (coord point)]
    (->> (uw/ask! {:cmd :snaps/range-query
                   :page-id page-id
                   :frame-id frame-id
                   :coord coord
                   :ranges [[(- value 1) (+ value 1)]]})
         (rx/first)
         (rx/map (remove-from-snap-points filter-shapes))
         (rx/map flatten-to-points))))

(defn- search-snap
  [page-id frame-id points coord filter-shapes]
  (let [ranges (->> points
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

(defn snap->vector [[from-x to-x] [from-y to-y]]
  (when (or from-x to-x from-y to-y)
    (let [from (gpt/point (or from-x 0) (or from-y 0))
          to   (gpt/point (or to-x 0)   (or to-y 0))]
      (gpt/to-vec from to))))

(defn- closest-snap
  [page-id frame-id points filter-shapes]
  (let [snap-x (search-snap page-id frame-id points :x filter-shapes)
        snap-y (search-snap page-id frame-id points :y filter-shapes)]
    ;; snap-x is the second parameter because is the "source" to combine
    (rx/combine-latest snap->vector snap-y snap-x)))

(defn search-snap-distance [selrect coord shapes-lt shapes-gt]
  (let [dist (fn [[sh1 sh2]] (-> sh1 (gsh/distance-shapes sh2) coord))
        dist-lt (fn [other] (-> (:selrect other) (gsh/distance-selrect selrect) coord))
        dist-gt (fn [other] (-> selrect (gsh/distance-selrect (:selrect other)) coord))

        ;; Calculates the distance between all the shapes given as argument
        inner-distance (fn [shapes]
                         (->> shapes
                              (sort-by coord)
                              (d/map-perm vector)
                              (filter (fn [[sh1 sh2]] (gsh/overlap-coord? coord sh1 sh2)))
                              (map dist)
                              (filter #(> % 0))))

        ;; Calculates the snap distance when in the middle of two shapes
        between-snap (fn [[sh-lt sh-gt]]
                       ;; To calculate the middle snap.
                       ;; Given x, the distance to a left shape and y to a right shape
                       ;; x - v = y + v  =>  v = (x - y)/2
                       ;; v will be the vector that we need to move the shape so it "snaps"
                       ;; in the middle
                       (/ (- (dist-gt sh-gt)
                             (dist-lt sh-lt)) 2))
        ]
    (->> shapes-lt
         (rx/combine-latest vector shapes-gt)
         (rx/map (fn [[shapes-lt shapes-gt]]
                   (let [;; Distance between the elements in an area, these are the snap
                         ;; candidates to either side
                         lt-cand (inner-distance shapes-lt)
                         gt-cand (inner-distance shapes-gt)

                         ;; Distance between the elements to either side and the current shape
                         ;; this is the distance that will "snap"
                         lt-dist (map dist-lt shapes-lt)
                         gt-dist (map dist-gt shapes-gt)

                         ;; Calculate the snaps, we need to reverse depending on area
                         lt-snap (d/join lt-cand lt-dist  -)
                         gt-snap (d/join gt-dist gt-cand  -)

                         ;; Calculate snap-between
                         between-snap (->> (d/join shapes-lt shapes-gt)
                                           (map between-snap))

                         ;; Search the minimum snap
                         min-snap (->> (concat lt-snap gt-snap between-snap)
                                       (filter #(<= (mth/abs %) snap-distance-accuracy))
                                       (reduce min ##Inf))]

                     (if (mth/finite? min-snap) [0 min-snap] nil)))))))

(defn select-shapes-area [page-id shapes objects area-selrect]
  (->> (uw/ask! {:cmd :selection/query
                 :page-id page-id
                 :rect area-selrect})
       (rx/map #(set/difference % (into #{} (map :id shapes))))
       (rx/map (fn [ids] (map #(get objects %) ids)))))

(defn closest-distance-snap [page-id shapes objects movev]
  (->> (rx/of shapes)
       (rx/map #(vector (->> % first :frame-id (get objects))
                        (-> % gsh/selection-rect (gsh/move movev))))
       (rx/merge-map
        (fn [[frame selrect]]
          (let [areas (->> (gsh/selrect->areas (or (:selrect frame) (gsh/rect->rect-shape @refs/vbox)) selrect)
                           (d/mapm #(select-shapes-area page-id shapes objects %2)))
                snap-x (search-snap-distance selrect :x (:left areas) (:right areas))
                snap-y (search-snap-distance selrect :y (:top areas) (:bottom areas))]
            (rx/combine-latest snap->vector snap-y snap-x))))))

(defn closest-snap-point
  [page-id shapes layout point]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        filter-shapes (fn [id] (if (= id :layout)
                                 (or (not (contains? layout :display-grid))
                                     (not (contains? layout :snap-grid)))
                                 (or (filter-shapes id)
                                     (not (contains? layout :dynamic-alignment)))))]
    (->> (closest-snap page-id frame-id [point] filter-shapes)
         (rx/map #(or % (gpt/point 0 0)))
         (rx/map #(gpt/add point %))
         (rx/map gpt/round))))

(defn closest-snap-move
  [page-id shapes objects layout movev]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        filter-shapes (fn [id] (if (= id :layout)
                                 (or (not (contains? layout :display-grid))
                                     (not (contains? layout :snap-grid)))
                                 (or (filter-shapes id)
                                     (not (contains? layout :dynamic-alignment)))))
        shapes-points (->> shapes
                           ;; Unroll all the possible snap-points
                           (mapcat (partial sp/shape-snap-points))

                           ;; Move the points in the translation vector
                           (map #(gpt/add % movev)))]
    (->> (rx/merge (closest-snap page-id frame-id shapes-points filter-shapes)
                   (when (contains? layout :dynamic-alignment)
                     (closest-distance-snap page-id shapes objects movev)))
         (rx/reduce gpt/min)
         (rx/map #(or % (gpt/point 0 0)))
         (rx/map #(gpt/add movev %)))))

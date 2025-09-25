;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.snap
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.focus :as cpf]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.snap :as sp]
   [app.common.math :as mth]
   [app.common.uuid :refer [zero]]
   [app.main.refs :as refs]
   [app.main.worker :as mw]
   [app.util.range-tree :as rt]
   [beicon.v2.core :as rx]
   [clojure.set :as set]))

(def ^:const snap-accuracy 10)
(def ^:const snap-path-accuracy 10)
(def ^:const snap-distance-accuracy 20)

(defn- remove-from-snap-points
  [remove-snap?]
  (fn [query-result]
    (->> query-result
         (map (fn [[value data]] [value (remove remove-snap? data)]))
         (filter (fn [[_ data]] (seq data))))))

(defn make-remove-snap
  "Creates a filter for the snap data. Used to disable certain layouts"
  [layout filter-shapes objects focus]

  (fn [{:keys [type id frame-id]}]
    (cond
      (= type :layout)
      (or (not (contains? layout :display-guides))
          (not (contains? layout :snap-guides))
          (and (d/not-empty? focus)
               (not (contains? focus id))))

      (= type :guide)
      (or (not (contains? layout :rulers))
          (not (contains? layout :snap-ruler-guides))
          (and (d/not-empty? focus)
               (not (contains? focus frame-id))))

      :else
      (or (contains? filter-shapes id)
          (not (contains? layout :dynamic-alignment))
          (and (d/not-empty? focus)
               (not (cpf/is-in-focus? objects focus id)))))))

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

(defn get-snap-points [page-id frame-id remove-snap? zoom point coord]
  (let [value (get point coord)
        vbox @refs/vbox
        ranges [[(- value (/ 0.5 zoom)) (+ value (/ 0.5 zoom))]]]
    (->> (mw/ask! {:cmd :index/query-snap
                   :page-id page-id
                   :frame-id frame-id
                   :axis coord
                   :bounds vbox
                   :ranges ranges})
         (rx/take 1)
         (rx/map (remove-from-snap-points remove-snap?)))))

(defn- search-snap
  [page-id frame-id points coord remove-snap? zoom]
  (let [snap-accuracy (/ snap-accuracy zoom)
        ranges (->> points
                    (map coord)
                    (mapv #(vector (- % snap-accuracy)
                                   (+ % snap-accuracy))))
        vbox @refs/vbox]
    (->> (mw/ask! {:cmd :index/query-snap
                   :page-id page-id
                   :frame-id frame-id
                   :axis coord
                   :bounds vbox
                   :ranges ranges})
         (rx/take 1)
         (rx/map (remove-from-snap-points remove-snap?))
         (rx/map (get-min-distance-snap points coord)))))

(defn snap->vector [[[from-x to-x] [from-y to-y]]]
  (when (or from-x to-x from-y to-y)
    (let [from (gpt/point (or from-x 0) (or from-y 0))
          to   (gpt/point (or to-x 0)   (or to-y 0))]
      (gpt/to-vec from to))))

(defn- closest-snap
  [page-id frame-id points remove-snap? zoom]
  (let [snap-x (search-snap page-id frame-id points :x remove-snap? zoom)
        snap-y (search-snap page-id frame-id points :y remove-snap? zoom)]
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

        ;; Calculates the distance between all the shapes given as argument
        inner-distance
        (fn [selrects]
          (->> selrects
               (sort-by coord)
               (d/map-perm #(sr-distance coord %1 %2)
                           #(overlap? coord %1 %2)
                           #{})))

        ;; Distance between the elements in an area, these are the snap
        ;; candidates to either side
        lt-cand (inner-distance (mapv :selrect shapes-lt))
        gt-cand (inner-distance (mapv :selrect shapes-gt))

        ;; Distance between the elements to either side and the current shape
        ;; this is the distance that will "snap"
        lt-dist (into #{} (map dist-lt) shapes-lt)
        gt-dist (into #{} (map dist-gt) shapes-gt)

        get-side-snaps
        (fn [candidates distances]
          ;; We add to the range tree the distrances between elements
          ;; then, for each distance from the selection we query the tree
          ;; to find a snap
          (let [range-tree (rt/make-tree)
                range-tree
                (->> candidates
                     (reduce #(rt/insert %1 %2 %2) range-tree))]
            (->> distances
                 (mapcat
                  (fn [cd]
                    (->> (rt/range-query
                          range-tree
                          (- cd snap-distance-accuracy)
                          (+ cd snap-distance-accuracy))
                         (map #(- (first %) cd))))))))

        get-middle-snaps
        (fn [lt-dist gt-dist]
          (let [range-tree (rt/make-tree)
                range-tree (->> lt-dist
                                (reduce #(rt/insert %1 %2 %2) range-tree))]
            (->> gt-dist
                 (mapcat (fn [cd]
                           (->> (rt/range-query
                                 range-tree
                                 (- cd (* snap-distance-accuracy 2))
                                 (+ cd (* snap-distance-accuracy 2)))
                                (map #(/ (- cd (first %)) 2))))))))

        ;; Calculate the snaps, we need to reverse depending on area
        lt-snap (get-side-snaps lt-cand lt-dist)
        gt-snap (get-side-snaps gt-dist gt-cand)
        md-snap (get-middle-snaps lt-dist gt-dist)

        ;; Search the minimum snap
        snap-list (d/concat-vec lt-snap gt-snap md-snap)

        min-snap  (reduce min ##Inf snap-list)]

    (if (d/num? min-snap) [0 min-snap] nil)))

(defn search-snap-distance [selrect coord shapes-lt shapes-gt zoom]
  (->> (rx/combine-latest shapes-lt shapes-gt)
       (rx/map
        (fn [[shapes-lt shapes-gt]]
          (calculate-snap coord selrect shapes-lt shapes-gt zoom)))))

(defn select-shapes-area
  [page-id frame-id selected objects area]
  (->> (mw/ask! {:cmd :index/query-selection
                 :page-id page-id
                 :frame-id frame-id
                 :include-frames? true
                 :rect area})
       (rx/map #(cfh/clean-loops objects %))
       (rx/map #(set/difference % selected))
       (rx/map #(map (d/getf objects) %))))

(defn closest-distance-snap
  [page-id shapes objects zoom movev]
  (let [frame-id (snap-frame-id shapes)
        frame (get objects frame-id)
        selrect (->> shapes (map #(gsh/move % movev)) gsh/shapes->rect)]
    (->> (rx/of (vector frame selrect))
         (rx/merge-map
          (fn [[frame selrect]]
            (let [vbox     (deref refs/vbox)

                  frame-id (->> shapes first :frame-id)
                  frame-sr (when-not (cfh/root? frame) (dm/get-prop frame :selrect))
                  bounds (d/nilv (grc/clip-rect frame-sr vbox) vbox)
                  selected (into #{} (map :id shapes))
                  areas (->> (gsh/get-areas bounds selrect)
                             (d/mapm #(select-shapes-area page-id frame-id selected objects %2)))
                  snap-x (search-snap-distance selrect :x (:left areas) (:right areas) zoom)
                  snap-y (search-snap-distance selrect :y (:top areas) (:bottom areas) zoom)]
              (rx/combine-latest snap-x snap-y))))
         (rx/map snap->vector))))

(defn closest-snap-point
  [page-id shapes objects layout zoom focus point]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        remove-snap? (make-remove-snap layout filter-shapes objects focus)]
    (->> (closest-snap page-id frame-id [point] remove-snap? zoom)
         (rx/map #(or % (gpt/point 0 0)))
         (rx/map #(gpt/add point %)))))

(defn combine-snaps-points
  ([] nil)
  ([p1] p1)
  ([p1 p2]
   (cond
     (nil? p2) p1
     (nil? p1) p2

     :else
     (gpt/point (mth/max-abs (:x p1) (:x p2))
                (mth/max-abs (:y p1) (:y p2))))))

(defn closest-snap-move
  [page-id shapes objects layout zoom focus movev]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        remove-snap? (make-remove-snap layout filter-shapes objects focus)

        snap-points
        (->> shapes
             (gsh/shapes->rect)
             (sp/rect->snap-points)
             ;; Move the points in the translation vector
             (map #(gpt/add % movev)))]

    (->> (rx/merge (closest-snap page-id frame-id snap-points remove-snap? zoom)
                   (when (contains? layout :dynamic-alignment)
                     (closest-distance-snap page-id shapes objects zoom movev)))
         (rx/reduce combine-snaps-points)
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
    (gpt/point (query-coord point :x)
               (query-coord point :y))))

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
                          [key (d/concat-vec (get matches key []) (get other key []))]))
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
  (if (nil? snap-delta)
    position
    (let [dx (if (not= 0 (:x snap-delta))
               (- (+ (:x snap-pos) (:x snap-delta)) (:x position))
               0)
          dy (if (not= 0 (:y snap-delta))
               (- (+ (:y snap-pos) (:y snap-delta)) (:y position))
               0)

          ;; If the deltas (dx,dy) are bigger than the snap-accuracy means the stored snap
          ;; is not valid, so we change to 0
          dx (if (> (mth/abs dx) snap-accuracy) 0 dx)
          dy (if (> (mth/abs dy) snap-accuracy) 0 dy)]
      (-> position
          (update :x + dx)
          (update :y + dy)))))

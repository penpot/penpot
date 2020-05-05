;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.geom.snap
  (:require
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [uxbox.util.math :as mth]
   [uxbox.common.uuid :refer [zero]]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.util.geom.point :as gpt]))

(def ^:private snap-accuracy 20)

(defn mapm
  "Map over the values of a map"
  [mfn coll]
  (into {} (map (fn [[key val]] [key (mfn val)]) coll)))

(defn- frame-snap-points [{:keys [x y width height]}]
  #{(gpt/point x y)
    (gpt/point (+ x (/ width 2)) y)
    (gpt/point (+ x width) y)
    (gpt/point (+ x width) (+ y (/ height 2)))
    (gpt/point (+ x width) (+ y height))
    (gpt/point (+ x (/ width 2)) (+ y height))
    (gpt/point x (+ y height))
    (gpt/point x (+ y (/ height 2)))})

(defn- frame-snap-points-resize [{:keys [x y width height]} handler]
  (case handler
    :top-left (gpt/point x y)
    :top (gpt/point (+ x (/ width 2)) y)
    :top-right (gpt/point (+ x width) y)
    :right (gpt/point (+ x width) (+ y (/ height 2)))
    :bottom-right (gpt/point (+ x width) (+ y height))
    :bottom (gpt/point (+ x (/ width 2)) (+ y height))
    :bottom-left (gpt/point x (+ y height))
    :left (gpt/point x (+ y (/ height 2)))))

(def ^:private handler->point-idx
  {:top-left 0
   :top 0
   :top-right 1
   :right 1
   :bottom-right 2
   :bottom 2
   :bottom-left 3
   :left 3})

(defn shape-snap-points-resize
  [handler shape]
  (let [modified-path (gsh/transform-apply-modifiers shape)
        point-idx (handler->point-idx handler)]
    #{(case (:type shape)
        :frame (frame-snap-points-resize shape handler)
        (:path :curve) (-> modified-path gsh/shape->rect-shape :segments (nth point-idx))
        (-> modified-path :segments (nth point-idx)))}))

(defn shape-snap-points
  [shape]
  (let [modified-path (gsh/transform-apply-modifiers shape)
        shape-center (gsh/center modified-path)]
    (case (:type shape)
      :frame (frame-snap-points shape)
      (:path :curve) (into #{shape-center} (-> modified-path gsh/shape->rect-shape :segments))
      (into #{shape-center} (-> modified-path :segments)))))

(defn create-coord-data [shapes coord]
  (let [process-shape
        (fn [coord]
          (fn [shape]
            (let [points (shape-snap-points shape)]
              (map #(vector % (:id shape)) points))))]
    (->> shapes
         (mapcat (process-shape coord))
         (group-by (comp coord first)))))

(defn initialize-snap-data
  "Initialize the snap information with the current workspace information"
  [objects]
  (let [shapes (vals objects)
        frame-shapes (->> shapes
                          (filter (comp not nil? :frame-id))
                          (group-by :frame-id))

        frame-shapes (->> shapes
                          (filter #(= :frame (:type %)))
                          (remove #(= zero (:id %)))
                          (reduce #(update %1 (:id %2) conj %2) frame-shapes))]
    (mapm (fn [shapes] {:x (create-coord-data shapes :x)
                        :y (create-coord-data shapes :y)})
          frame-shapes)))

(defn range-query
  "Queries the snap-data within a range of values"
  [snap-data from-value to-value]
  (filter (fn [[value _]] (and (>= value from-value)
                               (<= value to-value)))
          snap-data))

(defn remove-from-snap-points [snap-points ids-to-remove]
  (->> snap-points
       (map (fn [[value data]] [value (remove (comp ids-to-remove second) data)]))
       (filter (fn [[_ data]] (not (empty? data))))))

(defn search-snap-point
  "Search snap for a single point"
  [point coord snap-data filter-shapes]
  
  (let [coord-value (get point coord)

        ;; This gives a list of [value [[point1 uuid1] [point2 uuid2] ...] we need to remove
        ;; the shapes in filter shapes
        candidates (-> snap-data
                       (range-query (- coord-value snap-accuracy) (+ coord-value snap-accuracy))
                       (remove-from-snap-points filter-shapes))

        ;; Now return with the distance and the from-to pair that we'll return if this is the chosen
        point-snaps (map (fn [[cand-value data]] [(mth/abs (- coord-value cand-value)) [coord-value cand-value]]) candidates)]
    point-snaps))

(defn search-snap
  "Search a snap point in one axis `snap-data` contains the information to make the snap.
  `points` are the points that we need to search for a snap and `filter-shapes` is a set of uuids
  containgin the shapes that should be ignored to get a snap (usually because they are being moved)"
  [points coord snap-data filter-shapes]

  (let [snap-points (mapcat #(search-snap-point % coord snap-data filter-shapes) points)
        result (->> snap-points (apply min-key first) second)]
    (or result [0 0])))

(defn snap-frame-id [shapes]
  (let [frames (into #{} (map :frame-id shapes))]
    (cond
      ;; Only shapes from one frame. The common is the only one
      (= 0 (count frames)) (first frames)

      ;; Frames doesn't contain zero. So we take the first frame
      (not (frames zero)) (-> shapes first :frame-id)
      
      ;; Otherwise the root frame is the common
      :else zero)))

(defn- closest-snap
  [snap-data shapes trans-vec shapes-points]
  (let [;; Get the common frame-id to make the snap
        frame-id (snap-frame-id shapes)

        ;; We don't want to snap to the shapes currently transformed
        remove-shapes (into #{} (map :id shapes))

        ;; The snap is a tuple. The from is the point in the current moving shape
        ;; the "to" is the point where we'll snap. So we need to create a vector
        ;; snap-from --> snap-to and move the position in that vector
        [snap-from-x snap-to-x] (search-snap shapes-points :x (get-in snap-data [frame-id :x]) remove-shapes)
        [snap-from-y snap-to-y] (search-snap shapes-points :y (get-in snap-data [frame-id :y]) remove-shapes)

        snapv (gpt/to-vec (gpt/point snap-from-x snap-from-y)
                          (gpt/point snap-to-x snap-to-y))]

    (gpt/add trans-vec snapv)))

(defn closest-snap-point
  [snap-data shapes point]
  (closest-snap snap-data shapes point [point]))

(defn closest-snap-move
  ([snap-data shapes] (partial closest-snap-move snap-data shapes))
  ([snap-data shapes trans-vec]
   (let [shapes-points (->> shapes
                            ;; Unroll all the possible snap-points
                            (mapcat (partial shape-snap-points))

                            ;; Move the points in the translation vector
                            (map #(gpt/add % trans-vec)))]
     (closest-snap snap-data shapes trans-vec shapes-points))))

(defn get-snap-points [snap-data frame-id filter-shapes point coord]
  (let [value (coord point)

        ;; Search for values within 1 pixel
        snap-matches (-> (get-in snap-data [frame-id coord])
                        (range-query (- value 1) (+ value 1))
                        (remove-from-snap-points filter-shapes))

        snap-points (mapcat (fn [[v data]] (map (fn [[point _]] point) data)) snap-matches)]
    snap-points))

(defn is-snapping? [snap-data frame-id shape-id point coord]
  (let [value (coord point)
        ;; Search for values within 1 pixel
        snap-points (range-query (get-in snap-data [frame-id coord]) (- value 1.0) (+ value 1.0))]
    (some (fn [[point other-shape-id]] (not (= shape-id other-shape-id))) snap-points)))

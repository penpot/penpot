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
   [beicon.core :as rx]
   [uxbox.util.math :as mth]
   [uxbox.common.uuid :refer [zero]]
   [uxbox.util.geom.shapes :as gsh]
   [uxbox.util.geom.point :as gpt]
   [uxbox.main.worker :as uw]))

(def ^:private snap-accuracy 10)

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

(defn shape-snap-points
  [shape]
  (let [modified-path (gsh/transform-apply-modifiers shape)
        shape-center (gsh/center modified-path)]
    (case (:type shape)
      :frame (-> modified-path gsh/shape->rect-shape frame-snap-points)
      (:path :curve) (into #{shape-center} (-> modified-path gsh/shape->rect-shape :segments))
      (into #{shape-center} (-> modified-path :segments)))))


(defn remove-from-snap-points [ids-to-remove]
  (fn [query-result]
    (->> query-result
         (map (fn [[value data]] [value (remove (comp ids-to-remove second) data)]))
         (filter (fn [[_ data]] (not (empty? data)))))))

(defn- calculate-distance [query-result point coord]
  (->> query-result
       (map (fn [[value data]] [(mth/abs (- value (coord point))) [(coord point) value]]))))

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

(defn flatten-to-points
  [query-result]
  (mapcat (fn [[v data]] (map (fn [[point _]] point) data)) query-result))

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

(defn- closest-snap
  [page-id frame-id points filter-shapes]
  (let [snap-x (search-snap page-id frame-id points :x filter-shapes)
        snap-y (search-snap page-id frame-id points :y filter-shapes)
        snap-as-vector (fn [[from-x to-x] [from-y to-y]]
                         (let [from (gpt/point (or from-x 0) (or from-y 0))
                               to   (gpt/point (or to-x 0)   (or to-y 0))]
                           (gpt/to-vec from to)))]
    ;; snap-x is the second parameter because is the "source" to combine
    (rx/combine-latest snap-as-vector snap-y snap-x)))

(defn closest-snap-point
  [page-id shapes point]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))]
    (->> (closest-snap page-id frame-id [point] filter-shapes)
         (rx/map #(gpt/add point %)))))

(defn closest-snap-move
  [page-id shapes movev]
  (let [frame-id (snap-frame-id shapes)
        filter-shapes (into #{} (map :id shapes))
        shapes-points (->> shapes
                           ;; Unroll all the possible snap-points
                           (mapcat (partial shape-snap-points))

                           ;; Move the points in the translation vector
                           (map #(gpt/add % movev)))]
    (->> (closest-snap page-id frame-id shapes-points filter-shapes)
         (rx/map #(gpt/add movev %)))))

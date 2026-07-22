;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.path.selection
  "Transforms selected path nodes and handlers."
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as impl]))

(def align-nodes-axis
  "Valid alignment axes."
  #{:hleft :hcenter :hright :vtop :vcenter :vbottom})

(def distribute-nodes-axis
  "Valid distribution axes."
  #{:horizontal :vertical})

(defn- selected-node-entries
  "Returns selected nodes as `[index point]` pairs."
  [content indices]
  (into []
        (comp (filter (fn [[i seg]]
                        (and (contains? indices i)
                             (not= :close-path (:command seg)))))
              (map (fn [[i seg]] [i (helpers/segment->point seg)])))
        (d/enumerate content)))

(defn- expand-coincident-node-indices
  "Includes every command that represents a selected logical node."
  [content indices]
  (let [indices (set indices)
        points  (into #{} (map second) (selected-node-entries content indices))]
    (into indices
          (comp
           (filter (fn [[_ segment]]
                     (and (not= :close-path (:command segment))
                          (contains? points (helpers/segment->point segment)))))
           (map first))
          (d/enumerate content))))

(defn- translate-nodes
  "Moves selected nodes and handlers by their node deltas."
  [content indices deltas]
  (let [node-sel? (fn [i] (contains? indices i))
        add-delta (fn [params xk yk delta]
                    (if (and delta (contains? params xk))
                      (-> params
                          (update xk + (:x delta))
                          (update yk + (:y delta)))
                      params))
        move-cmd  (fn [i {:keys [command params] :as seg}]
                    (let [curve? (= :curve-to command)
                          params (cond-> params
                                   (and (not= :close-path command) (node-sel? i))
                                   (add-delta :x :y (get deltas i))

                                   (and curve? (node-sel? (dec i)))
                                   (add-delta :c1x :c1y (get deltas (dec i)))

                                   (and curve? (node-sel? i))
                                   (add-delta :c2x :c2y (get deltas i)))]
                      (assoc seg :params params)))]
    (into [] (map-indexed move-cmd) content)))

(defn flip-content
  "Flips selected nodes and handlers across their bounds."
  [content indices axis]
  (let [content   (vec content)
        indices   (set indices)
        node-sel? (fn [i] (contains? indices i))
        positions (into []
                        (comp (filter (fn [[i seg]]
                                        (and (node-sel? i)
                                             (not= :close-path (:command seg)))))
                              (map (fn [[_ seg]] (helpers/segment->point seg))))
                        (d/enumerate content))]
    (if (empty? positions)
      (impl/from-plain content)
      (let [xs      (map :x positions)
            ys      (map :y positions)
            cx      (/ (+ (reduce min xs) (reduce max xs)) 2.0)
            cy      (/ (+ (reduce min ys) (reduce max ys)) 2.0)
            flip-x? (= axis :horizontal)
            reflect (fn [params xk yk]
                      (if flip-x?
                        (cond-> params
                          (contains? params xk) (update xk #(- (* 2.0 cx) %)))
                        (cond-> params
                          (contains? params yk) (update yk #(- (* 2.0 cy) %)))))
            flip-cmd (fn [i {:keys [command params] :as seg}]
                       (let [curve? (= :curve-to command)
                             params (cond-> params
                                      (and (not= :close-path command) (node-sel? i))
                                      (reflect :x :y)

                                      (and curve? (node-sel? (dec i)))
                                      (reflect :c1x :c1y)

                                      (and curve? (node-sel? i))
                                      (reflect :c2x :c2y))]
                         (assoc seg :params params)))]
        (impl/from-plain
         (into [] (map-indexed flip-cmd) content))))))

(defn align-content
  "Aligns two or more selected nodes within their bounds."
  [content indices axis]
  (let [content (vec content)
        indices (set indices)
        entries (selected-node-entries content indices)]
    (if (< (count entries) 2)
      (impl/from-plain content)
      (let [pts  (map second entries)
            xs   (map :x pts)
            ys   (map :y pts)
            minx (reduce min xs)
            maxx (reduce max xs)
            miny (reduce min ys)
            maxy (reduce max ys)
            [coord target] (case axis
                             :hleft   [:x minx]
                             :hcenter [:x (/ (+ minx maxx) 2.0)]
                             :hright  [:x maxx]
                             :vtop    [:y miny]
                             :vcenter [:y (/ (+ miny maxy) 2.0)]
                             :vbottom [:y maxy])
            deltas (into {}
                         (map (fn [[i p]]
                                [i (if (= coord :x)
                                     (gpt/point (- target (:x p)) 0)
                                     (gpt/point 0 (- target (:y p))))]))
                         entries)]
        (impl/from-plain (translate-nodes content indices deltas))))))

(defn set-nodes-coordinate
  "Sets one coordinate of selected nodes and handlers."
  [content indices axis value]
  (let [content (vec content)
        indices (expand-coincident-node-indices content indices)
        entries (selected-node-entries content indices)
        deltas  (into {}
                      (map (fn [[i p]]
                             [i (if (= axis :x)
                                  (gpt/point (- value (:x p)) 0)
                                  (gpt/point 0 (- value (:y p))))]))
                      entries)]
    (impl/from-plain (translate-nodes content indices deltas))))

(defn set-handler-points
  "Moves handlers to their target points."
  [content pts]
  (impl/from-plain
   (reduce
    (fn [content [[index prefix] pt]]
      (if (= :curve-to (:command (get content index)))
        (let [[cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
          (-> content
              (assoc-in [index :params cx] (:x pt))
              (assoc-in [index :params cy] (:y pt))))
        content))
    (vec content)
    pts)))

(defn translate-selected-nodes
  "Moves selected nodes and handlers by `delta`."
  [content indices delta]
  (let [content (vec content)
        indices (expand-coincident-node-indices content indices)]
    (impl/from-plain
     (translate-nodes content indices (into {} (map (fn [i] [i delta])) indices)))))

(defn distribute-content
  "Distributes three or more selected positions along `axis`."
  [content indices axis]
  (let [content     (vec content)
        indices     (set indices)
        entries     (selected-node-entries content indices)
        horizontal? (= axis :horizontal)
        coord       (fn [p] (if horizontal? (:x p) (:y p)))
        groups      (->> entries
                         (group-by (fn [[_ p]] [(:x p) (:y p)]))
                         (mapv (fn [[_ es]]
                                 {:point   (second (first es))
                                  :indices (mapv first es)})))]
    (if (< (count groups) 3)
      (impl/from-plain content)
      (let [sorted (sort-by (comp coord :point) groups)
            lo     (coord (:point (first sorted)))
            hi     (coord (:point (last sorted)))
            step   (/ (- hi lo) (dec (count sorted)))
            deltas (into {}
                         (comp
                          (map-indexed
                           (fn [k {:keys [point indices]}]
                             (let [target (+ lo (* k step))
                                   d      (- target (coord point))
                                   dp     (if horizontal?
                                            (gpt/point d 0)
                                            (gpt/point 0 d))]
                               (map (fn [i] [i dp]) indices))))
                          cat)
                         sorted)]
        (impl/from-plain (translate-nodes content indices deltas))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.snaps
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.util.geom.grid :as gg]
   [app.util.geom.snap-points :as snap]
   [app.util.range-tree :as rt]
   [app.worker.impl :as impl]
   [clojure.set :as set]
   [okulary.core :as l]))

(defonce state (l/atom {}))

(defn process-shape [frame-id coord]
  (fn [shape]
    (let [points (snap/shape-snap-points shape)
          shape-data (->> points (mapv #(vector % (:id shape))))]
      (if (= (:id shape) frame-id)
        (d/concat
         shape-data

         ;; The grid points are only added by the "root" of the coord-dat
         (->> (gg/grid-snap-points shape coord)
              (map #(vector % :layout))))

        shape-data))))

(defn- add-coord-data
  "Initializes the range tree given the shapes"
  [data frame-id shapes coord]
  (letfn [(into-tree [tree [point _ :as data]]
            (rt/insert tree (coord point) data))]
    (->> shapes
         (mapcat (process-shape frame-id coord))
         (reduce into-tree (or data (rt/make-tree))))))

(defn remove-coord-data
  [data frame-id shapes coord]
  (letfn [(remove-tree [tree [point _ :as data]]
            (rt/remove tree (coord point) data))]
    (->> shapes
         (mapcat (process-shape frame-id coord))
         (reduce remove-tree (or data (rt/make-tree))))))

(defn aggregate-data
  ([objects]
   (aggregate-data objects (keys objects)))

  ([objects ids]
   (->> ids
        (filter #(contains? objects %))
        (map #(get objects %))
        (filter :frame-id)
        (group-by :frame-id)
        ;; Adds the frame
        (d/mapm #(conj %2 (get objects %1))))))

(defn- initialize-snap-data
  "Initialize the snap information with the current workspace information"
  [objects]
  (let [shapes-data (aggregate-data objects)

        create-index
        (fn [frame-id shapes]
          {:x (-> (rt/make-tree) (add-coord-data frame-id shapes :x))
           :y (-> (rt/make-tree) (add-coord-data frame-id shapes :y))})]

    (d/mapm create-index shapes-data)))

(defn- update-snap-data
  [snap-data old-objects new-objects]

  (let [changed? #(not= (get old-objects %) (get new-objects %))
        is-deleted-frame? #(and (not= uuid/zero %)
                                (contains? old-objects %)
                                (not (contains? new-objects %))
                                (= :frame (get-in old-objects [% :type])))
        is-new-frame? #(and (not= uuid/zero %)
                            (contains? new-objects %)
                            (not (contains? old-objects %))
                            (= :frame (get-in new-objects [% :type])))

        changed-ids (into #{}
                          (filter changed?)
                          (set/union (keys old-objects) (keys new-objects)))

        to-delete (aggregate-data old-objects changed-ids)
        to-add    (aggregate-data new-objects changed-ids)

        frames-to-delete (->> changed-ids (filter is-deleted-frame?))
        frames-to-add (->> changed-ids (filter is-new-frame?))

        delete-data
        (fn [snap-data [frame-id shapes]]
          (-> snap-data
              (update-in [frame-id :x] remove-coord-data frame-id shapes :x)
              (update-in [frame-id :y] remove-coord-data frame-id shapes :y)))

        add-data
        (fn [snap-data [frame-id shapes]]
          (-> snap-data
              (update-in [frame-id :x] add-coord-data frame-id shapes :x)
              (update-in [frame-id :y] add-coord-data frame-id shapes :y)))

        delete-frames
        (fn [snap-data frame-id]
          (dissoc snap-data frame-id))

        add-frames
        (fn [snap-data frame-id]
          (assoc snap-data frame-id {:x (rt/make-tree)
                                     :y (rt/make-tree)}))]

    (as-> snap-data $
      (reduce add-frames $ frames-to-add)
      (reduce add-data $ to-add)
      (reduce delete-data $ to-delete)
      (reduce delete-frames $ frames-to-delete))))

(defn- log-state
  "Helper function to print a friendly version of the snap tree. Debugging purposes"
  []
  (let [process-frame-data #(d/mapm rt/as-map %)
        process-page-data  #(d/mapm process-frame-data %)]
    (js/console.log "STATE" (clj->js (d/mapm process-page-data @state)))))

(defn- index-page [state page-id objects]
  (let [snap-data (initialize-snap-data objects)]
    (assoc state page-id snap-data)))

(defn- update-page [state page-id old-objects new-objects]
  (let [changed? #(not= (get old-objects %) (get new-objects %))
        changed-ids (into #{}
                          (filter changed?)
                          (set/union (keys old-objects) (keys new-objects)))

        snap-data (get state page-id)
        snap-data (update-snap-data snap-data old-objects new-objects)]
    (assoc state page-id snap-data)))

;; Public API
(defmethod impl/handler :snaps/initialize-index
  [{:keys [file-id data] :as message}]
  ;; Create the index
  (letfn [(process-page [state page]
            (let [id      (:id page)
                  objects (:objects page)]
              (index-page state id objects)))]
    (swap! state #(reduce process-page % (vals (:pages-index data))))
    ;; (log-state)
    ;; Return nil so the worker will not answer anything back
    nil))

(defmethod impl/handler :snaps/update-index
  [{:keys [page-id old-objects new-objects] :as message}]
  ;; TODO: Check the difference and update the index acordingly
  (swap! state update-page page-id old-objects new-objects)
  ;; (log-state)
  nil)

(defmethod impl/handler :snaps/range-query
  [{:keys [page-id frame-id coord ranges] :as message}]
  (letfn [(calculate-range [[from to]]
            (-> @state
                (get-in [page-id frame-id coord])
                (rt/range-query from to)))]
    (->> ranges
         (mapcat calculate-range)
         set ;; unique
         (into []))))



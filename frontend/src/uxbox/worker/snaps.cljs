;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.worker.snaps
  (:require
   [okulary.core :as l]
   [uxbox.common.uuid :as uuid]
   [uxbox.common.pages :as cp]
   [uxbox.common.data :as d]   
   [uxbox.worker.impl :as impl]
   [uxbox.util.range-tree :as rt]
   [uxbox.util.geom.snap-points :as snap]
   [uxbox.util.geom.grid :as gg]))

(defonce state (l/atom {}))

(defn- create-coord-data
  "Initializes the range tree given the shapes"
  [frame-id shapes coord]
  (let [process-shape (fn [coord]
                        (fn [shape]
                          (concat
                           (let [points (snap/shape-snap-points shape)]
                             (map #(vector % (:id shape)) points))

                           ;; The grid points are only added by the "root" of the coord-dat
                           (if (= (:id shape) frame-id)
                            (let [points (gg/grid-snap-points shape coord)]
                              (map #(vector % :layout) points))))))
        into-tree (fn [tree [point _ :as data]]
                    (rt/insert tree (coord point) data))]
    (->> shapes
         (mapcat (process-shape coord))
         (reduce into-tree (rt/make-tree)))))

(defn- initialize-snap-data
  "Initialize the snap information with the current workspace information"
  [objects]
  (let [frame-shapes (->> (vals objects)
                          (filter :frame-id)
                          (group-by :frame-id))
        frame-shapes (->> (cp/select-frames objects)
                          (reduce #(update %1 (:id %2) conj %2) frame-shapes))]
    (d/mapm (fn [frame-id shapes] {:x (create-coord-data frame-id shapes :x)
                                 :y (create-coord-data frame-id shapes :y)})
          frame-shapes)))

(defn- log-state
  "Helper function to print a friendly version of the snap tree. Debugging purposes"
  []
  (let [process-frame-data #(d/mapm rt/as-map %)
        process-page-data  #(d/mapm process-frame-data %)]
    (js/console.log "STATE" (clj->js (d/mapm process-page-data @state)))))

(defn- index-page [state page-id objects]
  (let [snap-data (initialize-snap-data objects)]
    (assoc state page-id snap-data)))

;; Public API
(defmethod impl/handler :snaps/create-index
  [{:keys [file-id pages] :as message}]

  ;; Create the index
  (letfn [(process-page [state page]
            (let [id (:id page)
                  objects (get-in page [:data :objects])]
              (index-page state id objects)))]
    (swap! state #(reduce process-page % pages)))

  #_(log-state)
  ;; Return nil so the worker will not answer anything back
  nil)

(defmethod impl/handler :snaps/update-index
  [{:keys [page-id objects] :as message}]
  ;; TODO: Check the difference and update the index acordingly
  (swap! state index-page page-id objects)
  #_(log-state)
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


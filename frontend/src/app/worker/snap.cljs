;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.snap
  "Data structure that holds and retrieves the data to make the snaps.
   Internally is implemented with a balanced binary tree that queries by range.
   https://en.wikipedia.org/wiki/Range_tree"
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.files.page-diff :as diff]
   [app.common.geom.grid :as gg]
   [app.common.geom.snap :as snap]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.util.range-tree :as rt]))

(def snap-attrs [:frame-id :x :y :width :height :hidden :selrect :grids])

;; PRIVATE FUNCTIONS

(defn- make-insert-tree-data
  "Inserts all data in it's corresponding axis bucket"
  [shape-data axis]
  (fn [tree]
    (let [tree (or tree (rt/make-tree))

          insert-data
          (fn [tree data]
            (rt/insert tree (get-in data [:pt axis]) data))]

      (reduce insert-data tree shape-data))))

(defn- make-delete-tree-data
  "Removes all data in it's corresponding axis bucket"
  [shape-data axis]
  (fn [tree]
    (let [tree (or tree (rt/make-tree))

          remove-data
          (fn [tree data]
            (rt/remove tree (get-in data [:pt axis]) data))]

      (reduce remove-data tree shape-data))))

(defn- add-root-frame
  [page-data]
  (let [frame-id uuid/zero]

    (-> page-data
        (assoc-in [frame-id :x] (rt/make-tree))
        (assoc-in [frame-id :y] (rt/make-tree)))))

(defn get-grids-snap-points
  [frame coord]
  (if (ctst/rotated-frame? frame)
    []
    (let [grid->snap (fn [[grid-type position]]
                       {:type :layout
                        :id (:id frame)
                        :grid grid-type
                        :pt position})]
      (->> (:grids frame)
           (mapcat (fn [grid]
                     (->> (gg/grid-snap-points frame grid coord)
                          (mapv #(vector (:type grid) %)))))
           (mapv grid->snap)))))

(defn- add-frame
  [objects page-data frame]
  (let [frame-id    (:id frame)
        parent-id   (:parent-id frame)

        frame-data  (if (:blocked frame)
                      []
                      (->> (snap/shape->snap-points frame)
                           (mapv #(array-map :type :shape
                                             :id frame-id
                                             :pt %))))
        grid-x-data (get-grids-snap-points frame :x)
        grid-y-data (get-grids-snap-points frame :y)]

    (cond-> page-data
      (and (not (ctl/any-layout-descent? objects frame))
           (not (:hidden frame))
           (not (cfh/hidden-parent? objects frame-id)))

      (-> ;; Update root frame information
       (assoc-in [uuid/zero :objects-data frame-id] frame-data)
       (update-in [parent-id :x] (make-insert-tree-data frame-data :x))
       (update-in [parent-id :y] (make-insert-tree-data frame-data :y))

       ;; Update frame information
       (assoc-in  [frame-id :objects-data frame-id] (d/concat-vec frame-data grid-x-data grid-y-data))
       (update-in [frame-id :x] #(or % (rt/make-tree)))
       (update-in [frame-id :y] #(or % (rt/make-tree)))
       (update-in [frame-id :x] (make-insert-tree-data (d/concat-vec frame-data grid-x-data) :x))
       (update-in [frame-id :y] (make-insert-tree-data (d/concat-vec frame-data grid-y-data) :y))))))

(defn- add-shape
  [objects page-data shape]
  (let [frame-id    (:frame-id shape)
        snap-points (if (:blocked shape)
                      []
                      (snap/shape->snap-points shape))
        shape-data  (->> snap-points
                         (mapv #(array-map
                                 :type :shape
                                 :id (:id shape)
                                 :pt %)))]
    (cond-> page-data
      (and (not (ctl/any-layout-descent? objects shape))
           (not (:hidden shape))
           (not (cfh/hidden-parent? objects (:id shape))))
      (-> (assoc-in [frame-id :objects-data (:id shape)] shape-data)
          (update-in [frame-id :x] (make-insert-tree-data shape-data :x))
          (update-in [frame-id :y] (make-insert-tree-data shape-data :y))))))

(defn- add-guide
  [objects page-data guide]

  (let [frame (get objects (:frame-id guide))
        guide-data (->> (snap/guide->snap-points guide frame)
                        (mapv #(array-map
                                :type :guide
                                :id (:id guide)
                                :axis (:axis guide)
                                :frame-id (:frame-id guide)
                                :pt %)))]
    (if-let [frame-id (:frame-id guide)]
      ;; Guide inside frame, we add the information only on that frame
      (cond-> page-data
        (and (not (:hidden frame))
             (not (cfh/hidden-parent? objects frame-id)))
        (-> (assoc-in [frame-id :objects-data (:id guide)] guide-data)
            (update-in [frame-id (:axis guide)] (make-insert-tree-data guide-data (:axis guide)))))

      ;; Guide outside the frame. We add the information in the global guides data
      (-> page-data
          (assoc-in [:guides :objects-data (:id guide)] guide-data)
          (update-in [:guides (:axis guide)] (make-insert-tree-data guide-data (:axis guide)))))))

(defn- remove-frame
  [page-data frame]
  (let [frame-id (:id frame)
        root-data (get-in page-data [uuid/zero :objects-data frame-id])]
    (-> page-data
        (d/dissoc-in [uuid/zero :objects-data frame-id])
        (update-in [uuid/zero :x] (make-delete-tree-data root-data :x))
        (update-in [uuid/zero :y] (make-delete-tree-data root-data :y))
        (dissoc frame-id))))

(defn- remove-shape
  [page-data shape]

  (let [frame-id (:frame-id shape)
        shape-data (get-in page-data [frame-id :objects-data (:id shape)])]
    (-> page-data
        (d/dissoc-in [frame-id :objects-data (:id shape)])
        (update-in [frame-id :x] (make-delete-tree-data shape-data :x))
        (update-in [frame-id :y] (make-delete-tree-data shape-data :y)))))

(defn- remove-guide
  [page-data guide]
  (if-let [frame-id (:frame-id guide)]
    (let [guide-data (get-in page-data [frame-id :objects-data (:id guide)])]
      (-> page-data
          (d/dissoc-in [frame-id :objects-data (:id guide)])
          (update-in [frame-id (:axis guide)] (make-delete-tree-data guide-data (:axis guide)))))

    ;; Guide outside the frame. We add the information in the global guides data
    (let [guide-data (get-in page-data [:guides :objects-data (:id guide)])]
      (-> page-data
          (d/dissoc-in [:guides :objects-data (:id guide)])
          (update-in [:guides (:axis guide)] (make-delete-tree-data guide-data (:axis guide)))))))

(defn- update-frame
  [objects page-data [_ new-frame]]
  (let [frame-id (:id new-frame)
        root-data (get-in page-data [uuid/zero :objects-data frame-id])
        frame-data (get-in page-data [frame-id :objects-data frame-id])]
    (as-> page-data $
      (update-in $ [uuid/zero :x] (make-delete-tree-data root-data :x))
      (update-in $ [uuid/zero :y] (make-delete-tree-data root-data :y))
      (update-in $ [frame-id :x]  (make-delete-tree-data frame-data :x))
      (update-in $ [frame-id :y]  (make-delete-tree-data frame-data :y))
      (add-frame objects $ new-frame))))

(defn- update-shape
  [objects page-data [old-shape new-shape]]
  (as-> page-data $
    (remove-shape $ old-shape)
    (add-shape objects $ new-shape)))

(defn- update-guide
  [objects page-data [old-guide new-guide]]
  (as-> page-data $
    (remove-guide $ old-guide)
    (add-guide objects $ new-guide)))

;; PUBLIC API

(defn make-snap-data
  "Creates an empty snap index"
  []
  {})

(defn add-page
  "Adds page information"
  [snap-data {:keys [objects guides] :as page}]
  (let [frames     (ctst/get-frames objects)
        shapes     (->> (vals (:objects page))
                        (remove cfh/frame-shape?))
        guides     (vals guides)

        page-data
        (as-> {} $
          (add-root-frame $)
          (reduce (partial add-frame objects) $ frames)
          (reduce (partial add-shape objects) $ shapes)
          (reduce (partial add-guide objects) $ guides))]
    (assoc snap-data (:id page) page-data)))

(defn update-page
  "Updates a previously inserted page with new data"
  [snap-data old-page page]

  (if (contains? snap-data (:id page))
    ;; Update page
    (update snap-data (:id page)
            (fn [page-data]
              (let [{:keys [objects]} page
                    {:keys [change-frame-shapes
                            change-frame-guides
                            removed-frames
                            removed-shapes
                            removed-guides
                            updated-frames
                            updated-shapes
                            updated-guides
                            new-frames
                            new-shapes
                            new-guides]}
                    (diff/calculate-page-diff old-page page snap-attrs)]

                (as-> page-data $
                  (reduce (partial update-shape objects) $ change-frame-shapes)
                  (reduce remove-frame   $ removed-frames)
                  (reduce remove-shape   $ removed-shapes)
                  (reduce (partial update-frame objects) $ updated-frames)
                  (reduce (partial update-shape objects) $ updated-shapes)
                  (reduce (partial add-frame objects)    $ new-frames)
                  (reduce (partial add-shape objects)    $ new-shapes)

                  ;; Guides functions. Need objects to get its frame data
                  (reduce remove-guide                     $ removed-guides)
                  (reduce (partial update-guide objects)   $ change-frame-guides)
                  (reduce (partial update-guide objects)   $ updated-guides)
                  (reduce (partial add-guide objects)      $ new-guides)))))

    ;; Page doesn't exist, we create a new entry
    (add-page snap-data page)))

(defn query
  "Retrieve the shape data for the snaps in that range"
  [snap-data page-id frame-id axis [from to]]

  (d/concat-vec
   (-> snap-data
       (get-in [page-id frame-id axis])
       (rt/range-query from to))

   (-> snap-data
       (get-in [page-id :guides axis])
       (rt/range-query from to))))

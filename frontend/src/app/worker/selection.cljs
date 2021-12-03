;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.selection
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.util.quadtree :as qdt]
   [app.worker.impl :as impl]
   [clojure.set :as set]
   [okulary.core :as l]))

(def ^:const padding-percent 0.10)

(defonce state (l/atom {}))

(defn index-shape
  [objects parents-index clip-parents-index]
  (fn [index shape]
    (let [{:keys [x y width height]} (gsh/points->selrect (:points shape))
          shape-bound #js {:x x :y y :width width :height height}

          parents      (get parents-index (:id shape))
          clip-parents (get clip-parents-index (:id shape))

          frame   (when (and (not= :frame (:type shape))
                             (not= (:frame-id shape) uuid/zero))
                    (get objects (:frame-id shape)))]
      (qdt/insert index
                  (:id shape)
                  shape-bound
                  (assoc shape
                         :frame frame
                         :clip-parents clip-parents
                         :parents parents)))))

(defn objects-bounds
  "Calculates the bounds of the quadtree given a objects map."
  [objects]
  (-> objects
      (dissoc uuid/zero)
      vals
      gsh/selection-rect))

(defn add-padding-bounds
  "Adds a padding to the bounds defined as a percent in the constant `padding-percent`.
  For a value of 0.1 will add a 20% width increase (2 x padding)"
  [bounds]
  (let [width-pad  (* (:width bounds) padding-percent)
        height-pad (* (:height bounds) padding-percent)]
    (-> bounds
        (update :x - width-pad)
        (update :x1 - width-pad)
        (update :x2 + width-pad)
        (update :y1 - height-pad)
        (update :y2 + height-pad)
        (update :width + width-pad width-pad)
        (update :height + height-pad height-pad))))

(defn- create-index
  [objects]
  (let [shapes             (-> objects (dissoc uuid/zero) vals)
        parents-index      (cp/generate-child-all-parents-index objects)
        clip-parents-index (cp/create-clip-index objects parents-index)
        bounds             (-> objects objects-bounds add-padding-bounds)

        index (reduce (index-shape objects parents-index clip-parents-index)
                      (qdt/create (clj->js bounds))
                      shapes)

        z-index (cp/calculate-z-index objects)]

    {:index index :z-index z-index :bounds bounds}))

(defn- update-index
  [{index :index z-index :z-index :as data} old-objects new-objects]
  (let [changes? (fn [id]
                   (not= (get old-objects id)
                         (get new-objects id)))

        changed-ids (into #{}
                          (comp (filter #(not= % uuid/zero))
                                (filter changes?)
                                (mapcat #(into [%] (cp/get-children % new-objects))))
                          (set/union (set (keys old-objects))
                                     (set (keys new-objects))))

        shapes             (->> changed-ids (mapv #(get new-objects %)) (filterv (comp not nil?)))
        parents-index      (cp/generate-child-all-parents-index new-objects shapes)
        clip-parents-index (cp/create-clip-index new-objects parents-index)

        new-index (qdt/remove-all index changed-ids)

        index (reduce (index-shape new-objects parents-index clip-parents-index)
                      new-index
                      shapes)

        z-index (cp/update-z-index z-index changed-ids old-objects new-objects)]

    (assoc data :index index :z-index z-index)))

(defn- query-index
  [{index :index z-index :z-index} rect frame-id full-frame? include-frames? clip-children? reverse?]
  (let [result (-> (qdt/search index (clj->js rect))
                   (es6-iterator-seq))

        ;; Check if the shape matches the filter criteria
        match-criteria?
        (fn [shape]
          (and (not (:hidden shape))
               (not (:blocked shape))
               (or (not frame-id) (= frame-id (:frame-id shape)))
               (case (:type shape)
                 :frame   include-frames?
                 true)

               (or (not full-frame?)
                   (not= :frame (:type shape))
                   (gsh/rect-contains-shape? rect shape))))

        overlaps?
        (fn [shape]
          (gsh/overlaps? shape rect))

        overlaps-parent?
        (fn [clip-parents]
          (->> clip-parents (some (comp not overlaps?)) not))

        add-z-index
        (fn [{:keys [id frame-id] :as shape}]
          (assoc shape :z (+ (get z-index id)
                             (get z-index frame-id 0))))

        ;; Shapes after filters of overlapping and criteria
        matching-shapes
        (into []
              (comp (map #(unchecked-get % "data"))
                    (filter match-criteria?)
                    (filter overlaps?)
                    (filter (comp overlaps? :frame))
                    (filter (if clip-children?
                              (comp overlaps-parent? :clip-parents)
                              (constantly true)))
                    (map add-z-index))
              result)

        keyfn (if reverse? (comp - :z) :z)]

    (into (d/ordered-set)
          (->> matching-shapes
               (sort-by keyfn)
               (map :id)))))


(defmethod impl/handler :selection/initialize-index
  [{:keys [data] :as message}]
  (letfn [(index-page [state page]
            (let [id      (:id page)
                  objects (:objects page)]
              (assoc state id (create-index objects))))

          (update-state [state]
            (reduce index-page state (vals (:pages-index data))))]
    (swap! state update-state)
    nil))

(defmethod impl/handler :selection/update-index
  [{:keys [page-id old-objects new-objects] :as message}]
  (let [update-page-index
        (fn [index]
          (let [old-bounds (:bounds index)
                new-bounds (objects-bounds new-objects)]

            ;; If the new bounds are contained within the old bounds we can
            ;; update the index.
            ;; Otherwise we need to re-create it
            (if (and (some? index)
                     (gsh/contains-selrect? old-bounds new-bounds))
              (update-index index old-objects new-objects)
              (create-index new-objects))))]
    (swap! state update page-id update-page-index))
  nil)

(defmethod impl/handler :selection/query
  [{:keys [page-id rect frame-id reverse? full-frame? include-frames? clip-children?]
    :or {reverse? false full-frame? false include-frames? false clip-children? true} :as message}]
  (when-let [index (get @state page-id)]
    (query-index index rect frame-id full-frame? include-frames? clip-children? reverse?)))

(defmethod impl/handler :selection/query-z-index
  [{:keys [page-id objects ids]}]
  (when-let [{z-index :z-index} (get @state page-id)]
    (->> ids (map #(+ (get z-index %)
                      (get z-index (get-in objects [% :frame-id])))))))

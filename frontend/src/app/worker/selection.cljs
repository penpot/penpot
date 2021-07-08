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

(defonce state (l/atom {}))

(defn index-shape
  [objects parents-index masks-index]
  (fn [index shape]
    (let [{:keys [x y width height]} (gsh/points->selrect (:points shape))
          shape-bound #js {:x x :y y :width width :height height}

          parents (get parents-index (:id shape))
          masks   (get masks-index (:id shape))

          frame   (when (and (not= :frame (:type shape))
                             (not= (:frame-id shape) uuid/zero))
                    (get objects (:frame-id shape)))]
      (qdt/insert index
                  (:id shape)
                  shape-bound
                  (assoc shape :frame frame :masks masks :parents parents)))))

(defn- create-index
  [objects]
  (let [shapes        (-> objects (dissoc uuid/zero) (vals))
        parents-index (cp/generate-child-all-parents-index objects)
        masks-index   (cp/create-mask-index objects parents-index)
        bounds #js {:x (int -0.5e7)
                    :y (int -0.5e7)
                    :width (int 1e7)
                    :height (int 1e7)}

        index (reduce (index-shape objects parents-index masks-index)
                      (qdt/create bounds)
                      shapes)

        z-index (cp/calculate-z-index objects)]

    {:index index :z-index z-index}))

(defn- update-index
  [{index :index z-index :z-index :as data} old-objects new-objects]

  (if (some? data)
    (let [changes? (fn [id]
                     (not= (get old-objects id)
                           (get new-objects id)))

          changed-ids (into #{}
                            (comp (filter changes?)
                                  (filter #(not= % uuid/zero)))
                            (set/union (set (keys old-objects))
                                       (set (keys new-objects))))

          shapes (->> changed-ids (mapv #(get new-objects %)) (filterv (comp not nil?)))
          parents-index (cp/generate-child-all-parents-index new-objects shapes)
          masks-index   (cp/create-mask-index new-objects parents-index)

          new-index (qdt/remove-all index changed-ids)

          index (reduce (index-shape new-objects parents-index masks-index)
                        new-index
                        shapes)

          z-index (cp/update-z-index z-index changed-ids old-objects new-objects)]

      {:index index :z-index z-index})

    ;; If not previous data. We need to create from scratch
    (create-index new-objects)))

(defn- query-index
  [{index :index z-index :z-index} rect frame-id include-frames? full-frame? include-groups? reverse?]
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
                 :group   include-groups?
                 true)

               (or (not full-frame?)
                   (not= :frame (:type shape))
                   (gsh/rect-contains-shape? rect shape))))

        overlaps?
        (fn [shape]
          (gsh/overlaps? shape rect))

        overlaps-masks?
        (fn [masks]
          (->> masks
               (some (comp not overlaps?))
               not))

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
                    (filter (comp overlaps-masks? :masks))
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
  (swap! state update page-id update-index old-objects new-objects)
  nil)

(defmethod impl/handler :selection/query
  [{:keys [page-id rect frame-id include-frames? full-frame? include-groups? reverse?]
    :or {include-groups? true reverse? false include-frames? false full-frame? false} :as message}]
  (when-let [index (get @state page-id)]
    (query-index index rect frame-id include-frames? full-frame? include-groups? reverse?)))

(defmethod impl/handler :selection/query-z-index
  [{:keys [page-id objects ids]}]
  (when-let [{z-index :z-index} (get @state page-id)]
    (->> ids (map #(+ (get z-index %)
                      (get z-index (get-in objects [% :frame-id])))))))

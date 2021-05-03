;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.selection
  (:require
   [cljs.spec.alpha :as s]
   [okulary.core :as l]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.util.quadtree :as qdt]
   [app.worker.impl :as impl]
   [clojure.set :as set]))

(defonce state (l/atom {}))

(defn index-shape
  [objects parents-index masks-index]
  (fn [index shape]
    (let [{:keys [x y width height]} (:selrect shape)
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
        bounds        (gsh/selection-rect shapes)
        bounds #js {:x (:x bounds)
                    :y (:y bounds)
                    :width (:width bounds)
                    :height (:height bounds)}

        index (reduce (index-shape objects parents-index masks-index)
                      (qdt/create bounds)
                      shapes)

        z-index (cp/calculate-z-index objects)]

    {:index index :z-index z-index}))

(defn- update-index
  [{index :index z-index :z-index} old-objects new-objects]

  (let [changes? (fn [id]
                   (not= (get old-objects id)
                         (get new-objects id)))

        changed-ids (into #{}
                          (filter changes?)
                          (set/union (keys old-objects)
                                     (keys new-objects)))

        shapes (->> changed-ids (mapv #(get new-objects %)) (filterv (comp not nil?)))
        parents-index (cp/generate-child-all-parents-index new-objects shapes)
        masks-index   (cp/create-mask-index new-objects parents-index)

        new-index (qdt/remove-all index changed-ids)

        index (reduce (index-shape new-objects parents-index masks-index)
                      new-index
                      shapes)

        z-index (cp/update-z-index z-index changed-ids old-objects new-objects)]

    {:index index :z-index z-index}))

(defn- query-index
  [{index :index z-index :z-index} rect frame-id include-frames? include-groups? disabled-masks reverse?]
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
                 true)))

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
                    (filter (comp overlaps? :frame))
                    (filter (comp overlaps-masks? :masks))
                    (filter overlaps?)
                    (map add-z-index))
              result)

        keyfn (if reverse? (comp - :z) :z)]

    (into (d/ordered-set)
          (->> matching-shapes
               (sort-by keyfn)
               (map :id)))))


(defmethod impl/handler :selection/initialize-index
  [{:keys [file-id data] :as message}]
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
  [{:keys [page-id rect frame-id include-frames? include-groups? disabled-masks reverse?]
    :or {include-groups? true disabled-masks #{} reverse? false} :as message}]
  (when-let [index (get @state page-id)]
    (query-index index rect frame-id include-frames? include-groups? disabled-masks reverse?)))


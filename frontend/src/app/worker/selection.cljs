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

(defn- index-object
  [objects z-index parents-index masks-index index obj]
  (let [{:keys [x y width height]} (:selrect obj)
        shape-bound #js {:x x :y y :width width :height height}

        parents (get parents-index (:id obj))
        masks   (get masks-index (:id obj))
        z       (get z-index (:id obj))

        frame   (when (and (not= :frame (:type obj))
                           (not= (:frame-id obj) uuid/zero))
                  (get objects (:frame-id obj)))]
    (qdt/insert index
                (:id obj)
                shape-bound
                (assoc obj :frame frame :masks masks :parents parents :z z))))

(defn- create-index
  [objects]
  (let [shapes        (-> objects (dissoc uuid/zero) (vals))
        z-index       (cp/calculate-z-index objects)
        parents-index (cp/generate-child-all-parents-index objects)
        masks-index   (cp/create-mask-index objects parents-index)
        bounds        (gsh/selection-rect shapes)
        bounds #js {:x (:x bounds)
                    :y (:y bounds)
                    :width (:width bounds)
                    :height (:height bounds)}]

    (reduce (partial index-object objects z-index parents-index masks-index)
            (qdt/create bounds)
            shapes)))

(defn- update-index
  [index old-objects new-objects]

  (let [changes? (fn [id]
                   (not= (get old-objects id)
                         (get new-objects id)))

        changed-ids (into #{}
                          (filter changes?)
                          (set/union (keys old-objects)
                                     (keys new-objects)))

        shapes (->> changed-ids (mapv #(get new-objects %)) (filterv (comp not nil?)))
        z-index       (cp/calculate-z-index new-objects)
        parents-index (cp/generate-child-all-parents-index new-objects shapes)
        masks-index   (cp/create-mask-index new-objects parents-index)

        new-index (qdt/remove-all index changed-ids)]

    (reduce (partial index-object new-objects z-index parents-index masks-index)
            new-index
            shapes)))

(defn- query-index
  [index rect frame-id include-frames? include-groups? disabled-masks reverse?]
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

        ;; Shapes after filters of overlapping and criteria
        matching-shapes
        (into []
              (comp (map #(unchecked-get % "data"))
                    (filter match-criteria?)
                    (filter (comp overlaps? :frame))
                    (filter (comp overlaps-masks? :masks))
                    (filter overlaps?))
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


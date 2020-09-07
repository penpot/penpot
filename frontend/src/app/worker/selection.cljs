;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.worker.selection
  (:require
   [cljs.spec.alpha :as s]
   [okulary.core :as l]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.shapes :as geom]
   [app.common.pages :as cp]
   [app.common.pages-helpers :as cph]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.util.quadtree :as qdt]
   [app.worker.impl :as impl]))

(defonce state (l/atom {}))

(declare index-object)
(declare create-index)

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
  [{:keys [page-id objects] :as message}]
  (let [index (create-index objects)]
    (swap! state update page-id (constantly index))
    nil))

(defmethod impl/handler :selection/query
  [{:keys [page-id rect frame-id] :as message}]
  (when-let [index (get @state page-id)]
    (let [result (-> (qdt/search index (clj->js rect))
                     (es6-iterator-seq))
          matches? (fn [shape]
                     (and
                      ;; When not frame-id is passed, we filter the frames
                      (or (and (not frame-id) (not= :frame (:type shape)))
                          ;;  If we pass a frame-id only get the area for shapes inside that frame
                          (= frame-id (:frame-id shape)))
                      (geom/overlaps? shape rect)))]

      (into (d/ordered-set)
            (comp (map #(unchecked-get % "data"))
                  (filter matches?)
                  (map :id))
            result))))

(defn- create-index
  [objects]
  (let [shapes (->> (cph/select-toplevel-shapes objects {:include-frames? true})
                    (map #(merge % (select-keys % [:x :y :width :height]))))
        bounds (geom/selection-rect shapes)
        bounds #js {:x (:x bounds)
                    :y (:y bounds)
                    :width (:width bounds)
                    :height (:height bounds)}]
    (reduce index-object
            (qdt/create bounds)
            shapes)))

(defn- index-object
  [index {:keys [id x y width height] :as obj}]
  (let [rect #js {:x x :y y :width width :height height}]
    (qdt/insert index rect obj)))


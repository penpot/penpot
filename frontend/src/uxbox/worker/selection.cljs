;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.worker.selection
  (:require
   [cljs.spec.alpha :as s]
   [okulary.core :as l]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.pages :as cp]
   [uxbox.common.uuid :as uuid]
   [uxbox.worker.impl :as impl]
   [uxbox.common.geom.shapes :as geom]
   [uxbox.util.quadtree :as qdt]))

(defonce state (l/atom {}))

(declare index-object)
(declare create-index)

(defmethod impl/handler :selection/create-index
  [{:keys [file-id pages] :as message}]
  (letfn [(index-page [state page]
            (let [id (:id page)
                  objects (get-in page [:data :objects])]
              (assoc state id (create-index objects))))

          (update-state [state]
            (reduce index-page state pages))]

    (swap! state update-state)
    nil))

(defmethod impl/handler :selection/update-index
  [{:keys [page-id objects] :as message}]
  (let [index (create-index objects)]
    (swap! state update page-id (constantly index))
    nil))

(defmethod impl/handler :selection/query
  [{:keys [page-id rect] :as message}]
  (when-let [index (get @state page-id)]
    (let [result (-> (qdt/search index (clj->js rect))
                     (es6-iterator-seq))
          matches? #(geom/overlaps? % rect)]
      (into #{} (comp (map #(unchecked-get % "data"))
                      (filter matches?)
                      (map :id))
            result))))

(defn- create-index
  [objects]
  (let [shapes (->> (cp/select-toplevel-shapes objects)
                    (map #(merge % (select-keys % [:x :y :width :height]))))
        bounds (geom/shapes->rect-shape shapes)
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


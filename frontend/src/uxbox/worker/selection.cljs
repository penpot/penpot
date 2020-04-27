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
   [promesa.core :as p]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.worker.impl :as impl]
   [uxbox.util.geom.shapes :as geom]
   [uxbox.util.quadtree :as qdt]))

(defonce state (l/atom {}))

(declare resolve-object)
(declare index-object)
(declare retrieve-toplevel-shapes)
(declare calculate-bounds)
(declare create-index)

(defmethod impl/handler :selection/create-index
  [{:keys [file-id pages] :as message}]
  (js/console.log :selection/create-index file-id)
  (letfn [(index-page [state page]
            (let [id (:id page)
                  objects (get-in page [:data :objects])
                  objects (reduce resolve-object {} (vals objects))
                  index (create-index objects)]
              (assoc state id {:index index
                               :objects objects})))
          (update-state [state]
            (reduce index-page state pages))]

    (time
     (swap! state update-state))
    nil))

(defmethod impl/handler :selection/update-index
  [{:keys [page-id objects] :as message}]
  (js/console.log :selection/update-index page-id)
  (letfn [(update-page [_]
            (let [objects (reduce resolve-object {} (vals objects))
                  index   (create-index objects)]
              {:index index :objects objects}))]
    (time
     (swap! state update page-id update-page))
    nil))

(defmethod impl/handler :selection/query
  [{:keys [page-id rect] :as message}]
  (time
   (when-let [{:keys [index objects]} (get @state page-id)]
     (let [lookup #(get objects %)
           result (-> (qdt/search index (clj->js rect))
                      (es6-iterator-seq))
           matches? #(geom/overlaps? % rect)]
       (into #{} (comp (map #(unchecked-get % "data"))
                       (filter matches?)
                       (map :id))
             result)))))

(defn- calculate-bounds
  [objects]
  #js {:x 0
       :y 0
       :width (::width objects)
       :height (::height objects)})

(defn- create-index
  [objects]
  (let [bounds (calculate-bounds objects)]
    (reduce index-object
            (qdt/create bounds)
            (->> (retrieve-toplevel-shapes objects)
                 (map #(get objects %))))))

(defn- index-object
  [index {:keys [id x y width height] :as obj}]
  (let [rect #js {:x x :y y :width width :height height}]
    (qdt/insert index rect obj)))

(defn- resolve-object
  [state {:keys [id] :as item}]
  (let [selection-rect (geom/selection-rect-shape item)
        item   (merge item (select-keys selection-rect [:x :y :width :height]))
        width  (+ (:x item 0) (:width item 0))
        height (+ (:y item 0) (:height item 0))
        max    (fnil max 0)]
    (-> state
        (assoc id item)
        (update ::width max width)
        (update ::height max height))))

(defn- retrieve-toplevel-shapes
  [objects]
  (let [lookup #(get objects %)
        root   (lookup uuid/zero)
        childs (:shapes root)]
    (loop [id  (first childs)
           ids (rest childs)
           res []]
      (if (nil? id)
        res
        (let [obj (lookup id)
              typ (:type obj)]
          (recur (first ids)
                 (rest ids)
                 (if (= :frame typ)
                   (into res (:shapes obj))
                   (conj res id))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KELEIDOS INC

(ns app.common.types.components-list
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.features :as feat]))

(defn components-seq
  [file-data]
  (vals (:components file-data)))

(defn add-component
  [file-data {:keys [id name path main-instance-id main-instance-page shapes]}]
  (let [components-v2  (dm/get-in file-data [:options :components-v2])
        wrap-object-fn feat/*wrap-with-objects-map-fn*]
    (cond-> file-data
      :always
      (assoc-in [:components id]
                {:id id
                 :name name
                 :path path
                 :objects (->> shapes
                               (d/index-by :id)
                               (wrap-object-fn))})

      components-v2
      (update-in [:components id] assoc
                 :main-instance-id main-instance-id
                 :main-instance-page main-instance-page))))

(defn mod-component
  [file-data {:keys [id name path objects]}]
  (let [wrap-objects-fn feat/*wrap-with-objects-map-fn*]
    (update-in file-data [:components id]
               (fn [component]
                 (let [objects (some-> objects wrap-objects-fn)]
                   (cond-> component
                     (some? name)
                     (assoc :name name)

                     (some? path)
                     (assoc :path path)

                     (some? objects)
                     (assoc :objects objects)))))))

(defn get-component
  [file-data component-id]
  (get-in file-data [:components component-id]))

(defn update-component
  [file-data component-id f]
  (update-in file-data [:components component-id] f))

(defn delete-component
  [file-data component-id]
  (update file-data :components dissoc component-id))


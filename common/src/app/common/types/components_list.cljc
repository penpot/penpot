;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KELEIDOS INC

(ns app.common.types.components-list
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as dt]
   [app.common.types.component :as ctk]
   [clojure.set :as set]))

(defn components
  ([file-data] (components file-data nil))
  ([file-data {:keys [include-deleted?] :or {include-deleted? false}}]
   (if include-deleted?
     (:components file-data)
     (d/removem (fn [[_ component]] (:deleted component))
                (:components file-data)))))

(defn components-seq
  [file-data]
  (remove :deleted (vals (:components file-data))))

(defn deleted-components-seq
  [file-data]
  (filter :deleted (vals (:components file-data))))

(defn- touch
  [component]
  (assoc component :modified-at (dt/now)))

(defn add-component
  [fdata {:keys [id name path main-instance-id main-instance-page annotation variant-id variant-properties]}]
  (let [fdata (update fdata :components assoc id (touch {:id id :name name :path path}))]
    (cond-> (update-in fdata [:components id] assoc :main-instance-id main-instance-id :main-instance-page main-instance-page)
      annotation (update-in [:components id] assoc :annotation annotation)
      variant-id (update-in [:components id] assoc :variant-id variant-id)
      variant-properties (update-in [:components id] assoc :variant-properties variant-properties))))

(defn mod-component
  [file-data {:keys [id name path main-instance-id main-instance-page objects annotation variant-id variant-properties modified-at]}]
  (d/update-in-when file-data [:components id]
                    (fn [component]
                      (let [new-comp (cond-> component
                                       (some? name)
                                       (assoc :name name)

                                       (some? path)
                                       (assoc :path path)

                                       (some? main-instance-id)
                                       (assoc :main-instance-id main-instance-id)

                                       (some? main-instance-page)
                                       (assoc :main-instance-page main-instance-page)

                                       (some? objects)
                                       (assoc :objects objects)

                                       (some? modified-at)
                                       (assoc :modified-at modified-at)

                                       (some? annotation)
                                       (assoc :annotation annotation)

                                       (nil? annotation)
                                       (dissoc :annotation)

                                       (some? variant-id)
                                       (assoc :variant-id variant-id)

                                       (nil? variant-id)
                                       (dissoc :variant-id)

                                       (some? variant-properties)
                                       (assoc :variant-properties variant-properties)

                                       (nil? variant-properties)
                                       (dissoc :variant-properties))

                            ;; The set of properties that doesn't mark a component as touched
                            diff     (set/difference
                                      (ctk/diff-components component new-comp)
                                      #{:annotation :modified-at :variant-id :variant-properties})]

                        (if (empty? diff)
                          new-comp
                          (touch new-comp))))))

(defn get-component
  ([file-data component-id]
   (get-component file-data component-id false))

  ([file-data component-id include-deleted?]
   (let [component (get-in file-data [:components component-id])]
     (when (or include-deleted?
               (not (:deleted component)))
       component))))

(defn get-deleted-component
  [file-data component-id]
  (let [component (get-in file-data [:components component-id])]
    (when (:deleted component)
      component)))

(defn update-component
  [file-data component-id f & args]
  (d/update-in-when file-data [:components component-id] #(-> (apply f % args)
                                                              (touch))))
(defn set-component-modified
  [file-data component-id]
  (update-component file-data component-id identity))

(defn delete-component
  [file-data component-id]
  (update file-data :components dissoc component-id))

(defn mark-component-deleted
  [file-data component-id]
  (d/update-in-when file-data [:components component-id] assoc :deleted true))

(defn mark-component-undeleted
  [file-data component-id]
  (d/dissoc-in file-data [:components component-id :deleted]))

(defn used-components-changed-since
  "Check if the shape is an instance of any component in the library, and
   the component has been modified after the date."
  [shape library since-date]
  (if (ctk/uses-library-components? shape (:id library))
    (let [component (get-component (:data library) (:component-id shape))]
      (if (< (:modified-at component) since-date)  ;; Note that :modified-at may be nil
        []
        [{:shape-id (:id shape)
          :asset-id (:component-id shape)
          :asset-type :component}]))
    []))

(defn get-component-annotation
  [shape libraries]
  (let [library        (dm/get-in libraries [(:component-file shape) :data])
        component      (get-component library (:component-id shape) true)]
    (:annotation component)))

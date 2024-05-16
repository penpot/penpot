;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.library
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.record :as cr]
   [app.main.store :as st]
   [app.plugins.utils :as utils :refer [get-data]]
   [app.util.object :as obj]))

(defn get-library-info
  ([self attr]
   (let [lib-id (get-data self :id)
         current-file-id (:current-file-id @st/state)]
     (if (= lib-id current-file-id)
       (dm/get-in @st/state [:workspace-file attr])
       (dm/get-in @st/state [:workspace-libraries lib-id attr]))))

  ([self attr mapfn]
   (-> (get-library-info self attr)
       (mapfn))))

(defn get-library-data
  ([self attr]
   (let [lib-id (get-data self :id)
         current-file-id (:current-file-id @st/state)]
     (if (= lib-id current-file-id)
       (dm/get-in @st/state [:workspace-data attr])
       (dm/get-in @st/state [:workspace-libraries lib-id :data attr]))))

  ([self attr mapfn]
   (-> (get-library-data self attr)
       (mapfn))))

(defn- array-to-js
  [value]
  (.freeze
   js/Object
   (apply array (->> value (map utils/to-js)))))

(deftype Library [_data]
  Object)

(defn create-library
  [data]
  (cr/add-properties!
   (Library. data)
   {:name "_data"
    :enumerable false}

   {:name "id"
    :get (fn [self]
           (str (:id (obj/get self "_data"))))}

   {:name "name"
    :get (fn [self]
           (get-library-info self :name))}

   {:name "colors"
    :get (fn [self]
           (array-to-js (get-library-data self :colors vals)))}

   {:name "typographies"
    :get (fn [self]
           (array-to-js (get-library-data self :typographies vals)))}

   {:name "components"
    :get (fn [self]
           (array-to-js (get-library-data self :components vals)))}))

(deftype PenpotLibrarySubcontext []
  Object
  (find
    [_ _name])

  (find [_]))

(defn create-library-subcontext
  []
  (cr/add-properties!
   (PenpotLibrarySubcontext.)
   {:name "local" :get
    (fn [_]
      (let [file (get @st/state :workspace-file)
            data (get @st/state :workspace-data)]
        (create-library (assoc file :data data))))}

   {:name "connected" :get
    (fn [_]
      (let [libraries (get @st/state :workspace-libraries)]
        (apply array (->> libraries vals (map create-library)))))}))

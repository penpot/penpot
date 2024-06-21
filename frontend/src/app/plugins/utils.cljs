;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.utils
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn locate-file
  [id]
  (assert (uuid? id) "File not valid uuid")
  (if (= id (:current-file-id @st/state))
    (-> (:workspace-file @st/state)
        (assoc :data (:workspace-data @st/state)))
    (dm/get-in @st/state [:workspace-libraries id])))

(defn locate-page
  [file-id id]
  (assert (uuid? id) "Page not valid uuid")
  (dm/get-in (locate-file file-id) [:data :pages-index id]))

(defn locate-objects
  [file-id page-id]
  (:objects (locate-page file-id page-id)))

(defn locate-shape
  [file-id page-id id]
  (assert (uuid? id) "Shape not valid uuid")
  (dm/get-in (locate-page file-id page-id) [:objects id]))

(defn locate-library-color
  [file-id id]
  (assert (uuid? id) "Color not valid uuid")
  (dm/get-in (locate-file file-id) [:data :colors id]))

(defn locate-library-typography
  [file-id id]
  (assert (uuid? id) "Typography not valid uuid")
  (dm/get-in (locate-file file-id) [:data :typographies id]))

(defn locate-library-component
  [file-id id]
  (assert (uuid? id) "Component not valid uuid")
  (dm/get-in (locate-file file-id) [:data :components id]))

(defn locate-presence
  [session-id]
  (dm/get-in @st/state [:workspace-presence session-id]))

(defn locate-profile
  [session-id]
  (let [{:keys [profile-id]} (locate-presence session-id)]
    (dm/get-in @st/state [:users profile-id])))

(defn locate-component
  [objects shape]
  (let [current-file-id (:current-file-id @st/state)
        workspace-data (:workspace-data @st/state)
        workspace-libraries (:workspace-libraries @st/state)
        root (ctn/get-instance-root objects shape)]
    [root (ctf/resolve-component root {:id current-file-id :data workspace-data} workspace-libraries {:include-deleted? true})]))

(defn proxy->file
  [proxy]
  (let [id (obj/get proxy "$id")]
    (when (some? id)
      (locate-file id))))

(defn proxy->page
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-page file-id id))))

(defn proxy->shape
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? page-id) (some? id))
      (locate-shape file-id page-id id))))

(defn proxy->library-color
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-color file-id id))))

(defn proxy->library-typography
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-typography file-id id))))

(defn proxy->library-component
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-component file-id id))))

(defn get-data
  ([self attr]
   (-> (obj/get self "_data")
       (get attr)))

  ([self attr transform-fn]
   (-> (get-data self attr)
       (transform-fn))))

(defn get-data-fn
  ([attr]
   (fn [self]
     (get-data self attr)))

  ([attr transform-fn]
   (fn [self]
     (get-data self attr transform-fn))))

(defn get-state
  ([self attr]
   (let [id (get-data self :id)
         page-id (d/nilv (get-data self :page-id) (:current-page-id @st/state))]
     (dm/get-in @st/state [:workspace-data :pages-index page-id :objects id attr])))
  ([self attr mapfn]
   (-> (get-state self attr)
       (mapfn))))

(defn from-js
  "Converts the object back to js"
  ([obj]
   (from-js obj #{:type}))
  ([obj keyword-keys]
   (when (some? obj)
     (let [process-node
           (fn process-node [node]
             (reduce-kv
              (fn [m k v]
                (let [k (keyword (str/kebab k))
                      v (cond (map? v)
                              (process-node v)

                              (vector? v)
                              (mapv process-node v)

                              (and (string? v) (re-matches us/uuid-rx v))
                              (uuid/uuid v)

                              (contains? keyword-keys k)
                              (keyword v)

                              :else v)]
                  (assoc m k v)))
              {}
              node))]
       (process-node (js->clj obj))))))

(defn to-js
  "Converts to javascript an camelize the keys"
  [obj]
  (when (some? obj)
    (let [result
          (reduce-kv
           (fn [m k v]
             (let [v (cond (object? v) (to-js v)
                           (uuid? v) (dm/str v)
                           :else v)]
               (assoc m (str/camel (name k)) v)))
           {}
           obj)]
      (clj->js result))))

(defn array-to-js
  [value]
  (if (coll? value)
    (.freeze
     js/Object
     (apply array (->> value (map to-js))))
    value))

(defn result-p
  "Creates a pair of atom+promise. The promise will be resolved when the atom gets a value.
  We use this to return the promise to the library clients and resolve its value when a value is passed
  to the atom"
  []
  (let [ret-v (atom nil)
        ret-p
        (p/create
         (fn [resolve _]
           (add-watch
            ret-v
            ::watcher
            (fn [_ _ _ value]
              (remove-watch ret-v ::watcher)
              (resolve value)))))]
    [ret-v ret-p]))

(defn display-not-valid
  [code value]
  (.error js/console (dm/str "[PENPOT PLUGIN] Value not valid: " value ". Code: " code)))

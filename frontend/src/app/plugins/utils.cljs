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
   [app.common.uuid :as uuid]
   [app.main.store :as st]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [promesa.core :as p]))

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
   (from-js obj identity))
  ([obj vfn]
   (let [ret (js->clj obj {:keyword-fn (fn [k] (str/camel (name k)))})]
     (reduce-kv
      (fn [m k v]
        (let [k (keyword (str/kebab k))
              v (cond (map? v)
                      (from-js v)

                      (and (string? v) (re-matches us/uuid-rx v))
                      (uuid/uuid v)

                      :else (vfn k v))]
          (assoc m k v)))
      {}
      ret))))

(defn to-js
  "Converts to javascript an camelize the keys"
  [obj]
  (let [result
        (reduce-kv
         (fn [m k v]
           (let [v (cond (object? v) (to-js v)
                         (uuid? v) (dm/str v)
                         :else v)]
             (assoc m (str/camel (name k)) v)))
         {}
         obj)]
    (clj->js result)))

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

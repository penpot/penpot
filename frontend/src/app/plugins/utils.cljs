;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.utils
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(def uuid-regex
  #"\w{8}-\w{4}-\w{4}-\w{4}-\w{12}")

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

(defn from-js
  "Converts the object back to js"
  [obj]
  (let [ret (js->clj obj {:keyword-fn (fn [k] (str/camel (name k)))})]
    (reduce-kv
     (fn [m k v]
       (let [v (cond (map? v)
                     (from-js v)

                     (and (string? v) (re-matches uuid-regex v))
                     (uuid/uuid v)

                     :else v)]
         (assoc m (keyword (str/kebab k)) v)))
     {}
     ret)))


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




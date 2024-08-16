;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.json
  (:refer-clojure :exclude [read])
  (:require
   #?(:clj [clojure.data.json :as j])
   [cuerdas.core :as str]))


#?(:clj
   (defn read
     [reader & {:as opts}]
     (j/read reader opts)))

#?(:clj
   (defn write
     [writer data & {:as opts}]
     (j/write data writer opts)))

#?(:cljs
   (defn map->obj
     "A simplified version of clj->js with focus on performance"
     [x & {:keys [key-fn]}]
     (cond
       (nil? x)
       nil

       (keyword? x)
       (name x)

       (map? x)
       (reduce-kv (fn [m k v]
                    (let [k (if (keyword? k) (name k) k)]
                      (unchecked-set m (key-fn k) (map->obj v key-fn))
                      m))
                  #js {}
                  x)

       (coll? x)
       (reduce (fn [arr v]
                 (.push arr v)
                 arr)
               (array)
               x)

       :else x)))

(defn read-kebab-key
  [k]
  (if (and (string? k) (not (str/includes? k "/")))
    (-> k str/kebab keyword)
    k))

(defn write-camel-key
  [k]
  (if (or (keyword? k) (symbol? k))
    (str/camel k)
    (str k)))

#?(:clj
   (defn encode
     [data & {:as opts}]
     (j/write-str data opts)))

#?(:clj
   (defn decode
     [data & {:as opts}]
     (j/read-str data opts)))

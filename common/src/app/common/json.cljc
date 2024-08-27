;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.json
  (:refer-clojure :exclude [read clj->js js->clj])
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

#?(:cljs
   (defn ->js
     [x & {:keys [key-fn]
           :or {key-fn write-camel-key} :as opts}]
     (let [f (fn this-fn [x]
               (cond
                 (nil? x)
                 nil

                 (satisfies? cljs.core/IEncodeJS x)
                 (cljs.core/-clj->js x)

                 (or (keyword? x)
                     (symbol? x))
                 (name x)

                 (number? x)
                 x

                 (boolean? x)
                 x

                 (map? x)
                 (reduce-kv (fn [m k v]
                              (let [k (key-fn k)]
                                (unchecked-set m k (this-fn v))
                                m))
                            #js {}
                            x)

                 (coll? x)
                 (reduce (fn [arr v]
                           (.push arr (this-fn v))
                           arr)
                         (array)
                         x)

                 :else
                 (str x)))]
       (f x))))

#?(:cljs
   (defn ->clj
     [o & {:keys [key-fn val-fn] :or {key-fn read-kebab-key val-fn identity}}]
     (let [f (fn this-fn [x]
               (let [x (val-fn x)]
                 (cond
                   (array? x)
                   (persistent!
                    (.reduce ^js/Array x
                             #(conj! %1 (this-fn %2))
                             (transient [])))

                   (identical? (type x) js/Object)
                   (persistent!
                    (.reduce ^js/Array (js-keys x)
                             #(assoc! %1 (key-fn %2) (this-fn (unchecked-get x %2)))
                             (transient {})))

                   :else
                   x)))]
       (f o))))

(defn encode
  [data & {:as opts}]
  #?(:clj (j/write-str data opts)
     :cljs (.stringify js/JSON (->js data opts))))

(defn decode
  [data & {:as opts}]
  #?(:clj (j/read-str data opts)
     :cljs (->clj (.parse js/JSON data) opts)))

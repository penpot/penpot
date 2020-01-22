;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.common.data
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [concat read-string])
  (:require [clojure.set :as set]
            #?(:cljs [cljs.reader :as r]
               :clj [clojure.edn :as r])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures Manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dissoc-in
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn concat
  [& colls]
  (loop [result (first colls)
         colls (rest colls)]
    (if (seq colls)
      (recur (reduce conj result (first colls))
             (rest colls))
      result)))

(defn enumerate
  ([items] (enumerate items 0))
  ([items start]
   (loop [idx start
          items items
          res []]
     (if (empty? items)
       res
       (recur (inc idx)
              (rest items)
              (conj res [idx (first items)]))))))

(defn seek
  ([pred coll]
   (seek pred coll nil))
  ([pred coll not-found]
   (reduce (fn [_ x]
             (if (pred x)
               (reduced x)
               not-found))
           not-found coll)))

(defn diff-maps
  [ma mb]
  (let [ma-keys (set (keys ma))
        mb-keys (set (keys mb))
        added (set/difference mb-keys ma-keys)
        removed (set/difference ma-keys mb-keys)
        both (set/intersection ma-keys mb-keys)]
    (concat
     (mapv #(vector :set % (get mb %)) added)
     (mapv #(vector :set % nil) removed)
     (loop [k (first both)
            r (rest both)
            rs []]
       (if k
         (let [vma (get ma k)
               vmb (get mb k)]
           (if (= vma vmb)
             (recur (first r) (rest r) rs)
             (recur (first r) (rest r) (conj rs [:set k vmb]))))
         rs)))))

(defn index-by
  "Return a indexed map of the collection keyed by the result of
  executing the getter over each element of the collection."
  [getter coll]
  (persistent!
   (reduce #(assoc! %1 (getter %2) %2) (transient {}) coll)))

(defn index-of
  [coll v]
  (loop [c (first coll)
         coll (rest coll)
         index 0]
    (if (nil? c)
      nil
      (if (= c v)
        index
        (recur (first coll)
               (rest coll)
               (inc index))))))

(defn remove-nil-vals
  "Given a map, return a map removing key-value
  pairs when value is `nil`."
  [data]
  (into {} (remove (comp nil? second) data)))

(defn without-keys
  "Return a map without the keys provided
  in the `keys` parameter."
  [data keys]
  (persistent!
   (reduce #(dissoc! %1 %2) (transient data) keys)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Parsing / Conversion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- nan?
  [v]
  (not= v v))

(defn- impl-parse-integer
  [v]
  #?(:cljs (js/parseInt v 10)
     :clj (try
            (Integer/parseInt v)
            (catch Throwable e
              nil))))

(defn- impl-parse-double
  [v]
  #?(:cljs (js/parseFloat v)
     :clj (try
            (Double/parseDouble v)
            (catch Throwable e
              nil))))

(defn parse-integer
  ([v]
   (parse-integer v nil))
  ([v default]
   (let [v (impl-parse-integer v)]
     (if (or (nil? v) (nan? v))
       default
       v))))

(defn parse-double
  ([v]
   (parse-double v nil))
  ([v default]
   (let [v (impl-parse-double v)]
     (if (or (nil? v) (nan? v))
       default
       v))))

(defn read-string
  [v]
  (r/read-string v))

(defn coalesce-str
  [val default]
  (if (or (nil? val) (nan? val))
    default
    (str val)))

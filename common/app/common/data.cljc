;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.common.data
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [concat read-string])
  (:require [clojure.set :as set]
            [linked.set :as lks]
            #?(:cljs [cljs.reader :as r]
               :clj [clojure.edn :as r])
            #?(:cljs [cljs.core :as core]
               :clj [clojure.core :as core]))
  #?(:clj
     (:import linked.set.LinkedSet)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ordered-set
  ([] lks/empty-linked-set)
  ([a] (conj lks/empty-linked-set a))
  ([a & xs] (apply conj lks/empty-linked-set a xs)))

(defn ordered-set?
  [o]
  #?(:cljs (instance? lks/LinkedSet o)
     :clj (instance? LinkedSet o)))

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
  (loop [result (transient (first colls))
         colls  (next colls)]
    (if colls
      (recur (reduce conj! result (first colls))
             (next colls))
      (persistent! result))))

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

(defn index-by
  "Return a indexed map of the collection keyed by the result of
  executing the getter over each element of the collection."
  [getter coll]
  (persistent!
   (reduce #(assoc! %1 (getter %2) %2) (transient {}) coll)))

(defn index-of-pred
  [coll pred]
  (loop [c (first coll)
         coll (rest coll)
         index 0]
    (if (nil? c)
      nil
      (if (pred c)
        index
        (recur (first coll)
               (rest coll)
               (inc index))))))

(defn index-of
  [coll v]
  (index-of-pred coll #(= % v)))

(defn replace-by-id
  ([value]
   (map (fn [item]
          (if (= (:id item) (:id value))
            value
            item))))
  ([coll value]
   (sequence (replace-by-id value) coll)))

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

(defn remove-at-index
  [v index]
  (vec (core/concat
        (subvec v 0 index)
        (subvec v (inc index)))))

(defn zip [col1 col2]
  (map vector col1 col2))

(defn mapm
  "Map over the values of a map"
  [mfn coll]
  (into {} (map (fn [[key val]] [key (mfn key val)]) coll)))

(defn map-perm
  "Maps a function to each pair of values that can be combined inside the
  function without repetition.
  Example:
  (map-perm vector [1 2 3 4]) => [[1 2] [1 3] [1 4] [2 3] [2 4] [3 4]]"
  [mfn coll]
  (if (empty? coll)
    []
    (core/concat
     (map (partial mfn (first coll)) (rest coll))
     (map-perm mfn (rest coll)))))

(defn join
  "Returns a new collection with the cartesian product of both collections.
  For example:
    (join [1 2 3] [:a :b]) => ([1 :a] [1 :b] [2 :a] [2 :b] [3 :a] [3 :b])
  You can pass a function to merge the items. By default is `vector`:
    (join [1 2 3] [1 10 100] *) => (1 10 100 2 20 200 3 30 300)"
  ([col1 col2] (join col1 col2 vector '()))
  ([col1 col2 join-fn] (join col1 col2 join-fn '()))
  ([col1 col2 join-fn acc]
   (cond
     (empty? col1) acc
     (empty? col2) acc
     :else (recur (rest col1) col2 join-fn
                  (core/concat acc (map (partial join-fn (first col1)) col2))))))


(def sentinel
  #?(:clj (Object.)
     :cljs (js/Object.)))

(defn update-in-when
  [m key-seq f & args]
  (let [found (get-in m key-seq sentinel)]
    (if-not (identical? sentinel found)
      (assoc-in m key-seq (apply f found args))
      m)))

(defn update-when
  [m key f & args]
  (let [found (get m key sentinel)]
    (if-not (identical? sentinel found)
      (assoc m key (apply f found args))
      m)))

(defn assoc-in-when
  [m key-seq v]
  (let [found (get-in m key-seq sentinel)]
    (if-not (identical? sentinel found)
      (assoc-in m key-seq v)
      m)))

(defn assoc-when
  [m key v]
  (let [found (get m key sentinel)]
    (if-not (identical? sentinel found)
      (assoc m key v)
      m)))

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

(defn coalesce
  [val default]
  (or val default))

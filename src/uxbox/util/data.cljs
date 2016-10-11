;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.data
  "A collection of data transformation utils."
  (:require [cljs.reader :as r]
            [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structure manipulation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn index-by
  "Return a indexed map of the collection
  keyed by the result of executing the getter
  over each element of the collection."
  [coll getter]
  (persistent!
   (reduce #(assoc! %1 (getter %2) %2) (transient {}) coll)))

(def index-by-id #(index-by % :id))

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

(defn index-of
  "Return the first index when appears the `v` value
  in the `coll` collection."
  [coll v]
  (first (keep-indexed (fn [idx x]
                         (when (= v x) idx))
                       coll)))

(defn replace-by-id
  [coll value]
  {:pre [(vector? coll)]}
  (mapv (fn [item]
          (if (= (:id item) (:id value))
            value
            item)) coll))


(defn deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn conj-or-disj
  "Given a set, and an element remove that element from set
  if it exists or add it if it does not exists."
  [s v]
  (if (contains? s v)
    (disj s v)
    (conj s v)))

;; --- String utils

(def +uuid-re+
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(defn uuid-str?
  [v]
  (and (string? v)
       (re-seq +uuid-re+ v)))

;; --- Interop

(defn jscoll->vec
  "Convert array like js object into vector."
  [v]
  (-> (clj->js [])
      (.-slice)
      (.call v)
      (js->clj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Numbers Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nan?
  [v]
  (js/isNaN v))

(defn read-string
  [v]
  (r/read-string v))

(defn parse-int
  ([v]
   (parse-int v nil))
  ([v default]
   (let [v (js/parseInt v 10)]
     (if (or (not v) (nan? v))
       default
       v))))

(defn parse-float
  ([v]
   (parse-float v nil))
  ([v default]
   (let [v (js/parseFloat v)]
     (if (or (not v) (nan? v))
       default
       v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Other
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn classnames
  [& params]
  {:pre [(even? (count params))]}
  (str/join " " (reduce (fn [acc [k v]]
                          (if (true? v)
                            (conj acc (name k))
                            acc))
                        []
                        (partition 2 params))))

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

(def ^:static index-by-id #(index-by % :id))

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
   (js/parseInt v 10))
  ([v default]
   (let [v (js/parseInt v 10)]
     (if (or (not v) (nan? v))
       default
       v))))

(defn parse-float
  ([v]
   (js/parseFloat v))
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

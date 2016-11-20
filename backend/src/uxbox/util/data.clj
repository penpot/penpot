;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.data
  "Data transformations utils."
  (:require [clojure.walk :as walk]
            [cuerdas.core :as str]))

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

(defn normalize-attrs
  "Recursively transforms all map keys from strings to keywords."
  [m]
  (letfn [(tf [[k v]]
            (let [ks (-> (name k)
                         (str/replace "_" "-"))]
              [(keyword ks) v]))
          (walker [x]
            (if (map? x)
              (into {} (map tf) x)
              x))]
    (walk/postwalk walker m)))

(defn strip-delete-attrs
  [m]
  (dissoc m :deleted-at))

(defn normalize
  "Perform a common normalization transformation
  for a entity (database retrieved) data structure."
  [m]
  (-> m normalize-attrs strip-delete-attrs))

(defn deep-merge
  [& maps]
  (letfn [(merge' [& maps]
            (if (every? map? maps)
              (apply merge-with merge' maps)
              (last maps)))]
    (apply merge' (remove nil? maps))))

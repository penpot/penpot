;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.common.data
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [concat])
  (:require [clojure.set :as set]))

(defn concat
  [& colls]
  (loop [result (first colls)
         colls (rest colls)]
    (if (seq colls)
      (recur (reduce conj result (first colls))
             (rest colls))
      result)))

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
     (mapv #(vector :add % (get mb %)) added)
     (mapv #(vector :del % nil) removed)
     (loop [k (first both)
            r (rest both)
            rs []]
       (if k
         (let [vma (get ma k)
               vmb (get mb k)]
           (if (= vma vmb)
             (recur (first r) (rest r) rs)
             (recur (first r) (rest r) (conj rs [:mod k vmb]))))
         rs)))))

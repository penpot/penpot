;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [get get-in assoc!])
  (:require [goog.object :as gobj]))

(defn get
  ([obj k]
   (when (object? obj)
     (unchecked-get obj k)))
  ([obj k default]
   (if (object? obj)
     (or (unchecked-get obj k) default)
     default)))

(defn get-in
  [obj keys]
  (loop [key (first keys)
         keys (rest keys)
         res obj]
    (if (nil? key)
      res
      (if (nil? res)
        res
        (recur (first keys)
               (rest keys)
               (unchecked-get res key))))))

(defn assign!
  ([a b]
   (js/Object.assign a b))
  ([a b & more]
   (reduce assign! (assign! a b) more)))

(defn assoc!
  [obj attr value]
  (when (object? obj)
    (unchecked-set obj attr value)
    obj))

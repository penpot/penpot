;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.weak
  "A collection of helpers for work with weak references and weak
  data structures on JS runtime."
  (:refer-clojure :exclude [memoize])
  (:require
   ["./weak/impl_weak_map.js" :as wm]
   ["./weak/impl_weak_value_map.js" :as wvm]))

(defn create-weak-value-map
  []
  (new wvm/WeakValueMap.))

(defn create-weak-map
  []
  (new wm/WeakEqMap #js {:hash hash :equals =}))

(def ^:private state (new js/WeakMap))
(def ^:private global-counter 0)

(defn weak-key
  "A simple helper that returns a stable key string for an object
  while that object remains in memory and is not collected by the GC.

  Mainly used for assign temporal IDs/keys for react children
  elements when the element has no specific id."
  [o]
  (let [key (.get ^js/WeakMap state o)]
    (if (some? key)
      key
      (let [key (str "weak-key" (js* "~{}++" global-counter))]
        (.set ^js/WeakMap state o key)
        key))))

(defn memoize
  "Returns a memoized version of a referentially transparent function. The
  memoized version of the function keeps a cache of the mapping from arguments
  to results and, when calls with the same arguments are repeated often, has
  higher performance at the expense of higher memory use.

  The main difference with clojure.core/memoize, is that this function
  uses weak-map, so cache is cleared once GC is passed and cached keys
  are collected"
  [f]
  (let [mem (create-weak-map)]
    (fn [& args]
      (let [v (.get mem args)]
        (if (undefined? v)
          (let [ret (apply f args)]
            (.set ^js mem args ret)
            ret)
          v)))))



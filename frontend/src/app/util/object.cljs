;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! get get-in assoc!])
  (:require
   [cuerdas.core :as str]
   [goog.object :as gobj]
   ["lodash/omit" :as omit]))

(defn get
  ([obj k]
   (when-not (nil? obj)
     (unchecked-get obj k)))
  ([obj k default]
   (let [result (get obj k)]
     (if (undefined? result) default result))))

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

(defn without
  [obj keys]
  (let [keys (cond
               (vector? keys) (into-array keys)
               (array? keys) keys
               :else (throw (js/Error. "unexpected input")))]
    (omit obj keys)))

(defn merge!
  ([a b]
   (js/Object.assign a b))
  ([a b & more]
   (reduce merge! (merge! a b) more)))

(defn set!
  [obj key value]
  (unchecked-set obj key value)
  obj)

(defn- props-key-fn
  [key]
  (if (or (= key :class) (= key :class-name))
    "className"
    (str/camel (name key))))

(defn clj->props
  [props]
  (clj->js props :keyword-fn props-key-fn))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! get get-in merge clone contains?])
  (:require
   [cuerdas.core :as str]))

(defn new [] #js {})

(defn get
  ([obj k]
   (when-not (nil? obj)
     (unchecked-get obj k)))
  ([obj k default]
   (let [result (get obj k)]
     (if (undefined? result) default result))))

(defn contains?
  [obj k]
  (some? (unchecked-get obj k)))

(defn get-keys
  [obj]
  (js/Object.keys ^js obj))

(defn get-in
  ([obj keys]
   (get-in obj keys nil))

  ([obj keys default]
   (loop [key (first keys)
          keys (rest keys)
          res obj]
     (if (or (nil? key) (nil? res))
       (or res default)
       (recur (first keys)
              (rest keys)
              (unchecked-get res key))))))

(defn clone
  [a]
  (js/Object.assign #js {} a))

(defn merge!
  ([a b]
   (js/Object.assign a b))
  ([a b & more]
   (reduce merge! (merge! a b) more)))

(defn merge
  ([a b]
   (js/Object.assign #js {} a b))
  ([a b & more]
   (reduce merge! (merge a b) more)))

(defn set!
  [obj key value]
  (unchecked-set obj key value)
  obj)

(defn update!
  [obj key f & args]
  (let [found (get obj key ::not-found)]
    (if-not (identical? ::not-found found)
      (do (unchecked-set obj key (apply f found args))
          obj)
      obj)))

(defn- props-key-fn
  [key]
  (if (or (= key :class) (= key :class-name))
    "className"
    (str/camel (name key))))

(defn clj->props
  [props]
  (clj->js props :keyword-fn props-key-fn))

(defn ^boolean in?
  [obj prop]
  (js* "~{} in ~{}" prop obj))

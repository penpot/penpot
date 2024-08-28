;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! new get merge clone contains? array? into-array]))

(defn array?
  [o]
  (.isArray js/Array o))

(defn into-array
  [o]
  (js/Array.from o))

(defn create [] #js {})

(defn get
  ([obj k]
   (when (some? obj)
     (unchecked-get obj k)))
  ([obj k default]
   (let [result (get obj k)]
     (if (undefined? result) default result))))

(defn contains?
  [obj k]
  (when (some? obj)
    (js/Object.hasOwn obj k)))

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

(defn unset!
  [obj key]
  (js-delete obj key)
  obj)

(def ^:private not-found-sym (js/Symbol "not-found"))

(defn update!
  [obj key f & args]
  (let [found (get obj key not-found-sym)]
    (when-not ^boolean (identical? found not-found-sym)
      (unchecked-set obj key (apply f found args)))
    obj))

(defn ^boolean in?
  [obj prop]
  (js* "~{} in ~{}" prop obj))

(defn without-empty
  [^js obj]
  (when (some? obj)
    (js* "Object.entries(~{}).reduce((a, [k,v]) => (v == null ? a : (a[k]=v, a)), {}) " obj)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.array
  "A collection of helpers for work with javascript arrays."
  (:refer-clojure :exclude [conj! conj filter map reduce find])
  (:require
   [cljs.core :as c]))

(defn conj
  "A conj like function for js arrays."
  [a v]
  (js* "[...~{}, ~{}]" a v))

(defn conj!
  "A conj! like function for js arrays."
  ([a v]
   (.push ^js a v)
   a)
  ([a v1 v2]
   (.push ^js a v1 v2)
   a)
  ([a v1 v2 v3]
   (.push ^js a v1 v2 v3)
   a)
  ([a v1 v2 v3 v4]
   (.push ^js a v1 v2 v3 v4)
   a)
  ([a v1 v2 v3 v4 v5]
   (.push ^js a v1 v2 v3 v4 v5)
   a)
  ([a v1 v2 v3 v4 v5 v6]
   (.push ^js a v1 v2 v3 v4 v5 v6)
   a))

(defn normalize-to-array
  "If `o` is an array, returns it as-is, if not, wrap into an array."
  [o]
  (if (array? o)
    o
    #js [o]))

(defn without-nils
  [^js/Array o]
  (.filter o (fn [v] (some? v))))

(defn filter
  "A specific filter for js arrays."
  [pred ^js/Array o]
  (.filter o pred))

(defn map
  [f a]
  (.map ^js/Array a f))

(defn reduce
  [f init val]
  (.reduce ^js/Array val f init))

(defn find-index
  [f v]
  (.findIndex ^js/Array v f))

(defn find
  [f v]
  (.find ^js/Array v f))

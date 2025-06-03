;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.generators
  (:refer-clojure :exclude [set subseq uuid filter map let boolean vector keyword int double])
  #?(:cljs (:require-macros [app.common.schema.generators]))
  (:require
   [app.common.schema.registry :as sr]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [clojure.core :as c]
   [clojure.test.check.generators :as tg]
   [cuerdas.core :as str]
   [malli.generator :as mg]))

(defmacro let
  [& params]
  `(tg/let ~@params))

(defn sample
  ([g]
   (mg/sample g {:registry sr/default-registry}))
  ([g opts]
   (mg/sample g (assoc opts :registry sr/default-registry))))

(defn generate
  ([g]
   (mg/generate g {:registry sr/default-registry}))
  ([g opts]
   (mg/generate g (assoc opts :registry sr/default-registry))))

(defn generator
  ([s]
   (mg/generator s {:registry sr/default-registry}))
  ([s opts]
   (mg/generator s (assoc opts :registry sr/default-registry))))

(defn small-double
  [& {:keys [min max] :or {min -100 max 100}}]
  (tg/double* {:min min, :max max, :infinite? false, :NaN? false}))

(defn small-int
  [& {:keys [min max] :or {min -100 max 100}}]
  (tg/large-integer* {:min min, :max max}))

(defn word-string
  []
  (as-> tg/string-ascii $$
    (tg/resize 10 $$)
    (tg/fmap (fn [v] (apply str (re-seq #"[A-Za-z]+" v))) $$)
    (tg/such-that (fn [v] (>= (count v) 4)) $$ 100)
    (tg/fmap str/lower $$)))

(defn word-keyword
  []
  (->> (word-string)
       (tg/fmap c/keyword)))

(defn email
  []
  (->> (word-string)
       (tg/such-that (fn [v] (>= (count v) 4)))
       (tg/fmap str/lower)
       (tg/fmap (fn [v]
                  (str v "@example.net")))))

(defn uri
  []
  (tg/let [scheme (tg/elements ["http" "https"])
           domain (as-> (word-string) $
                    (tg/such-that (fn [x] (> (count x) 5)) $ 100)
                    (tg/fmap str/lower $))
           ext    (tg/elements ["net" "com" "org" "app" "io"])]
    (u/uri (str scheme "://" domain "." ext))))

(defn uuid
  []
  (tg/fmap (fn [_] (uuid/next)) (small-int)))

(defn subseq
  "Given a collection, generates \"subsequences\" which are sequences
  of (not necessarily contiguous) elements from the original
  collection, in the same order. For collections of distinct elements
  this is effectively a subset generator, with an ordering guarantee."
  ([elements]
   (subseq [] elements))
  ([dest elements]
   (->> (apply tg/tuple (repeat (count elements) tg/boolean))
        (tg/fmap (fn [bools]
                   (into dest
                         (comp
                          (c/filter first)
                          (c/map second))
                         (c/map list bools elements)))))))

(defn map-of
  ([kg vg]
   (tg/map kg vg {:min-elements 1 :max-elements 3}))
  ([kg vg opts]
   (tg/map kg vg opts)))

(defn elements
  [s]
  (tg/elements s))

(defn one-of
  [& gens]
  (tg/one-of (into [] gens)))

(defn fmap
  [f g]
  (tg/fmap f g))

(defn filter
  [pred gen]
  (tg/such-that pred gen 100))

(defn mcat
  [f g]
  (tg/bind g f))

(defn tuple
  [& opts]
  (apply tg/tuple opts))

(defn vector
  [& opts]
  (apply tg/vector opts))

(defn set
  [g]
  (tg/set g))

;; Static Generators

(def boolean tg/boolean)
(def text (word-string))
(def double (small-double))
(def int (small-int))
(def keyword (word-keyword))

(def any
  (tg/one-of [text boolean double int keyword]))

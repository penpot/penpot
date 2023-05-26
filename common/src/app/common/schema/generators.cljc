;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.generators
  (:refer-clojure :exclude [set subseq uuid for])
  #?(:cljs (:require-macros [app.common.schema.generators]))
  (:require
   [app.common.schema.registry :as sr]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as tg]
   [clojure.test.check.properties :as tp]
   [cuerdas.core :as str]
   [malli.generator :as mg]))

(defn default-reporter-fn
  [{:keys [type result] :as args}]
  (case type
    :complete
    (prn (select-keys args [:result :num-tests :seed "time-elapsed-ms"]))

    :failure
    (do
      (prn (select-keys args [:num-tests :seed :failed-after-ms]))
      (when #?(:clj (instance? Throwable result)
               :cljs (instance? js/Error result))
        (throw result)))

    nil))

(defmacro for
  [& params]
  `(tp/for-all ~@params))

(defn check!
  [p & {:keys [num] :or {num 20} :as options}]
  (tc/quick-check num p (assoc options :reporter-fn default-reporter-fn)))

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
  (->> (tg/such-that #(re-matches #"\w+" %)
                       tg/string-alphanumeric
                       50)
       (tg/such-that (complement str/blank?))))

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
  (->> tg/small-integer
       (tg/fmap (fn [_] (uuid/next)))))

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
                            (filter first)
                            (map second))
                           (map list bools elements)))))))

(defn set
  [g]
  (tg/set g))

(defn elements
  [s]
  (tg/elements s))

(defn one-of
  [& gens]
  (tg/one-of (into [] gens)))

(defn fmap
  [f g]
  (tg/fmap f g))

(defn tuple
  [& opts]
  (apply tg/tuple opts))

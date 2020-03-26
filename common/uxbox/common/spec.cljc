;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.common.spec
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [assert])
  #?(:cljs (:require-macros [uxbox.common.spec :refer [assert]]))
  (:require
   #?(:clj [datoteka.core :as fs])
   #?(:clj [clojure.spec.alpha :as s]
      :cljs [cljs.spec.alpha :as s])
   [expound.alpha :as expound]
   [uxbox.common.exceptions :as ex]
   [cuerdas.core :as str]))

(s/check-asserts true)

;; --- Constants

(def email-rx
  #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

;; --- Conformers

(defn- uuid-conformer
  [v]
  (if (uuid? v)
    v
    (if (string? v)
      (if (re-matches uuid-rx v)
        #?(:cljs (uuid v)
           :clj (java.util.UUID/fromString v))

        (if (str/empty? v) nil ::s/invalid))
      ::s/invalid)))

(defn boolean-conformer
  [v]
  (if (boolean? v)
    v
    (if (string? v)
      (if (re-matches #"^(?:t|true|false|f|0|1)$" v)
        (contains? #{"t" "true" "1"} v)
        ::s/invalid)
      ::s/invalid)))

(defn boolean-unformer
  [v]
  (if v "true" "false"))

(defn- number-conformer
  [v]
  (cond
    (number? v) v
    (str/numeric? v)
    #?(:clj (Double/parseDouble v)
       :cljs (js/parseFloat v))
    :else ::s/invalid))

(defn- integer-conformer
  [v]
  (cond
    (integer? v) v
    (string? v)
    (if (re-matches #"^[-+]?\d+$" v)
      #?(:clj (Long/parseLong v)
         :cljs (js/parseInt v 10))
      ::s/invalid)
    :else ::s/invalid))

(defn- color-conformer
  [v]
  (if (and (string? v) (re-matches #"^#[0-9A-Fa-f]{6}$" v))
    v
    ::s/invalid))

(defn- email-conformer
  [v]
  (if (and (string? v) (re-matches email-rx v))
    v
    ::s/invalid))

#?(:clj
   (defn path-conformer
     [v]
     (cond
       (string? v) (fs/path v)
       (fs/path? v) v
       :else ::s/invalid)))


;; --- Default Specs

(s/def ::inst inst?)
(s/def ::string string?)
(s/def ::email (s/conformer email-conformer str))
(s/def ::color (s/conformer color-conformer str))
(s/def ::uuid (s/conformer uuid-conformer str))
(s/def ::boolean (s/conformer boolean-conformer boolean-unformer))
(s/def ::number (s/conformer number-conformer str))
(s/def ::integer (s/conformer integer-conformer str))
(s/def ::not-empty-string (s/and string? #(not (str/empty? %))))
#?(:clj (s/def ::path (s/conformer path-conformer str)))

;; --- Macros

(defn spec-assert
  [spec x]
  (s/assert* spec x))

(defmacro assert
  "Development only assertion macro."
  [spec x]
  (when *assert*
    `(spec-assert ~spec ~x)))

(defmacro verify
  "Always active assertion macro (does not obey to :elide-asserts)"
  [spec x]
  `(spec-assert ~spec ~x))

;; --- Public Api

(defn conform
  [spec data]
  (let [result (s/conform spec data)]
    (when (= result ::s/invalid)
      (let [edata (s/explain-data spec data)]
        (throw (ex/error :type :validation
                         :code :spec-validation
                         :explain (with-out-str
                                    (expound/printer edata))
                         :data (::s/problems edata)))))
    result))

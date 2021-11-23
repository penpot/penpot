;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec
  "Data manipulation and query helper functions."
  (:refer-clojure :exclude [assert bytes?])
  #?(:cljs (:require-macros [app.common.spec :refer [assert]]))
  (:require
   #?(:clj  [clojure.spec.alpha :as s]
      :cljs [cljs.spec.alpha :as s])

   ;; NOTE: don't remove this, causes exception on advanced build
   ;; because of some strange interaction with cljs.spec.alpha and
   ;; modules splitting.
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [expound.alpha]))

(s/check-asserts true)

;; --- Constants

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def max-safe-int (int 1e6))
(def min-safe-int (int -1e6))

;; --- Conformers

(defn uuid-conformer
  [v]
  (if (uuid? v)
    v
    (if (string? v)
      (if (re-matches uuid-rx v)
        (uuid/uuid v)
        ::s/invalid)
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
  (if (and (string? v) (re-matches #"^#(?:[0-9a-fA-F]{3}){1,2}$" v))
    v
    ::s/invalid))

(defn keyword-conformer
  [v]
  (cond
    (keyword? v)
    v

    (string? v)
    (keyword v)

    :else
    ::s/invalid))

;; --- Default Specs

(s/def ::keyword (s/conformer keyword-conformer name))
(s/def ::inst inst?)
(s/def ::string string?)
(s/def ::color (s/conformer color-conformer str))
(s/def ::uuid (s/conformer uuid-conformer str))
(s/def ::boolean (s/conformer boolean-conformer boolean-unformer))
(s/def ::number (s/conformer number-conformer str))
(s/def ::integer (s/conformer integer-conformer str))
(s/def ::not-empty-string (s/and string? #(not (str/empty? %))))
(s/def ::url string?)
(s/def ::fn fn?)
(s/def ::point gpt/point?)
(s/def ::id ::uuid)

(defn bytes?
  "Test if a first parameter is a byte
  array or not."
  [x]
  (if (nil? x)
    false
    #?(:clj (= (Class/forName "[B")
               (.getClass ^Object x))
       :cljs (or (instance? js/Uint8Array x)
                 (instance? js/ArrayBuffer x)))))

(s/def ::bytes bytes?)

(s/def ::safe-integer
  #(and
    (int? %)
    (>= % min-safe-int)
    (<= % max-safe-int)))

(s/def ::safe-number
  #(and
    (or (int? %)
        (float? %))
    (>= % min-safe-int)
    (<= % max-safe-int)))


;; --- SPEC: set of Keywords

(s/def ::set-of-keywords
  (s/conformer
   (fn [s]
     (let [xform (comp
                  (map (fn [s]
                         (cond
                           (string? s) (keyword s)
                           (keyword? s) s
                           :else nil)))
                  (filter identity))]
       (cond
         (set? s)    (into #{} xform s)
         (string? s) (into #{} xform (str/words s))
         :else       ::s/invalid)))
   (fn [s]
     (str/join " " (map name s)))))

;; --- SPEC: email

(def email-re #"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(s/def ::email
  (s/conformer
   (fn [v]
     (if (string? v)
       (if-let [matches (re-seq email-re v)]
         (first matches)
         (do ::s/invalid))
       ::s/invalid))
   str))

(s/def ::set-of-emails
  (s/conformer
   (fn [v]
     (cond
       (string? v)
       (into #{} (re-seq email-re v))

       (or (set? v) (sequential? v))
       (->> (str/join " " v)
            (re-seq email-re)
            (into #{}))

       :else ::s/invalid))

   (fn [v]
     (str/join " " v))))

;; --- SPEC: set-of-str

(s/def ::set-of-str
  (s/conformer
   (fn [s]
     (let [xform (comp
                  (filter string?)
                  (remove str/empty?)
                  (remove str/blank?))]
       (cond
         (string? s) (->> (str/split s #"\s*,\s*")
                          (into #{} xform))
         (set? s)    (into #{} xform s)
         :else       ::s/invalid)))
   (fn [s]
     (str/join "," s))))

;; --- Macros

(defn spec-assert*
  [spec x message context]
  (if (s/valid? spec x)
    x
    (let [data    (s/explain-data spec x)
          explain (with-out-str (s/explain-out data))]
      (ex/raise :type :assertion
                :code :spec-validation
                :hint message
                :data data
                :explain explain
                :context context
                #?@(:cljs [:stack (.-stack (ex-info message {}))])))))


(defmacro assert
  "Development only assertion macro."
  [spec x]
  (when *assert*
    (let [nsdata  (:ns &env)
          context (when nsdata
                    {:ns (str (:name nsdata))
                     :name (pr-str spec)
                     :line (:line &env)
                     :file (:file (:meta nsdata))})
          message (str "spec assert: '" (pr-str spec) "'")]
      `(spec-assert* ~spec ~x ~message ~context))))

(defmacro verify
  "Always active assertion macro (does not obey to :elide-asserts)"
  [spec x]
  (let [nsdata  (:ns &env)
        context (when nsdata
                  {:ns (str (:name nsdata))
                   :name (pr-str spec)
                   :line (:line &env)
                   :file (:file (:meta nsdata))})
        message (str "spec verify: '" (pr-str spec) "'")]
    `(spec-assert* ~spec ~x ~message ~context)))

;; --- Public Api

(defn conform
  [spec data]
  (let [result (s/conform spec data)]
    (when (= result ::s/invalid)
      (let [data    (s/explain-data spec data)
            explain (with-out-str
                      (s/explain-out data))]
        (throw (ex/error :type :validation
                         :code :spec-validation
                         :explain explain
                         :data data))))
    result))

(defmacro instrument!
  [& {:keys [sym spec]}]
  (when *assert*
    (let [message (str "Spec failed on: " sym)]
      `(let [origf# ~sym
             mdata# (meta (var ~sym))]
         (set! ~sym (fn [& params#]
                      (spec-assert* ~spec params# ~message mdata#)
                      (apply origf# params#)))))))


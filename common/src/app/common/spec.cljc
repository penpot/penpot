;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec
  "Data validation & assertion helpers."
  (:refer-clojure :exclude [assert bytes?])
  #?(:cljs (:require-macros [app.common.spec :refer [assert]]))
  (:require
   #?(:clj  [clojure.spec.alpha :as s]
      :cljs [cljs.spec.alpha :as s])

   ;; NOTE: don't remove this, causes exception on advanced build
   ;; because of some strange interaction with cljs.spec.alpha and
   ;; modules splitting.
   [app.common.exceptions :as ex]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [expound.alpha :as expound]))

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
(s/def ::set-of-string (s/every string? :kind set?))
(s/def ::url string?)
(s/def ::fn fn?)
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

(letfn [(conform-fn [dest s]
          (let [xform (keep (fn [s]
                              (cond
                                (string? s) (keyword s)
                                (keyword? s) s
                                :else nil)))]
            (cond
              (set? s)    (into dest xform s)
              (string? s) (into dest xform (str/words s))
              :else       ::s/invalid)))]

  (s/def ::set-of-keywords
    (s/conformer
     (fn [s] (conform-fn #{} s))
     (fn [s] (str/join " " (map name s)))))

  (s/def ::vec-of-keywords
    (s/conformer
     (fn [s] (conform-fn [] s))
     (fn [s] (str/join " " (map name s))))))

;; --- SPEC: email

(def email-re #"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(defn parse-email
  [s]
  (some->> s (re-seq email-re) first))

(s/def ::email
  (s/conformer
   (fn [v]
     (or (parse-email v) ::s/invalid))
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

(s/def ::uri
  (s/conformer
   (fn [s]
     (cond
       (u/uri? s) s
       (string? s) (u/uri s)
       :else ::s/invalid))
   str))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MACROS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn explain-data
  [spec value]
  (s/explain-data spec value))

(defn valid?
  [spec value]
  (s/valid? spec value))

(defmacro assert-expr*
  "Auxiliar macro for expression assertion."
  [expr hint]
  `(when-not ~expr
     (ex/raise :type :assertion
               :code :expr-validation
               :hint ~hint)))

(defmacro assert-spec*
  "Auxiliar macro for spec assertion."
  [spec value hint]
  (let [context (if-let [nsdata (:ns &env)]
                  {:ns (str (:name nsdata))
                   :name (pr-str spec)
                   :line (:line &env)
                   :file (:file (:meta nsdata))}
                  {:ns   (str (ns-name *ns*))
                   :name (pr-str spec)
                   :line (:line (meta &form))})
        hint    (or hint (str "spec assert: " (pr-str spec)))]

    `(if (valid? ~spec ~value)
       ~value
       (let [data# (explain-data ~spec ~value)]
         (ex/raise :type :assertion
                   :code :spec-validation
                   :hint ~hint
                   ::ex/data (merge ~context data#))))))

(defmacro assert
  "Is a spec specific assertion macro that only evaluates if *assert*
  is true. DEPRECATED: it should be replaced by the new, general
  purpose assert! macro."
  [spec value]
  (when *assert*
    `(assert-spec* ~spec ~value nil)))

(defmacro verify
  "Is a spec specific assertion macro that evaluates always,
  independently of *assert* value. DEPRECATED: should be replaced by
  the new, general purpose `verify!` macro."
  [spec value]
  `(assert-spec* ~spec ~value nil))

(defmacro assert!
  "General purpose assertion macro."
  [& params]
  ;; If we only receive two arguments, this means we use the simplified form
  (let [pcnt (count params)]
    (cond
      ;; When we have a single argument, this means a simplified form
      ;; of expr assertion
      (= 1 pcnt)
      (let [expr (first params)
            hint (str "expr assert failed:" (pr-str expr))]
        (when *assert*
          `(assert-expr* ~expr ~hint)))

      ;; If we have two arguments, this can be spec or expr
      ;; assertion. The spec assertion is determined if the first
      ;; argument is a qualified keyword.
      (= 2 pcnt)
      (let [[spec-or-expr value-or-msg] params]
        (if (qualified-keyword? spec-or-expr)
          `(assert-spec* ~spec-or-expr ~value-or-msg nil)
          `(assert-expr* ~spec-or-expr ~value-or-msg)))

      (= 3 pcnt)
      (let [[spec value hint] params]
        `(assert-spec* ~spec ~value ~hint))

      :else
      (let [{:keys [spec expr hint always? val]} params]
        (when (or always? *assert*)
          (if spec
            `(assert-spec* ~spec ~val ~hint)
            `(assert-expr* ~expr ~hint)))))))

(defmacro verify!
  "A variant of `assert!` macro that evaluates always, independently
  of the *assert* value."
  [& params]
  (binding [*assert* true]
    `(assert! ~@params)))

;; --- Public Api

(defn conform
  [spec data]
  (let [result (s/conform spec data)]
    (when (= result ::s/invalid)
      (let [data (s/explain-data spec data)]
        (throw (ex/error :type :validation
                         :code :spec-validation
                         ::ex/data data))))
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

(defn pretty-explain
  ([data] (pretty-explain data nil))
  ([data {:keys [max-problems] :or {max-problems 10}}]
   (when (and (::s/problems data)
              (::s/value data)
              (::s/spec data))
     (binding [s/*explain-out* expound/printer]
       (with-out-str
         (s/explain-out (update data ::s/problems #(take max-problems %))))))))

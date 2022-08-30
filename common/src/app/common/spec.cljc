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
   [app.common.data.macros :as dm]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULT SPECS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- SPEC: uuid

(letfn [(conformer [v]
          (if (uuid? v)
            v
            (if (string? v)
              (if (re-matches uuid-rx v)
                (uuid/uuid v)
                ::s/invalid)
              ::s/invalid)))
        (unformer [v]
          (dm/str v))]
  (s/def ::uuid (s/conformer conformer unformer)))

;; --- SPEC: boolean

(letfn [(conformer [v]
          (if (boolean? v)
            v
            (if (string? v)
              (if (re-matches #"^(?:t|true|false|f|0|1)$" v)
                (contains? #{"t" "true" "1"} v)
                ::s/invalid)
              ::s/invalid)))
        (unformer [v]
          (if v "true" "false"))]
  (s/def ::boolean (s/conformer conformer unformer)))

;; --- SPEC: number

(letfn [(conformer [v]
          (cond
            (number? v)      v
            (str/numeric? v) #?(:cljs (js/parseFloat v)
                                :clj  (Double/parseDouble v))
            :else            ::s/invalid))]
  (s/def ::number (s/conformer conformer str)))

;; --- SPEC: integer

(letfn [(conformer [v]
          (cond
            (integer? v) v
            (string? v)
            (if (re-matches #"^[-+]?\d+$" v)
              #?(:clj (Long/parseLong v)
                 :cljs (js/parseInt v 10))
              ::s/invalid)
            :else ::s/invalid))]
  (s/def ::integer (s/conformer conformer str)))

;; --- SPEC: keyword

(letfn [(conformer [v]
          (cond
            (keyword? v) v
            (string? v)  (keyword v)
            :else        ::s/invalid))

        (unformer [v]
          (name v))]
  (s/def ::keyword (s/conformer conformer unformer)))

;; --- SPEC: email

(def email-re #"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(defn parse-email
  [s]
  (some->> s (re-seq email-re) first))

(letfn [(conformer [v]
          (or (parse-email v) ::s/invalid))
        (unformer [v]
          (dm/str v))]
  (s/def ::email (s/conformer conformer unformer)))

;; -- SPEC: uri

(letfn [(conformer [s]
          (cond
            (u/uri? s) s
            (string? s) (u/uri s)
            :else ::s/invalid))
        (unformer [v]
          (dm/str v))]
  (s/def ::uri (s/conformer conformer unformer)))

;; --- SPEC: color string

(letfn [(conformer [v]
          (if (and (string? v) (re-matches #"^#(?:[0-9a-fA-F]{3}){1,2}$" v))
            v
            ::s/invalid))
        (unformer [v]
          (dm/str v))]
  (s/def ::rgb-color-str (s/conformer conformer unformer)))

;; --- SPEC: set/vector of Keywords

(letfn [(conformer-fn [dest s]
          (let [xform (keep (fn [s]
                              (cond
                                (string? s) (keyword s)
                                (keyword? s) s
                                :else nil)))]
            (cond
              (set? s)    (into dest xform s)
              (string? s) (into dest xform (str/words s))
              :else       ::s/invalid)))
        (unformer-fn [v]
          (str/join " " (map name v)))]

  (s/def ::set-of-keywords
    (s/conformer (partial conformer-fn #{}) unformer-fn))

  (s/def ::vector-of-keywords
    (s/conformer (partial conformer-fn []) unformer-fn)))

;; --- SPEC: set/vector of strings

(def non-empty-strings-xf
  (comp
   (filter string?)
   (remove str/empty?)
   (remove str/blank?)))

(letfn [(conformer-fn [dest v]
          (cond
            (string? v) (into dest non-empty-strings-xf (str/split v #"[\s,]+"))
            (vector? v) (into dest non-empty-strings-xf v)
            (set? v)    (into dest non-empty-strings-xf v)
            :else       ::s/invalid))
        (unformer-fn [v]
          (str/join "," v))]

  (s/def ::set-of-strings
    (s/conformer (partial conformer-fn #{}) unformer-fn))

  (s/def ::vector-of-strings
    (s/conformer (partial conformer-fn []) unformer-fn)))

;; --- SPEC: set-of-valid-emails

(letfn [(conformer [v]
          (cond
            (string? v)
            (into #{} (re-seq email-re v))

            (or (set? v) (sequential? v))
            (->> (str/join " " v)
                 (re-seq email-re)
                 (into #{}))

            :else ::s/invalid))
        (unformer [v]
          (str/join " " v))]
  (s/def ::set-of-valid-emails (s/conformer conformer unformer)))

;; --- SPEC: query-string

(letfn [(conformer [s]
          (if (string? s)
            (ex/try* #(u/query-string->map s) (constantly ::s/invalid))
            s))
        (unformer [s]
          (u/map->query-string s))]
  (s/def ::query-string (s/conformer conformer unformer)))

;; --- SPECS WITHOUT CONFORMER

(s/def ::inst inst?)
(s/def ::string string?)
(s/def ::not-empty-string (s/and string? #(not (str/empty? %))))
(s/def ::url string?)
(s/def ::fn fn?)
(s/def ::id ::uuid)

(s/def ::set-of-string (s/every ::string :kind set?))
(s/def ::coll-of-uuid (s/every ::uuid))
(s/def ::set-of-uuid (s/every ::uuid :kind set?))

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

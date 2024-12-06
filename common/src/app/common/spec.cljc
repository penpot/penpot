;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
   [clojure.test.check.generators :as tgen]
   [cuerdas.core :as str]
   [expound.alpha :as expound]))

(s/check-asserts true)

;; --- Constants

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

;; Integer/MAX_VALUE
(def max-safe-int 2147483647)
;; Integer/MIN_VALUE
(def min-safe-int -2147483648)

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
  (s/def ::uuid
    (s/with-gen (s/conformer conformer unformer)
      #(tgen/fmap (fn [_] (uuid/random)) tgen/any))))

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
  (s/def ::boolean
    (s/with-gen (s/conformer conformer unformer)
      (constantly tgen/boolean))))

;; --- SPEC: number

(letfn [(conformer [v]
          (cond
            (number? v)      v
            (str/numeric? v) #?(:cljs (js/parseFloat v)
                                :clj  (Double/parseDouble v))
            :else            ::s/invalid))]
  (s/def ::number
    (s/with-gen (s/conformer conformer str)
      #(s/gen ::safe-number))))

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
  (s/def ::integer
    (s/with-gen (s/conformer conformer str)
      #(s/gen ::safe-integer))))

;; --- SPEC: keyword

(letfn [(conformer [v]
          (cond
            (keyword? v) v
            (string? v)  (keyword v)
            :else        ::s/invalid))

        (unformer [v]
          (name v))]
  (s/def ::keyword
    (s/with-gen (s/conformer conformer unformer)
      #(->> (s/gen ::not-empty-string)
            (tgen/fmap keyword)))))

;; --- SPEC: email

(def email-re #"[a-zA-Z0-9_.+-\\\\]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+")

(defn parse-email
  [s]
  (some->> s (re-seq email-re) first))

(letfn [(conformer [v]
          (or (parse-email v) ::s/invalid))
        (unformer [v]
          (dm/str v))]
  (s/def ::email
    (s/with-gen (s/conformer conformer unformer)
      #(as-> (tgen/let [p1 (s/gen ::not-empty-string)
                        p2 (s/gen ::not-empty-string)
                        p3 (tgen/elements ["com" "net"])]
               (str p1 "@" p2 "." p3)) $
         (tgen/such-that (partial re-matches email-re) $ 50)))))

;; -- SPEC: uri

(letfn [(conformer [s]
          (cond
            (u/uri? s) s
            (string? s) (u/uri (str/trim s))
            :else ::s/invalid))
        (unformer [v]
          (dm/str v))]
  (s/def ::uri
    (s/with-gen (s/conformer conformer unformer)
      #(tgen/let [scheme (tgen/elements ["http" "https"])
                  domain (as-> (s/gen ::not-empty-string) $
                           (tgen/such-that (fn [x] (> (count x) 5)) $ 100)
                           (tgen/fmap str/lower $))
                  ext    (tgen/elements ["net" "com" "org" "app" "io"])]
         (u/uri (str scheme "://" domain "." ext))))))

;; --- SPEC: color string

(def rgb-color-str-re
  #"^#(?:[0-9a-fA-F]{3}){1,2}$")

(letfn [(conformer [v]
          (if (and (string? v) (re-matches rgb-color-str-re v))
            v
            ::s/invalid))
        (unformer [v]
          (dm/str v))]
  (s/def ::rgb-color-str
    (s/with-gen (s/conformer conformer unformer)
      #(->> tgen/any
            (tgen/fmap (fn [_]
                         #?(:clj (format "%x" (rand-int 16rFFFFFF))
                            :cljs (.toString (rand-int 16rFFFFFF) 16))))
            (tgen/fmap (fn [x]
                         (str "#" x)))))))

;; --- SPEC: set/vector of Keywords

(letfn [(conformer-fn [dest s]
          (let [xform (keep (fn [s]
                              (cond
                                (string? s) (keyword s)
                                (keyword? s) s
                                :else nil)))]
            (cond
              (coll? s)   (into dest xform s)
              (string? s) (into dest xform (str/words s))
              :else       ::s/invalid)))
        (unformer-fn [v]
          (str/join " " (map name v)))]

  (s/def ::set-of-keywords
    (s/with-gen (s/conformer (partial conformer-fn #{}) unformer-fn)
      #(tgen/set (s/gen ::keyword))))

  (s/def ::vector-of-keywords
    (s/with-gen (s/conformer (partial conformer-fn []) unformer-fn)
      #(tgen/vector (s/gen ::keyword)))))

;; --- SPEC: set/vector of strings

(def non-empty-strings-xf
  (comp
   (filter string?)
   (remove str/empty?)
   (remove str/blank?)))

(letfn [(conformer-fn [dest v]
          (cond
            (coll? v)   (into dest non-empty-strings-xf v)
            (string? v) (into dest non-empty-strings-xf (str/split v #"[\s,]+"))
            :else       ::s/invalid))
        (unformer-fn [v]
          (str/join "," v))]
  (s/def ::set-of-strings
    (-> (s/conformer (partial conformer-fn #{}) unformer-fn)
        (s/with-gen #(tgen/set (s/gen ::not-empty-string)))))

  (s/def ::vector-of-strings
    (-> (s/conformer (partial conformer-fn []) unformer-fn)
        (s/with-gen #(tgen/vector (s/gen ::not-empty-string))))))

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
  (s/def ::set-of-valid-emails
    (s/with-gen (s/conformer conformer unformer)
      #(tgen/set (s/gen ::email)))))

;; --- SPECS WITHOUT CONFORMER

(s/def ::inst inst?)  ;; A clojure instant (date and time)

(s/def ::string
  (s/with-gen string?
    (fn []
      (tgen/such-that (fn [o]
                        (re-matches #"\w+" o))
                      tgen/string-alphanumeric
                      50))))

(s/def ::not-empty-string
  (s/with-gen (s/and string? #(not (str/empty? %)))
    #(tgen/such-that (complement str/empty?) (s/gen ::string))))

#?(:clj
   (s/def ::agent #(instance? clojure.lang.Agent %)))

#?(:clj
   (s/def ::atom #(instance? clojure.lang.Atom %)))

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

(s/def ::bytes
  #?(:clj (s/with-gen bytes? (constantly tgen/bytes))
     :cljs bytes?))

(defn safe-number?
  [x]
  (and (number? x)
       (>= x min-safe-int)
       (<= x max-safe-int)))

(defn safe-int? [x]
  (and (safe-number? x) (int? x)))

(defn safe-float? [x]
  (and (safe-number? x) (float? x)))

(s/def ::safe-integer
  (s/with-gen safe-int? (constantly tgen/small-integer)))

(s/def ::safe-float
  (s/with-gen safe-float? #(tgen/double* {:inifinite? false
                                          :NaN? false
                                          :min min-safe-int
                                          :max max-safe-int})))

(s/def ::safe-number
  (s/with-gen safe-number? #(tgen/one-of [(s/gen ::safe-integer)
                                          (s/gen ::safe-float)])))

(s/def ::url ::string)
(s/def ::fn fn?)
(s/def ::id ::uuid)
(s/def ::some some?)
(s/def ::coll-of-uuid (s/every ::uuid))
(s/def ::set-of-uuid (s/every ::uuid :kind set?))

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
  "Auxiliary macro for expression assertion."
  [expr hint]
  `(when-not ~expr
     (ex/raise :type :assertion
               :code :expr-validation
               :hint ~hint)))

(defmacro assert-spec*
  "Auxiliary macro for spec assertion."
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
        (if (or (qualified-keyword? spec-or-expr)
                (symbol? spec-or-expr))
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


;; FIXME: REMOVE
(defn pretty-explain
  ([data] (pretty-explain data nil))
  ([data {:keys [max-problems] :or {max-problems 10}}]
   (when (and (::s/problems data)
              (::s/value data)
              (::s/spec data))
     (binding [s/*explain-out* expound/printer]
       (with-out-str
         (s/explain-out (update data ::s/problems #(take max-problems %))))))))

(defn validation-error?
  [cause]
  (if (and (map? cause) (= :spec-validation (:type cause)))
    cause
    (when (ex/error? cause)
      (validation-error? (ex-data cause)))))

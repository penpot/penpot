;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.data.macros
  "Data retrieval & manipulation specific macros."
  (:refer-clojure :exclude [get-in select-keys str with-open max])
  #?(:cljs (:require-macros [app.common.data.macros]))
  (:require
   #?(:clj [cljs.analyzer.api :as aapi])
   #?(:clj [clojure.core :as c]
      :cljs [cljs.core :as c])
   [app.common.data :as d]
   [cuerdas.core :as str]))

(defmacro select-keys
  "A macro version of `select-keys`. Useful when keys vector is known
  at compile time (aprox 600% performance boost).

  It is not 100% equivalent, this macro does not removes not existing
  keys in contrast to clojure.core/select-keys"
  [target keys]
  (assert (vector? keys) "keys expected to be a vector")
  `{~@(mapcat (fn [key] [key (list `c/get target key)]) keys) ~@[]})

(defmacro get-in
  "A macro version of `get-in`. Useful when the keys vector is known at
  compile time (20-40% performance improvement)."
  ([target keys]
   (assert (vector? keys) "keys expected to be a vector")
   `(-> ~target ~@(map (fn [key] (list `c/get key)) keys)))
  ([target keys default]
   (assert (vector? keys) "keys expected to be a vector")
   (let [last-index (dec (count keys))]
     `(-> ~target ~@(map-indexed (fn [index key]
                                   (if (= last-index index)
                                     (list `c/get key default)
                                     (list `c/get key)))
                                 keys)))))

(defmacro str
  [& params]
  `(str/concat ~@params))

#?(:clj
   (defmacro export
     "A helper macro that allows reexport a var in a current namespace."
     [v]
     (if (boolean (:ns &env))

       ;; Code for ClojureScript
       (let [mdata    (aapi/resolve &env v)
             arglists (second (get-in mdata [:meta :arglists]))
             sym      (symbol (c/name v))
             andsym   (symbol "&")
             procarg  #(if (= % andsym) % (gensym "param"))]
         (if (pos? (count arglists))
           `(def
              ~(with-meta sym (:meta mdata))
              (fn ~@(for [args arglists]
                      (let [args (map procarg args)]
                        (if (some #(= andsym %) args)
                          (let [[sargs dargs] (split-with #(not= andsym %) args)]
                            `([~@sargs ~@dargs] (apply ~v ~@sargs ~@(rest dargs))))
                          `([~@args] (~v ~@args)))))))
           `(def ~(with-meta sym (:meta mdata)) ~v)))

       ;; Code for Clojure
       (let [vr (resolve v)
             m  (meta vr)
             n  (:name m)
             n  (with-meta n
                  (cond-> {}
                    (:dynamic m) (assoc :dynamic true)
                    (:protocol m) (assoc :protocol (:protocol m))))]
         `(let [m# (meta ~vr)]
            (def ~n (deref ~vr))
            (alter-meta! (var ~n) merge (dissoc m# :name))
            ;; (when (:macro m#)
            ;;   (.setMacro (var ~n)))
            ~vr)))))

(defmacro fmt
  "String interpolation helper. Can only be used with strings known at
  compile time. Can be used with indexed params access or sequential.

  Examples:

    (dm/fmt \"url(%)\" my-url) ; sequential
    (dm/fmt \"url(%1)\" my-url) ; indexed
  "
  [s & params]
  `(str/ffmt ~s ~@params))

(defmacro with-open
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))
         (pos? (count bindings))]}
  (reduce (fn [acc bindings]
            `(let ~(vec bindings)
               (try
                 ~acc
                 (finally
                   (d/close! ~(first bindings))))))
          `(do ~@body)
          (reverse (partition 2 bindings))))

(defmacro get-prop
  "A macro based, optimized variant of `get` that access the property
  directly on CLJS, on CLJ works as get."
  [obj prop]
  (if (:ns &env)
    (list 'js* (c/str "(~{}?." (str/snake prop) "?? ~{})") obj (list 'cljs.core/get obj prop))
    (list `c/get obj prop)))

(defn runtime-assert
  [hint f]
  (try
    (when-not (f)
      (throw (ex-info hint {:type :assertion
                            :code :expr-validation
                            :hint hint})))
    (catch #?(:clj Throwable :cljs :default) cause
      (let [data (-> (ex-data cause)
                     (assoc :type :assertion)
                     (assoc :code :expr-validation)
                     (assoc :hint hint))]
        (throw (ex-info hint data cause))))))

(defmacro assert!
  ([expr]
   `(assert! nil ~expr))
  ([hint expr]
   (let [hint  (cond
                 (vector? hint)
                 `(str/ffmt ~@hint)

                 (some? hint)
                 hint

                 :else
                 (str "expr assert: " (pr-str expr)))]
     (when *assert*
       `(runtime-assert ~hint (fn [] ~expr))))))

(defn truncate
  "Truncates a string to a certain length"
  [s max-length]
  (subs s 0 (min max-length (count s))))

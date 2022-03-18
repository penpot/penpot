;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

#_:clj-kondo/ignore
(ns app.common.data.macros
  "Data retrieval & manipulation specific macros."
  (:refer-clojure :exclude [get-in select-keys str])
  #?(:cljs (:require-macros [app.common.data.macros]))
  (:require
   #?(:clj [clojure.core :as c]
      :cljs [cljs.core :as c])
   [app.common.data :as d]
   [cljs.analyzer.api :as aapi]))

(defmacro select-keys
  "A macro version of `select-keys`. Usefull when keys vector is known
  at compile time (aprox 600% performance boost).

  It is not 100% equivalent, this macro does not removes not existing
  keys in contrast to clojure.core/select-keys"
  [target keys]
  (assert (vector? keys) "keys expected to be a vector")
  `{ ~@(mapcat (fn [key] [key (list `c/get target key)]) keys) ~@[] })

(defmacro get-in
  "A macro version of `get-in`. Usefull when the keys vector is known at
  compile time (20-40% performance improvement)."
  ([target keys]
   (assert (vector? keys) "keys expected to be a vector")
   `(-> ~target ~@(map (fn [key] (list `c/get key)) keys)))
  ([target keys default]
   (assert (vector? keys) "keys expected to be a vector")
   `(let [v# (-> ~target ~@(map (fn [key] (list `c/get key)) keys))]
      (if (some? v#) v# ~default))))


;; => benchmarking: clojure.core/str
;; --> WARM:  100000
;; --> BENCH: 500000
;; --> TOTAL: 197.82ms
;; --> MEAN:  395.64ns
;; => benchmarking: app.commons.data.macros/str
;; --> WARM:  100000
;; --> BENCH: 500000
;; --> TOTAL: 20.31ms
;; --> MEAN:  40.63ns

(defmacro str
  "CLJS only macro variant of `str` function that performs string concat much faster."
  ([a]
   (if (:ns &env)
     (list 'js* "\"\"+~{}" a)
     (list `c/str a)))
  ([a b]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}" a b)
     (list `c/str a b)))
  ([a b c]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}+~{}" a b c)
     (list `c/str a b c)))
  ([a b c d]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}+~{}+~{}" a b c d)
     (list `c/str a b c d)))
  ([a b c d e]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}+~{}+~{}+~{}" a b c d e)
     (list `c/str a b c d e)))
  ([a b c d e f]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}+~{}+~{}+~{}+~{}" a b c d e f)
     (list `c/str a b c d e f)))
  ([a b c d e f g]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}+~{}+~{}+~{}+~{}+~{}" a b c d e f g)
     (list `c/str a b c d e f g)))
  ([a b c d e f g h]
   (if (:ns &env)
     (list 'js* "\"\"+~{}+~{}+~{}+~{}+~{}+~{}+~{}+~{}" a b c d e f g h)
     (list `c/str a b c d e f g h)))
  ([a b c d e f g h & rest]
   (let [all (into [a b c d e f g h] rest)]
     (if (:ns &env)
       (let [xf   (map (fn [items] `(str ~@items)))
             pall (partition-all 8 all)]
         (if (<= (count all) 64)
           `(str ~@(sequence xf pall))
           `(c/str ~@(sequence xf pall))))
       `(c/str ~@all)))))

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
         ~vr))))

(defn- interpolate
  [s params]
  (loop [items  (->> (re-seq #"([^\%]+)*(\%(\d+)?)?" s)
                     (remove (fn [[full seg]] (and (nil? seg) (not full)))))
         result []
         index  0]
    (if-let [[_ segment var? sidx] (first items)]
      (cond
        (and var? sidx)
        (let [cidx (dec (d/read-string sidx))]
          (recur (rest items)
                 (-> result
                     (conj segment)
                     (conj (nth params cidx)))
                 (inc index)))

        var?
        (recur (rest items)
               (-> result
                   (conj segment)
                   (conj (nth params index)))
               (inc index))

        :else
        (recur (rest items)
               (conj result segment)
               (inc index)))

      (remove nil? result))))

(defmacro fmt
  "String interpolation helper. Can only be used with strings known at
  compile time. Can be used with indexed params access or sequential.

  Examples:

    (dm/fmt \"url(%)\" my-url) ; sequential
    (dm/fmt \"url(%1)\" my-url) ; indexed
  "
  [s & params]
  (cons 'app.common.data.macros/str (interpolate s (vec params))))




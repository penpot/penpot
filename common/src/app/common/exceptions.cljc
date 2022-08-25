;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.exceptions
  "A helpers for work with exceptions."
  #?(:cljs
     (:require-macros [app.common.exceptions]))
  (:require [clojure.spec.alpha :as s]))

(s/def ::type keyword?)
(s/def ::code keyword?)
(s/def ::hint string?)
(s/def ::cause #?(:clj #(instance? Throwable %)
                  :cljs #(instance? js/Error %)))

(s/def ::error-params
  (s/keys :req-un [::type]
          :opt-un [::code
                   ::hint
                   ::cause]))

(defn error
  [& {:keys [hint cause ::data type] :as params}]
  (s/assert ::error-params params)
  (let [payload (-> params
                    (dissoc :cause ::data)
                    (merge data))
        hint    (or hint (pr-str type))]
    (ex-info hint payload cause)))

(defmacro raise
  [& args]
  `(throw (error ~@args)))

(defn try*
  [f on-error]
  (try (f) (catch #?(:clj Throwable :cljs :default) e (on-error e))))

;; http://clj-me.cgrand.net/2013/09/11/macros-closures-and-unexpected-object-retention/
;; Explains the use of ^:once metadata

(defmacro ignoring
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) (constantly nil)))

(defmacro try
  [& exprs]
  `(try* (^:once fn* [] ~@exprs) identity))

(defn with-always
  "A helper that evaluates an exptession independently if the body
  raises exception or not."
  [always-expr & body]
  `(try ~@body (finally ~always-expr)))

(defn ex-info?
  [v]
  (instance? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) v))

(defn exception?
  [v]
  (instance? #?(:clj java.lang.Throwable :cljs js/Error) v))


#?(:cljs
   (deftype WrappedException [cause meta]
     cljs.core/IMeta
     (-meta [_] meta)

     cljs.core/IDeref
     (-deref [_] cause))
   :clj
   (deftype WrappedException [cause meta]
     clojure.lang.IMeta
     (meta [_] meta)

     clojure.lang.IDeref
     (deref [_] cause)))


#?(:clj (ns-unmap 'app.common.exceptions '->WrappedException))
#?(:clj (ns-unmap 'app.common.exceptions 'map->WrappedException))

(defn wrapped?
  [o]
  (instance? WrappedException o))

(defn wrap-with-context
  [cause context]
  (WrappedException. cause context))

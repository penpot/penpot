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
(s/def ::message string?)
(s/def ::hint string?)
(s/def ::cause #?(:clj #(instance? Throwable %)
                  :cljs #(instance? js/Error %)))
(s/def ::error-params
  (s/keys :req-un [::type]
          :opt-un [::code
                   ::hint
                   ::message
                   ::cause]))

(defn error
  [& {:keys [message hint cause] :as params}]
  (s/assert ::error-params params)
  (let [message (or message hint "")
        payload (-> params
                    (dissoc :cause)
                    (dissoc :message)
                    (assoc :hint message))]
    (ex-info message payload cause)))

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

(defn ex-info?
  [v]
  (instance? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) v))

(defn exception?
  [v]
  (instance? #?(:clj java.lang.Throwable :cljs js/Error) v))

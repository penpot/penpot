;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.util
  (:refer-clojure :exclude [doseq])
  (:require [promesa.core :as p])
  (:import io.vertx.core.Vertx
           io.vertx.core.Handler
           io.vertx.core.Context
           io.vertx.core.AsyncResult
           java.util.function.Supplier))

(defn resolve-system
  [o]
  (cond
    (instance? Vertx o) o
    (instance? Context o) (.owner ^Context o)
    :else (throw (ex-info "unexpected parameters" {}))))

(defn fn->supplier
  [f]
  (reify Supplier
    (get [_] (f))))

(defn fn->handler
  [f]
  (reify Handler
    (handle [_ v]
      (f v))))

(defn deferred->handler
  [d]
  (reify Handler
    (handle [_ ar]
      (if (.failed ar)
        (p/reject! d (.cause ar))
        (p/resolve! d (.result ar))))))

(defmacro doseq
  "A faster version of doseq."
  [[bsym csym] & body]
  `(let [it# (.iterator ~csym)]
     (loop []
       (when (.hasNext it#)
         (let [~bsym (.next it#)]
           ~@body
           (recur))))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns vertx.util
  (:refer-clojure :exclude [loop doseq])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [clojure.core :as c]
   [promesa.core :as p]
   [vertx.impl :as impl])
  (:import
   io.vertx.core.AsyncResult
   io.vertx.core.Context
   io.vertx.core.Handler
   io.vertx.core.Promise
   io.vertx.core.Vertx))

(defn get-or-create-context
  [vsm]
  (.getOrCreateContext ^Vertx (impl/resolve-system vsm)))

(defn current-context
  "Returns the current context or nil."
  []
  (Vertx/currentContext))

(defmacro blocking
  [& body]
  (let [sym-vsm (with-meta (gensym "blocking")
                  {:tag 'io.vertx.core.Vertx})
        sym-e   (with-meta (gensym "blocking")
                  {:tag 'java.lang.Throwable})
        sym-prm (gensym "blocking")
        sym-ar  (gensym "blocking")]
    `(let [~sym-vsm (-> (current-context)
                        (impl/resolve-system))
           d# (p/deferred)]
       (.executeBlocking
        ~sym-vsm
        (reify Handler
          (handle [_ ~sym-prm]
            (let [prm# ~(with-meta sym-prm {:tag 'io.vertx.core.Promise})]
              (try
                (.complete prm# (do ~@body))
                (catch Throwable ~sym-e
                  (.fail prm# ~sym-e))))))
        true
        (reify Handler
          (handle [_ ~sym-ar]
            (let [ar# ~(with-meta sym-ar {:tag 'io.vertx.core.AsyncResult})]
              (if (.failed ar#)
                (p/reject! d# (.cause ar#))
                (p/resolve! d# (.result ar#)))))))
       d#)))

(defn wrap-blocking
  ([f] (wrap-blocking (current-context) f))
  ([ctx f]
   (let [^Vertx vsm (impl/resolve-system ctx)]
     (fn [& args]
       (let [d (p/deferred)]
         (.executeBlocking
          vsm
          (reify Handler
            (handle [_ prm]
              (try
                (.complete ^Promise prm (apply f args))
                (catch Throwable e
                  (.fail ^Promise prm e)))))
          true
          (reify Handler
            (handle [_ ar]
              (if (.failed ^AsyncResult ar)
                (p/reject! d (.cause ^AsyncResult ar))
                (p/resolve! d (.result ^AsyncResult ar))))))
         d)))))

(defn handle-on-context
  "Attaches the context (current if not explicitly provided) to the
  promise execution chain."
  ([prm] (handle-on-context prm (current-context)))
  ([prm ctx]
   (assert (instance? Context ctx) "`ctx` should be a valid Context instance")
   (let [d (p/deferred)]
     (p/finally prm (fn [v e]
                      (.runOnContext
                       ^Context ctx
                       ^Handler (reify Handler
                                  (handle [_ v']
                                    (if e
                                      (p/reject! d e)
                                      (p/resolve! d v)))))))
     d)))

(defn run-on-context!
  "Run callbale on context."
  [ctx f]
  (.runOnContext ^Context ctx
                 ^Handler (reify Handler
                            (handle [_ v']
                              (f)))))


(defmacro loop
  [& args]
  `(let [ctx# (current-context)]
     (binding [p/*loop-run-fn* #(run-on-context! ctx# %)]
       (p/loop ~@args))))

(defmacro doseq
  "A faster version of doseq."
  [[bsym csym] & body]
  (let [itsym (gensym "iterator")]
    `(let [~itsym (.iterator ~(with-meta csym {:tag 'java.lang.Iterable}))]
       (c/loop []
         (when (.hasNext ~(with-meta itsym {:tag 'java.util.Iterator}))
           (let [~bsym (.next ~itsym)]
             ~@body
             (recur)))))))


(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Throwable e# e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Throwable r#)
       (throw r#)
       r#)))


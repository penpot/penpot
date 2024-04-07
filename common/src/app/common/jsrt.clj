;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.jsrt
  "A JS runtime for the JVM"
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.java.io :as io])
  (:import
   org.apache.commons.pool2.ObjectPool
   org.apache.commons.pool2.PooledObject
   org.apache.commons.pool2.PooledObjectFactory
   org.apache.commons.pool2.impl.DefaultPooledObject
   org.apache.commons.pool2.impl.SoftReferenceObjectPool
   org.graalvm.polyglot.Context
   org.graalvm.polyglot.Source
   org.graalvm.polyglot.Value))

(defn resource->source
  [path]
  (let [resource (io/resource path)]
    (.. (Source/newBuilder "js" resource)
        (build))))

(defn pool?
  [o]
  (instance? ObjectPool o))

(defn pool
  [& {:keys [init]}]
  (SoftReferenceObjectPool.
   (reify PooledObjectFactory
     (activateObject [_ _])
     (destroyObject [_ o]
       (let [context (.getObject ^PooledObject o)]
         (.close ^java.lang.AutoCloseable context)))

     (destroyObject [_ o _]
       (let [context (.getObject ^PooledObject o)]
         (.close ^java.lang.AutoCloseable context)))

     (passivateObject [_ _])
     (validateObject [_ _] true)

     (makeObject [_]
       (let [context (Context/create (into-array String ["js"]))]
         (.initialize ^Context context "js")
         (when (instance? Source init)
           (.eval ^Context context ^Source init))
         (DefaultPooledObject. context))))))

(defn run!
  [^ObjectPool pool f]
  (let [ctx (.borrowObject pool)]
    (try
      (f ctx)
      (finally
        (.returnObject pool ctx)))))

(defn eval!
  [context data & {:keys [as] :or {as :string}}]
  (let [result (.eval ^Context context "js" ^String data)]
    (case as
      (:string :str) (.asString ^Value result)
      :long          (.asLong ^Value result)
      :int           (.asInt ^Value result)
      :float         (.asFloat ^Value result)
      :double        (.asDouble ^Value result))))

(defn set!
  [context attr value]
  (let [bindings (.getBindings ^Context context "js")]
    (.putMember ^Value bindings ^String attr ^String value)
    context))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.generic-pool
  (:refer-clojure :exclude [get])
  (:import
   java.lang.AutoCloseable
   org.apache.commons.pool2.ObjectPool
   org.apache.commons.pool2.PooledObject
   org.apache.commons.pool2.PooledObjectFactory
   org.apache.commons.pool2.impl.DefaultPooledObject
   org.apache.commons.pool2.impl.SoftReferenceObjectPool))

(defn pool?
  [o]
  (instance? ObjectPool o))

(defn create
  [& {:keys [create-fn destroy-fn validate-fn dispose-fn]}]
  (SoftReferenceObjectPool.
   (reify PooledObjectFactory
     (activateObject [_ _])
     (destroyObject [_ o]
       (let [object (.getObject ^PooledObject o)]
         (destroy-fn object)))

     (destroyObject [_ o _]
       (let [object (.getObject ^PooledObject o)]
         (destroy-fn object)))

     (passivateObject [_ o]
       (when (fn? dispose-fn)
         (let [object (.getObject ^PooledObject o)]
           (dispose-fn object))))

     (validateObject [_ o]
       (if (fn? validate-fn)
         (let [object (.getObject ^PooledObject o)]
           (validate-fn object))
         true))

     (makeObject [_]
       (let [object (create-fn)]
         (DefaultPooledObject. object))))))

(defn get
  [^ObjectPool pool]
  (let [object (.borrowObject pool)]
    (reify
      clojure.lang.IDeref
      (deref [_] object)

      AutoCloseable
      (close [_]
        (.returnObject pool object)))))

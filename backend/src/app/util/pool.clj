(ns app.util.pool
  (:import
   java.lang.AutoCloseable
   org.apache.commons.pool2.PooledObject
   org.apache.commons.pool2.PooledObjectFactory
   org.apache.commons.pool2.impl.GenericObjectPool
   org.apache.commons.pool2.impl.DefaultPooledObject
   org.apache.commons.pool2.impl.BaseGenericObjectPool))

(def noop (constantly true))

(deftype CloseableWrapper [obj pool]
  clojure.lang.IDeref
  (deref [_] obj)

  AutoCloseable
  (close [_]
    (.returnObject ^GenericObjectPool pool obj)))

(defn create
  [{:keys [create destroy validate activate passivate max-idle max-total min-idle]
    :or {destroy noop
         validate noop
         activate noop
         passivate noop
         max-idle 10
         max-total 10
         min-idle 0}}]
  (let [object-factory
        (proxy [PooledObjectFactory] []
          (makeObject [] (DefaultPooledObject. (create)))
          (destroyObject [^PooledObject o] (destroy (.getObject o)))
          (validateObject [^PooledObject o] (validate (.getObject o)))
          (activateObject [^PooledObject o] (activate (.getObject o)))
          (passivateObject [^PooledObject o] (passivate (.getObject o))))

        config
        (doto (org.apache.commons.pool2.impl.GenericObjectPoolConfig.)
          (.setMaxTotal max-total)
          (.setMaxIdle max-idle)
          (.setMinIdle min-idle)
          (.setBlockWhenExhausted true))]
    (GenericObjectPool. object-factory config)))

(defn borrow
  [^GenericObjectPool pool]
  (.borrowObject pool))

(defn return
  [^GenericObjectPool pool object]
  (.returnObject pool object))

(defn acquire
  [pool]
  (let [obj (borrow pool)]
    (CloseableWrapper. obj pool)))

(defn clear!
  "Clear idle objects in pool."
  [pool]
  (.clear ^GenericObjectPool pool))

(defn close!
  [^BaseGenericObjectPool pool]
  (.close pool))

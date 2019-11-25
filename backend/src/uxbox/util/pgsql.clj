;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.pgsql
  "Asynchronous posgresql client."
  (:require
   [promesa.core :as p])
  (:import
   clojure.lang.IDeref
   java.util.function.Supplier
   java.lang.ThreadLocal
   io.vertx.core.Vertx
   io.vertx.core.Handler
   io.vertx.core.AsyncResult
   io.vertx.core.buffer.Buffer
   io.vertx.pgclient.PgPool
   io.vertx.sqlclient.impl.ArrayTuple
   io.vertx.sqlclient.RowSet
   io.vertx.sqlclient.PoolOptions))

(declare impl-execute)
(declare impl-query)
(declare impl-handler)
(declare impl-transact)
(declare seqable->tuple)

;; --- Public Api

(defn vertx?
  [v]
  (instance? Vertx v))

(defn pool?
  [v]
  (instance? PgPool v))

(defn bytes->buffer
  [data]
  (Buffer/buffer ^bytes data))

(defn pool
  ([uri] (pool uri {}))
  ([uri {:keys [system max-size] :or {max-size 5}}]
   (let [^PoolOptions poptions (PoolOptions.)]
     (when max-size (.setMaxSize poptions max-size))
     (if (vertx? system)
       (PgPool/pool ^Vertx system ^String uri poptions)
       (PgPool/pool ^String uri poptions)))))

(defn tl-pool
  "Thread local based connection pool."
  ([uri] (tl-pool uri {}))
  ([uri options]
   (let [state (ThreadLocal/withInitial (reify Supplier
                                          (get [_]
                                            (pool uri options))))]
     (reify IDeref
       (deref [_]
         (.get state))))))

(defn query
  ([conn sqlv] (query conn sqlv {}))
  ([conn sqlv opts]
   (cond
     (vector? sqlv)
     (impl-query conn (first sqlv) (rest sqlv) opts)

     (string? sqlv)
     (impl-query conn sqlv nil opts)

     :else
     (throw (ex-info "Invalid arguments" {:sqlv sqlv})))))

(defn query-one
  [& args]
  (p/map first (apply query args)))

(defn row->map
  [row]
  (reduce (fn [acc index]
            (let [cname (.getColumnName row index)]
              (assoc acc cname (.getValue row index))))
          {}
          (range (.size row))))

(defmacro with-atomic
  [[bsym psym] & body]
  `(impl-transact ~psym (fn [c#] (let [~bsym c#] ~@body))))

;; --- Implementation

(defn- seqable->tuple
  [v]
  (let [res (ArrayTuple. (count v))]
    (run! #(.addValue res %) v)
    res))

(defn- impl-handler
  [resolve reject]
  (reify Handler
    (handle [_ ar]
      (if (.failed ar)
        (reject (.cause ar))
        (resolve (.result ar))))))

(defn- impl-execute
  [conn sql params]
  (if (seq params)
    (p/create #(.preparedQuery conn sql (seqable->tuple params) (impl-handler %1 %2)))
    (p/create #(.query conn sql (impl-handler %1 %2)))))

(defn- impl-query
  [conn sql params {:keys [xfm] :as opts}]
  (let [conn (if (instance? IDeref conn) @conn conn)]
    (-> (impl-execute conn sql params)
        (p/catch' (fn [err]
                    (p/rejected err)))
        (p/then' (fn [rows]
                   (if xfm
                     (into [] xfm rows)
                     (into [] (map vec) rows)))))))

(defn impl-transact
  [pool f]
  (let [pool (if (instance? IDeref pool) @pool pool)]
    (letfn [(commit [tx]
              (p/create #(.commit tx (impl-handler %1 %2))))
            (rollback [tx]
              (p/create #(.rollback tx (impl-handler %1 %2))))
            (on-connect [conn]
              (let [tx (.begin conn)
                    df (p/deferred)]
                (-> (f conn)
                    (p/finally (fn [v e]
                                 (if e
                                   (-> (rollback tx)
                                       (p/finally (fn [& args]
                                                    (.close conn)
                                                    (p/reject! df e))))
                                   (-> (commit tx)
                                       (p/finally (fn [_ e']
                                                    (.close conn)
                                                    (if e'
                                                      (p/reject! df e')
                                                      (p/resolve! df v)))))))))
                df))]
      (-> (p/create #(.getConnection pool (impl-handler %1 %2)))
          (p/bind on-connect)))))


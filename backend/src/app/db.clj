;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.db
  (:refer-clojure :exclude [get run!])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.db.sql :as sql]
   [app.metrics :as mtx]
   [app.util.json :as json]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as jdbc-dt])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariDataSource
   com.zaxxer.hikari.metrics.prometheus.PrometheusMetricsTrackerFactory
   io.whitfin.siphash.SipHasher
   io.whitfin.siphash.SipHasherContainer
   java.io.InputStream
   java.io.OutputStream
   java.sql.Connection
   java.sql.Savepoint
   org.postgresql.PGConnection
   org.postgresql.geometric.PGpoint
   org.postgresql.jdbc.PgArray
   org.postgresql.largeobject.LargeObject
   org.postgresql.largeobject.LargeObjectManager
   org.postgresql.util.PGInterval
   org.postgresql.util.PGobject))

(declare open)
(declare create-pool)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::connection-timeout ::us/integer)
(s/def ::max-size ::us/integer)
(s/def ::min-size ::us/integer)
(s/def ::name keyword?)
(s/def ::password ::us/string)
(s/def ::uri ::us/not-empty-string)
(s/def ::username ::us/string)
(s/def ::validation-timeout ::us/integer)
(s/def ::read-only? ::us/boolean)

(s/def ::pool-options
  (s/keys :opt [::uri
                ::name
                ::min-size
                ::max-size
                ::connection-timeout
                ::validation-timeout
                ::username
                ::password
                ::mtx/metrics
                ::read-only?]))

(def defaults
  {::name :main
   ::min-size 0
   ::max-size 60
   ::connection-timeout 10000
   ::validation-timeout 10000
   ::idle-timeout 120000 ; 2min
   ::max-lifetime 1800000 ; 30m
   ::read-only? false})

(defmethod ig/prep-key ::pool
  [_ cfg]
  (merge defaults (d/without-nils cfg)))

;; Don't validate here, just validate that a map is received.
(defmethod ig/pre-init-spec ::pool [_] ::pool-options)

(defmethod ig/init-key ::pool
  [_ {:keys [::uri ::read-only?] :as cfg}]
  (when uri
    (l/info :hint "initialize connection pool"
            :name (d/name (::name cfg))
            :uri uri
            :read-only read-only?
            :with-credentials (and (contains? cfg ::username)
                                   (contains? cfg ::password))
            :min-size (::min-size cfg)
            :max-size (::max-size cfg))
    (create-pool cfg)))

(defmethod ig/halt-key! ::pool
  [_ pool]
  (when pool
    (.close ^HikariDataSource pool)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API & Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initsql
  (str "SET statement_timeout = 300000;\n"
       "SET idle_in_transaction_session_timeout = 300000;"))

(defn- create-datasource-config
  [{:keys [::mtx/metrics ::uri] :as cfg}]
  (let [config (HikariConfig.)]
    (doto config
      (.setJdbcUrl           (str "jdbc:" uri))
      (.setPoolName          (d/name (::name cfg)))
      (.setAutoCommit true)
      (.setReadOnly          (::read-only? cfg))
      (.setConnectionTimeout (::connection-timeout cfg))
      (.setValidationTimeout (::validation-timeout cfg))
      (.setIdleTimeout       (::idle-timeout cfg))
      (.setMaxLifetime       (::max-lifetime cfg))
      (.setMinimumIdle       (::min-size cfg))
      (.setMaximumPoolSize   (::max-size cfg))
      (.setConnectionInitSql initsql)
      (.setInitializationFailTimeout -1))

    ;; When metrics namespace is provided
    (when metrics
      (->> (::mtx/registry metrics)
           (PrometheusMetricsTrackerFactory.)
           (.setMetricsTrackerFactory config)))

    (some->> ^String (::username cfg) (.setUsername config))
    (some->> ^String (::password cfg) (.setPassword config))

    config))

(defn pool?
  [v]
  (instance? javax.sql.DataSource v))

(defn connection?
  [conn]
  (instance? Connection conn))

(s/def ::conn some?)
(s/def ::nilable-pool (s/nilable ::pool))
(s/def ::pool pool?)
(s/def ::pool-or-conn some?)

(defn closed?
  [pool]
  (.isClosed ^HikariDataSource pool))

(defn read-only?
  [pool-or-conn]
  (cond
    (instance? HikariDataSource pool-or-conn)
    (.isReadOnly ^HikariDataSource pool-or-conn)

    (instance? Connection pool-or-conn)
    (.isReadOnly ^Connection pool-or-conn)

    :else
    (ex/raise :type :internal
              :code :invalid-connection
              :hint "invalid connection provided")))

(defn create-pool
  [cfg]
  (let [dsc (create-datasource-config cfg)]
    (jdbc-dt/read-as-instant)
    (HikariDataSource. dsc)))

(defn unwrap
  [conn klass]
  (.unwrap ^Connection conn klass))

(defn lobj-manager
  [conn]
  (let [conn (unwrap conn org.postgresql.PGConnection)]
    (.getLargeObjectAPI ^PGConnection conn)))

(defn lobj-create
  [manager]
  (.createLO ^LargeObjectManager manager LargeObjectManager/READWRITE))

(defn lobj-open
  ([manager oid]
   (lobj-open manager oid {}))
  ([manager oid {:keys [mode] :or {mode :rw}}]
   (let [mode (case mode
                (:r :read) LargeObjectManager/READ
                (:w :write) LargeObjectManager/WRITE
                (:rw :read+write) LargeObjectManager/READWRITE)]
     (.open ^LargeObjectManager manager (long oid) mode))))

(defn lobj-unlink
  [manager oid]
  (.unlink ^LargeObjectManager manager (long oid)))

(extend-type LargeObject
  io/IOFactory
  (make-reader [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-reader is opts)))
  (make-writer [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-writer os opts)))
  (make-input-stream [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-input-stream is opts)))
  (make-output-stream [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-output-stream os opts))))

(defmacro with-atomic
  [& args]
  (if (symbol? (first args))
    (let [cfgs (first args)
          body (rest args)]
      `(jdbc/with-transaction [conn# (::pool ~cfgs)]
         (let [~cfgs (assoc ~cfgs ::conn conn#)]
           ~@body)))
    `(jdbc/with-transaction ~@args)))

(defn open
  [system-or-pool]
  (if (pool? system-or-pool)
    (jdbc/get-connection system-or-pool)
    (if (map? system-or-pool)
      (open (::pool system-or-pool))
      (ex/raise :type :internal
                :code :unable-resolve-pool))))

(defn get-connection
  [cfg-or-conn]
  (if (connection? cfg-or-conn)
    cfg-or-conn
    (if (map? cfg-or-conn)
      (get-connection (::conn cfg-or-conn))
      (ex/raise :type :internal
                :code :unable-resolve-connection
                :hint "expected conn or system map"))))

(defn connection-map?
  "Check if the provided value is a map like data structure that
  contains a database connection."
  [o]
  (and (map? o) (connection? (::conn o))))

(defn- get-connectable
  [o]
  (cond
    (connection? o) o
    (pool? o)       o
    (map? o)        (get-connectable (or (::conn o) (::pool o)))
    :else           (ex/raise :type :internal
                              :code :unable-resolve-connectable
                              :hint "expected conn, pool or system")))

(def ^:private default-opts
  {:builder-fn sql/as-kebab-maps})

(defn exec!
  ([ds sv]
   (-> (get-connectable ds)
       (jdbc/execute! sv default-opts)))
  ([ds sv opts]
   (-> (get-connectable ds)
       (jdbc/execute! sv (into default-opts (sql/adapt-opts opts))))))

(defn exec-one!
  ([ds sv]
   (-> (get-connectable ds)
       (jdbc/execute-one! sv default-opts)))
  ([ds sv opts]
   (-> (get-connectable ds)
       (jdbc/execute-one! sv (into default-opts (sql/adapt-opts opts))))))

(defn insert!
  [ds table params & {:as opts :keys [::return-keys?] :or {return-keys? true}}]
  (-> (get-connectable ds)
      (exec-one! (sql/insert table params opts)
                 (assoc opts ::return-keys? return-keys?))))

(defn insert-multi!
  [ds table cols rows & {:as opts :keys [::return-keys?] :or {return-keys? true}}]
  (-> (get-connectable ds)
      (exec! (sql/insert-multi table cols rows opts)
             (assoc opts ::return-keys? return-keys?))))

(defn update!
  [ds table params where & {:as opts :keys [::return-keys?] :or {return-keys? true}}]
  (-> (get-connectable ds)
      (exec-one! (sql/update table params where opts)
                 (assoc opts ::return-keys? return-keys?))))

(defn delete!
  [ds table params & {:as opts :keys [::return-keys?] :or {return-keys? true}}]
  (-> (get-connectable ds)
      (exec-one! (sql/delete table params opts)
                 (assoc opts ::return-keys? return-keys?))))

(defn is-row-deleted?
  [{:keys [deleted-at]}]
  (and (dt/instant? deleted-at)
       (< (inst-ms deleted-at)
          (inst-ms (dt/now)))))

(defn get*
  "Retrieve a single row from database that matches a simple filters. Do
  not raises exceptions."
  [ds table params & {:as opts}]
  (let [rows (exec! ds (sql/select table params opts))
        rows (cond->> rows
               (::remove-deleted? opts true)
               (remove is-row-deleted?))]
    (first rows)))

(defn get
  "Retrieve a single row from database that matches a simple
  filters. Raises :not-found exception if no object is found."
  [ds table params & {:as opts}]
  (let [row (get* ds table params opts)]
    (when (and (not row) (::check-deleted? opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :table table
                :hint "database object not found"))
    row))

(defn plan
  [ds sql]
  (-> (get-connectable ds)
      (jdbc/plan sql sql/default-opts)))

(defn get-by-id
  [ds table id & {:as opts}]
  (get ds table {:id id} opts))

(defn query
  [ds table params & {:as opts}]
  (exec! ds (sql/select table params opts)))

(defn pgobject?
  ([v]
   (instance? PGobject v))
  ([v type]
   (and (instance? PGobject v)
        (= type (.getType ^PGobject v)))))

(defn pginterval?
  [v]
  (instance? PGInterval v))

(defn pgpoint?
  [v]
  (instance? PGpoint v))

(defn pgarray?
  ([v] (instance? PgArray v))
  ([v type]
   (and (instance? PgArray v)
        (= type (.getBaseTypeName ^PgArray v)))))

(defn pgarray-of-uuid?
  [v]
  (and (pgarray? v) (= "uuid" (.getBaseTypeName ^PgArray v))))

;; TODO rename to decode-pgarray-into
(defn decode-pgarray
  ([v] (decode-pgarray v []))
  ([v in]
   (into in (some-> ^PgArray v .getArray)))
  ([v in xf]
   (into in xf (some-> ^PgArray v .getArray))))

(defn pgarray->set
  [v]
  (set (.getArray ^PgArray v)))

(defn pgarray->vector
  [v]
  (vec (.getArray ^PgArray v)))

(defn pgpoint
  [p]
  (PGpoint. (:x p) (:y p)))

(defn create-array
  [conn type objects]
  (let [^PGConnection conn (unwrap conn org.postgresql.PGConnection)]
    (if (coll? objects)
      (.createArrayOf conn ^String type (into-array Object objects))
      (.createArrayOf conn ^String type objects))))

(defn decode-pgpoint
  [^PGpoint v]
  (gpt/point (.-x v) (.-y v)))

(defn pginterval
  [data]
  (org.postgresql.util.PGInterval. ^String data))

(defn savepoint
  ([^Connection conn]
   (.setSavepoint conn))
  ([^Connection conn label]
   (.setSavepoint conn (name label))))

(defn release!
  [^Connection conn ^Savepoint sp]
  (.releaseSavepoint conn sp))

(defn rollback!
  ([conn]
   (let [^Connection conn (get-connection conn)]
     (l/trc :hint "explicit rollback requested")
     (.rollback conn)))
  ([conn ^Savepoint sp]
   (let [^Connection conn (get-connection conn)]
     (l/trc :hint "explicit rollback requested")
     (.rollback conn sp))))

(defn tx-run!
  [system f & params]
  (cond
    (connection? system)
    (tx-run! {::conn system} f)

    (pool? system)
    (tx-run! {::pool system} f)

    (::conn system)
    (let [conn (::conn system)
          sp   (savepoint conn)]
      (try
        (let [result (apply f system params)]
          (release! conn sp)
          result)
        (catch Throwable cause
          (rollback! conn sp)
          (throw cause))))

    (::pool system)
    (with-atomic [conn (::pool system)]
      (let [system (assoc system ::conn conn)
            result (apply f system params)]
        (when (::rollback system)
          (rollback! conn))
        result))

    :else
    (throw (IllegalArgumentException. "invalid arguments"))))

(defn run!
  [system f & params]
  (cond
    (connection? system)
    (run! {::conn system} f)

    (pool? system)
    (run! {::pool system} f)

    (::conn system)
    (apply f system params)

    (::pool system)
    (with-open [^Connection conn (open (::pool system))]
      (apply f (assoc system ::conn conn) params))

    :else
    (throw (IllegalArgumentException. "invalid arguments"))))

(defn interval
  [o]
  (cond
    (or (integer? o)
        (float? o))
    (->> (/ o 1000.0)
         (format "%s seconds")
         (pginterval))

    (string? o)
    (pginterval o)

    (dt/duration? o)
    (interval (inst-ms o))

    :else
    (ex/raise :type :not-implemented
              :hint (format "no implementation found for value %s" (pr-str o)))))

(defn decode-json-pgobject
  [^PGobject o]
  (when o
    (let [typ (.getType o)
          val (.getValue o)]
      (if (or (= typ "json")
              (= typ "jsonb"))
        (json/decode val)
        val))))

(defn decode-transit-pgobject
  [^PGobject o]
  (when o
    (let [typ (.getType o)
          val (.getValue o)]
      (if (or (= typ "json")
              (= typ "jsonb"))
        (t/decode-str val)
        val))))

(defn inet
  [ip-addr]
  (when ip-addr
    (doto (org.postgresql.util.PGobject.)
      (.setType "inet")
      (.setValue (str ip-addr)))))

(defn decode-inet
  [^PGobject o]
  (when o
    (if (= "inet" (.getType o))
      (.getValue o)
      nil)))

(defn tjson
  "Encode as transit json."
  [data]
  (when data
    (doto (org.postgresql.util.PGobject.)
      (.setType "jsonb")
      (.setValue (t/encode-str data {:type :json-verbose})))))

(defn json
  "Encode as plain json."
  [data]
  (when data
    (doto (org.postgresql.util.PGobject.)
      (.setType "jsonb")
      (.setValue (json/encode-str data)))))

(defn get-update-count
  [result]
  (:next.jdbc/update-count result))


;; --- Locks

(def ^:private siphash-state
  (SipHasher/container
   (uuid/get-bytes uuid/zero)))

(defn uuid->hash-code
  [o]
  (.hash ^SipHasherContainer siphash-state
         ^bytes (uuid/get-bytes o)))

(defn- xact-check-param
  [n]
  (cond
    (uuid? n) (uuid->hash-code n)
    (int? n)  n
    :else (throw (IllegalArgumentException. "uuid or number allowed"))))

(defn xact-lock!
  [conn n]
  (let [n (xact-check-param n)]
    (exec-one! conn ["select pg_advisory_xact_lock(?::bigint) as lock" n])
    true))

(defn xact-try-lock!
  [conn n]
  (let [n   (xact-check-param n)
        row (exec-one! conn ["select pg_try_advisory_xact_lock(?::bigint) as lock" n])]
    (:lock row)))

(defn sql-exception?
  [cause]
  (instance? java.sql.SQLException cause))

(defn connection-error?
  [cause]
  (and (sql-exception? cause)
       (contains? #{"08003" "08006" "08001" "08004"}
                  (.getSQLState ^java.sql.SQLException cause))))

(defn serialization-error?
  [cause]
  (and (sql-exception? cause)
       (= "40001" (.getSQLState ^java.sql.SQLException cause))))

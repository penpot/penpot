;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.db
  (:refer-clojure :exclude [get])
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
   [app.util.migrations :as mg]
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
   java.lang.AutoCloseable
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

(declare apply-migrations!)

(s/def ::connection-timeout ::us/integer)
(s/def ::max-size ::us/integer)
(s/def ::min-size ::us/integer)
(s/def ::migrations map?)
(s/def ::name keyword?)
(s/def ::password ::us/string)
(s/def ::uri ::us/not-empty-string)
(s/def ::username ::us/string)
(s/def ::validation-timeout ::us/integer)
(s/def ::read-only? ::us/boolean)

(s/def ::pool-options
  (s/keys :opt-un [::uri ::name
                   ::min-size
                   ::max-size
                   ::connection-timeout
                   ::validation-timeout
                   ::migrations
                   ::username
                   ::password
                   ::mtx/metrics
                   ::read-only?]))

(def defaults
  {:name :main
   :min-size 0
   :max-size 60
   :connection-timeout 10000
   :validation-timeout 10000
   :idle-timeout 120000 ; 2min
   :max-lifetime 1800000 ; 30m
   :read-only? false})

(defmethod ig/prep-key ::pool
  [_ cfg]
  (merge defaults (d/without-nils cfg)))

;; Don't validate here, just validate that a map is received.
(defmethod ig/pre-init-spec ::pool [_] ::pool-options)

(defmethod ig/init-key ::pool
  [_ {:keys [migrations read-only? uri] :as cfg}]
  (if uri
    (let [pool (create-pool cfg)]
      (l/info :hint "initialize connection pool"
              :name (d/name (:name cfg))
              :uri uri
              :read-only read-only?
              :with-credentials (and (contains? cfg :username)
                                     (contains? cfg :password))
              :min-size (:min-size cfg)
              :max-size (:max-size cfg))
      (when-not read-only?
        (some->> (seq migrations) (apply-migrations! pool)))
      pool)

    (do
      (l/warn :hint "unable to initialize pool, missing url"
              :name (d/name (:name cfg))
              :read-only read-only?)
      nil)))

(defmethod ig/halt-key! ::pool
  [_ pool]
  (when pool
    (.close ^HikariDataSource pool)))

(defn- apply-migrations!
  [pool migrations]
  (with-open [conn ^AutoCloseable (open pool)]
    (mg/setup! conn)
    (doseq [[name steps] migrations]
      (mg/migrate! conn {:name (d/name name) :steps steps}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API & Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initsql
  (str "SET statement_timeout = 300000;\n"
       "SET idle_in_transaction_session_timeout = 300000;"))

(defn- create-datasource-config
  [{:keys [metrics uri] :as cfg}]
  (let [config (HikariConfig.)]
    (doto config
      (.setJdbcUrl           (str "jdbc:" uri))
      (.setPoolName          (d/name (:name cfg)))
      (.setAutoCommit true)
      (.setReadOnly          (:read-only? cfg))
      (.setConnectionTimeout (:connection-timeout cfg))
      (.setValidationTimeout (:validation-timeout cfg))
      (.setIdleTimeout       (:idle-timeout cfg))
      (.setMaxLifetime       (:max-lifetime cfg))
      (.setMinimumIdle       (:min-size cfg))
      (.setMaximumPoolSize   (:max-size cfg))
      (.setConnectionInitSql initsql)
      (.setInitializationFailTimeout -1))

    ;; When metrics namespace is provided
    (when metrics
      (->> (::mtx/registry metrics)
           (PrometheusMetricsTrackerFactory.)
           (.setMetricsTrackerFactory config)))

    (some->> ^String (:username cfg) (.setUsername config))
    (some->> ^String (:password cfg) (.setPassword config))

    config))

(defn pool?
  [v]
  (instance? javax.sql.DataSource v))

(s/def ::pool pool?)

(defn closed?
  [pool]
  (.isClosed ^HikariDataSource pool))

(defn read-only?
  [pool]
  (.isReadOnly ^HikariDataSource pool))

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
  `(jdbc/with-transaction ~@args))

(defn open
  [pool]
  (jdbc/get-connection pool))

(defn exec!
  ([ds sv]
   (exec! ds sv {}))
  ([ds sv opts]
   (jdbc/execute! ds sv (assoc opts :builder-fn sql/as-kebab-maps))))

(defn exec-one!
  ([ds sv] (exec-one! ds sv {}))
  ([ds sv opts]
   (jdbc/execute-one! ds sv (assoc opts :builder-fn sql/as-kebab-maps))))

(defn insert!
  ([ds table params] (insert! ds table params nil))
  ([ds table params opts]
   (exec-one! ds
              (sql/insert table params opts)
              (merge {:return-keys true} opts))))

(defn insert-multi!
  ([ds table cols rows] (insert-multi! ds table cols rows nil))
  ([ds table cols rows opts]
   (exec! ds
          (sql/insert-multi table cols rows opts)
          (merge {:return-keys true} opts))))

(defn update!
  ([ds table params where] (update! ds table params where nil))
  ([ds table params where opts]
   (exec-one! ds
              (sql/update table params where opts)
              (merge {:return-keys true} opts))))

(defn delete!
  ([ds table params] (delete! ds table params nil))
  ([ds table params opts]
   (exec-one! ds
              (sql/delete table params opts)
              (assoc opts :return-keys true))))

(defn is-row-deleted?
  [{:keys [deleted-at]}]
  (and (dt/instant? deleted-at)
       (< (inst-ms deleted-at)
          (inst-ms (dt/now)))))

(defn get*
  "Internal function for retrieve a single row from database that
  matches a simple filters."
  ([ds table params]
   (get* ds table params nil))
  ([ds table params {:keys [check-deleted?] :or {check-deleted? true} :as opts}]
   (let [rows (exec! ds (sql/select table params opts))
         rows (cond->> rows
                check-deleted?
                (remove is-row-deleted?))]
     (first rows))))

(defn get
  ([ds table params]
   (get ds table params nil))
  ([ds table params {:keys [check-deleted?] :or {check-deleted? true} :as opts}]
   (let [row (get* ds table params opts)]
     (when (and (not row) check-deleted?)
       (ex/raise :type :not-found
                 :table table
                 :hint "database object not found"))
     row)))

(defn get-by-params
  "DEPRECATED"
  ([ds table params]
   (get-by-params ds table params nil))
  ([ds table params {:keys [check-not-found] :or {check-not-found true} :as opts}]
   (let [row (get* ds table params (assoc opts :check-deleted? check-not-found))]
     (when (and (not row) check-not-found)
       (ex/raise :type :not-found
                 :table table
                 :hint "database object not found"))
     row)))

(defn get-by-id
  ([ds table id]
   (get ds table {:id id} nil))
  ([ds table id opts]
   (let [opts (cond-> opts
                (contains? opts :check-not-found)
                (assoc :check-deleted? (:check-not-found opts)))]
     (get ds table {:id id} opts))))

(defn query
  ([ds table params]
   (query ds table params nil))
  ([ds table params opts]
   (exec! ds (sql/select table params opts))))

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

(defn decode-pgarray
  ([v] (some->> ^PgArray v .getArray vec))
  ([v in] (some->> ^PgArray v .getArray (into in)))
  ([v in xf]  (some->> ^PgArray v .getArray (into in xf))))

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

(defn rollback!
  ([^Connection conn]
   (.rollback conn))
  ([^Connection conn ^Savepoint sp]
   (.rollback conn sp)))

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
  (let [typ (.getType o)
        val (.getValue o)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (json/read val)
      val)))

(defn decode-transit-pgobject
  [^PGobject o]
  (let [typ (.getType o)
        val (.getValue o)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (t/decode-str val)
      val)))

(defn inet
  [ip-addr]
  (doto (org.postgresql.util.PGobject.)
    (.setType "inet")
    (.setValue (str ip-addr))))

(defn decode-inet
  [^PGobject o]
  (if (= "inet" (.getType o))
    (.getValue o)
    nil))

(defn tjson
  "Encode as transit json."
  [data]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue (t/encode-str data {:type :json-verbose}))))

(defn json
  "Encode as plain json."
  [data]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str data))))

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

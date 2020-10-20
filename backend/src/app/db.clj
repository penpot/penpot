;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.db
  (:require
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.config :as cfg]
   [app.metrics :as mtx]
   [app.util.data :as data]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [lambdaisland.uri :refer [uri]]
   [mount.core :as mount :refer [defstate]]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as jdbc-dt]
   [next.jdbc.optional :as jdbc-opt]
   [next.jdbc.result-set :as jdbc-rs]
   [next.jdbc.sql :as jdbc-sql]
   [next.jdbc.sql.builder :as jdbc-bld])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariDataSource
   com.zaxxer.hikari.metrics.prometheus.PrometheusMetricsTrackerFactory
   java.sql.Connection
   java.sql.Savepoint
   org.postgresql.jdbc.PgArray
   org.postgresql.geometric.PGpoint
   org.postgresql.util.PGInterval
   org.postgresql.util.PGobject))

(def initsql
  (str "SET statement_timeout = 10000;\n"
       "SET idle_in_transaction_session_timeout = 30000;"))

(defn- create-datasource-config
  [cfg]
  (let [dburi    (:database-uri cfg)
        username (:database-username cfg)
        password (:database-password cfg)
        config (HikariConfig.)
        mfactory (PrometheusMetricsTrackerFactory. mtx/registry)]
    (doto config
      (.setJdbcUrl (str "jdbc:" dburi))
      (.setPoolName "main")
      (.setAutoCommit true)
      (.setReadOnly false)
      (.setConnectionTimeout 8000)  ;; 8seg
      (.setValidationTimeout 4000)  ;; 4seg
      (.setIdleTimeout 300000)      ;; 5min
      (.setMaxLifetime 900000)      ;; 15min
      (.setMinimumIdle 0)
      (.setMaximumPoolSize 15)
      (.setConnectionInitSql initsql)
      (.setMetricsTrackerFactory mfactory))
    (when username (.setUsername config username))
    (when password (.setPassword config password))
    config))

(defn pool?
  [v]
  (instance? javax.sql.DataSource v))

(s/def ::pool pool?)

(defn pool-closed?
  [pool]
  (.isClosed ^com.zaxxer.hikari.HikariDataSource pool))

(defn- create-pool
  [cfg]
  (let [dsc (create-datasource-config cfg)]
    (jdbc-dt/read-as-instant)
    (HikariDataSource. dsc)))

(defstate pool
  :start (create-pool cfg/config)
  :stop (.close pool))

(defmacro with-atomic
  [& args]
  `(jdbc/with-transaction ~@args))

(defn- kebab-case [s] (str/replace s #"_" "-"))
(defn- snake-case [s] (str/replace s #"-" "_"))
(defn- as-kebab-maps
  [rs opts]
  (jdbc-opt/as-unqualified-modified-maps rs (assoc opts :label-fn kebab-case)))

(defn open
  []
  (jdbc/get-connection pool))

(defn exec!
  ([ds sv]
   (exec! ds sv {}))
  ([ds sv opts]
   (jdbc/execute! ds sv (assoc opts :builder-fn as-kebab-maps))))

(defn exec-one!
  ([ds sv] (exec-one! ds sv {}))
  ([ds sv opts]
   (jdbc/execute-one! ds sv (assoc opts :builder-fn as-kebab-maps))))

(def ^:private default-options
  {:table-fn snake-case
   :column-fn snake-case
   :builder-fn as-kebab-maps})

(defn insert!
  [ds table params]
  (jdbc-sql/insert! ds table params default-options))

(defn update!
  [ds table params where]
  (let [opts (assoc default-options :return-keys true)]
    (jdbc-sql/update! ds table params where opts)))

(defn delete!
  [ds table params]
  (let [opts (assoc default-options :return-keys true)]
    (jdbc-sql/delete! ds table params opts)))

(defn get-by-params
  ([ds table params]
   (get-by-params ds table params nil))
  ([ds table params opts]
   (let [opts (cond-> (merge default-options opts)
                (:for-update opts)
                (assoc :suffix "for update"))
         res  (exec-one! ds (jdbc-bld/for-query table params opts) opts)]
     (when (or (:deleted-at res) (not res))
       (ex/raise :type :not-found))
     res)))

(defn get-by-id
  ([ds table id]
   (get-by-params ds table {:id id} nil))
  ([ds table id opts]
   (get-by-params ds table {:id id} opts)))

(defn query
  ([ds table params]
   (query ds table params nil))
  ([ds table params opts]
   (let [opts (cond-> (merge default-options opts)
                (:for-update opts)
                (assoc :suffix "for update"))]
     (exec! ds (jdbc-bld/for-query table params opts) opts))))

(defn pgobject?
  [v]
  (instance? PGobject v))

(defn pginterval?
  [v]
  (instance? PGInterval v))

(defn pgpoint?
  [v]
  (instance? PGpoint v))

(defn pgarray?
  [v]
  (instance? PgArray v))

(defn pgarray-of-uuid?
  [v]
  (and (pgarray? v) (= "uuid" (.getBaseTypeName ^PgArray v))))

(defn pgpoint
  [p]
  (PGpoint. (:x p) (:y p)))

(defn decode-pgpoint
  [^PGpoint v]
  (gpt/point (.-x v) (.-y v)))

(defn pginterval
  [data]
  (org.postgresql.util.PGInterval. ^String data))

(defn interval
  [data]
  (cond
    (integer? data)
    (->> (/ data 1000.0)
         (format "%s seconds")
         (pginterval))

    (string? data)
    (pginterval data)

    (dt/duration? data)
    (->> (/ (.toMillis data) 1000.0)
         (format "%s seconds")
         (pginterval))

    :else
    (ex/raise :type :not-implemented)))

(defn decode-pgobject
  [^PGobject obj]
  (let [typ (.getType obj)
        val (.getValue obj)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (json/read-str val)
      val)))

(defn decode-json-pgobject
  [^PGobject o]
  (let [typ (.getType o)
        val (.getValue o)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (json/read-str val :key-fn keyword)
      val)))

(defn decode-transit-pgobject
  [^PGobject o]
  (let [typ (.getType o)
        val (.getValue o)]
    (if (or (= typ "json")
            (= typ "jsonb"))
      (t/decode-str val)
      val)))

(defn tjson
  "Encode as transit json."
  [data]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue (t/encode-verbose-str data))))

(defn json
  "Encode as plain json."
  [data]
  (doto (org.postgresql.util.PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str data))))

(defn pgarray->set
  [v]
  (set (.getArray ^PgArray v)))

(defn pgarray->vector
  [v]
  (vec (.getArray ^PgArray v)))

;; Instrumentation

(mtx/instrument-with-counter!
 {:var [#'jdbc/execute-one!
        #'jdbc/execute!]
  :id "database__query_counter"
  :help "An absolute counter of database queries."})

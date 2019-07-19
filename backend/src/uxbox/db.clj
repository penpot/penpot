;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.db
  "Database access layer for UXBOX."
  (:require
   [executors.core :as exec]
   [hikari-cp.core :as hikari]
   [mount.core :as mount :refer (defstate)]
   [promesa.core :as p]
   [suricatta.core :as sc]
   [suricatta.impl :as si]
   [suricatta.proto :as sp]
   [uxbox.config :as cfg])
  (:import
   org.jooq.Configuration
   org.jooq.TransactionContext
   org.jooq.TransactionProvider
   ))

;; --- State

(def connection-defaults
  {:connection-timeout 30000
   :idle-timeout 600000
   :max-lifetime 1800000
   :minimum-idle 10
   :maximum-pool-size 10})

(defn get-db-config
  [config]
  (assoc connection-defaults
         :username (:database-username config)
         :password (:database-password config)
         :jdbc-url (:database-uri config)))

(defn create-datasource
  [config]
  (let [dbconf (get-db-config config)]
    (hikari/make-datasource dbconf)))

(defstate datasource
  :start (create-datasource cfg/config)
  :stop (hikari/close-datasource datasource))

;; --- Suricatta Async Adapter

(defn transaction
  "Asynchronous transaction handling."
  {:internal true}
  [ctx func]
  (let [^Configuration conf (.derive (sp/-config ctx))
        ^TransactionContext txctx (si/transaction-context conf)
        ^TransactionProvider provider (.transactionProvider conf)]
    (doto conf
      (.data "suricatta.rollback" false)
      (.data "suricatta.transaction" true))
    (try
      (.begin provider txctx)
      (->> (func (si/make-context conf))
           (p/map (fn [result]
                    (if (.data conf "suricatta.rollback")
                      (.rollback provider txctx)
                      (.commit provider txctx))
                    result))
           (p/error (fn [error]
                      (.rollback provider (.cause txctx error))
                      (p/rejected error))))
      (catch Exception cause
        (.rollback provider (.cause txctx cause))
        (p/rejected cause)))))

;; --- Public Api

(defmacro atomic
  [ctx & body]
  `(transaction ~ctx (fn [~ctx] ~@body)))

(defn connection
  []
  (sc/context datasource))

(defn fetch
  [& args]
  (exec/submit #(apply sc/fetch args)))

(defn execute
  [& args]
  (exec/submit #(apply sc/execute args)))

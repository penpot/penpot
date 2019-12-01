;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.migrations
  (:require
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [uxbox.util.pgsql :as pg]))

(s/def ::name string?)
(s/def ::step (s/keys :req-un [::name ::desc ::fn]))
(s/def ::steps (s/every ::step :kind vector?))
(s/def ::migrations
  (s/keys :req-un [::name ::steps]))

;; --- Implementation

(defn- registered?
  "Check if concrete migration is already registred."
  [pool modname stepname]
  (let [sql "select * from migrations where module=$1 and step=$2"]
    (-> (pg/query pool [sql modname stepname])
        (p/then' (fn [rows]
                   (pos? (count rows)))))))

(defn- register!
  "Register a concrete migration into local migrations database."
  [pool modname stepname]
  (let [sql "insert into migrations (module, step) values ($1, $2)"]
    (-> (pg/query pool [sql modname stepname])
        (p/then' (constantly nil)))))


(defn- setup!
  "Initialize the database if it is not initialized."
  [pool]
  (let [sql (str "create table if not exists migrations ("
                 " module text,"
                 " step text,"
                 " created_at timestamp DEFAULT current_timestamp,"
                 " unique(module, step)"
                 ");")]
    (-> (pg/query pool sql)
        (p/then' (constantly nil)))))

(defn- impl-migrate-single
  [pool modname {:keys [name] :as migration}]
  (letfn [(execute []
            (p/do! (register! pool modname name)
                   ((:fn migration) pool)))]
    (-> (registered? pool modname (:name migration))
        (p/then (fn [registered?]
                  (when-not registered?
                    (log/info (str/format "applying migration %s/%s" modname name))
                    (execute)))))))

(defn- impl-migrate
  [pool migrations {:keys [fake] :or {fake false}}]
  (s/assert ::migrations migrations)
  (let [mname (:name migrations)
        steps (:steps migrations)]
    ;; (println (str/format "Applying migrations for `%s`:" mname))
    (pg/with-atomic [conn pool]
      (p/run! #(impl-migrate-single conn mname %) steps))))

(defprotocol IMigrationContext
  (-migrate [_ migration options]))

;; --- Public Api

(defn context
  "Create new instance of migration context."
  ([pool] (context pool nil))
  ([pool opts]
   @(setup! pool)
   (reify
     java.lang.AutoCloseable
     (close [_] #_(.close pool))

     IMigrationContext
     (-migrate [_ migration options]
       (impl-migrate pool migration options)))))

(defn migrate
  "Main entry point for apply a migration."
  ([ctx migrations]
   (migrate ctx migrations nil))
  ([ctx migrations options]
   (-migrate ctx migrations options)))

(defn resource
  "Helper for setup migration functions
  just using a simple path to sql file
  located in the class path."
  [path]
  (fn [pool]
    (let [sql (slurp (io/resource path))]
      (-> (pg/query pool sql)
          (p/then' (constantly true))))))

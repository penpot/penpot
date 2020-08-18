;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.util.migrations
  (:require
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [next.jdbc :as jdbc]))

(s/def ::name string?)
(s/def ::step (s/keys :req-un [::name ::desc ::fn]))
(s/def ::steps (s/every ::step :kind vector?))
(s/def ::migrations
  (s/keys :req-un [::name ::steps]))

;; --- Implementation

(defn- registered?
  "Check if concrete migration is already registred."
  [pool modname stepname]
  (let [sql  "select * from migrations where module=? and step=?"
        rows (jdbc/execute! pool [sql modname stepname])]
    (pos? (count rows))))

(defn- register!
  "Register a concrete migration into local migrations database."
  [pool modname stepname]
  (let [sql "insert into migrations (module, step) values (?, ?)"]
    (jdbc/execute! pool [sql modname stepname])
    nil))

(defn- impl-migrate-single
  [pool modname {:keys [name] :as migration}]
  (letfn [(execute []
            (register! pool modname name)
            ((:fn migration) pool))]
    (when-not (registered? pool modname (:name migration))
      (log/info (str/format "applying migration %s/%s" modname name))
      (register! pool modname name)
      ((:fn migration) pool))))

(defn- impl-migrate
  [conn migrations {:keys [fake] :or {fake false}}]
  (s/assert ::migrations migrations)
  (let [mname (:name migrations)
        steps (:steps migrations)]
    (jdbc/with-transaction [conn conn]
      (run! #(impl-migrate-single conn mname %) steps))))

(defprotocol IMigrationContext
  (-migrate [_ migration options]))

;; --- Public Api
(defn setup!
  "Initialize the database if it is not initialized."
  [conn]
  (let [sql (str "create table if not exists migrations ("
                 " module text,"
                 " step text,"
                 " created_at timestamp DEFAULT current_timestamp,"
                 " unique(module, step)"
                 ");")]
    (jdbc/execute! conn [sql])
    nil))

(defn migrate!
  "Main entry point for apply a migration."
  ([conn migrations]
   (impl-migrate conn migrations nil))
  ([conn migrations options]
   (impl-migrate conn migrations options)))

(defn resource
  "Helper for setup migration functions
  just using a simple path to sql file
  located in the class path."
  [path]
  (fn [pool]
    (let [sql (slurp (io/resource path))]
      (jdbc/execute! pool [sql])
      true)))

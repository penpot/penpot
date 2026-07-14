;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.ingest
  "Penpot file -> Ladybug graph projection."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.types.file :as ctf]
   [app.db :as db]
   [app.graph.bulk :as bulk]
   [app.graph.ladybug :as ladybug]
   [app.graph.project.document :as project.document]
   [app.graph.project.transforms :as project.transforms]
   [app.graph.schema :as schema]
   [app.graph.stats :as stats]
   [app.srepl.helpers :as h])
  (:import
   com.ladybugdb.Connection))

(defn- fetch-file!
  [system file-id]
  (let [file-id (h/parse-uuid file-id)
        file    (db/run! system #(bfc/get-file % file-id :realize? true))]
    (when-not file
      (ex/raise :type :not-found
                :code :file-not-found
                :file-id (str file-id)))
    (when-not (:data file)
      (ex/raise :type :validation
                :code :file-without-data
                :hint "file has no data to project"
                :file-id (str file-id)))
    [file-id file]))

(defn ingest-on-connection!
  "Project `file-id` into an already open Ladybug `conn`."
  [system ^Connection conn file-id & {:keys [db-path skip-stats? skip-validation?]
                                      :or   {skip-stats? true}}]
  (let [[file-id file] (fetch-file! system file-id)
        db-path        (or db-path (ladybug/db-path-for-file file-id))
        data           (:data file)]
    (when-not skip-validation?
      (ctf/check-file-data data))
    (l/inf :hint "graph ingest"
           :file-id (str file-id)
           :revn (:revn file)
           :db-path db-path
           :schema schema/schema-version)
    (let [ddl          (schema/ddl-statements)
          {:keys [nodes edges stats]}
          (project.document/projection-data data file)
          staging-path (bulk/staging-dir db-path file-id)]
      (ladybug/exec-on-connection! conn ddl)
      (bulk/load-projection! conn {:nodes nodes :edges edges} staging-path)
      (ladybug/exec-on-connection! conn ["CHECKPOINT;"])
      {:file-id        file-id
       :revn           (:revn file)
       :name           (or (:name data) (:name file))
       :db-path        db-path
       :schema-version schema/schema-version
       :projection     {:stats stats}
       :transforms     (project.transforms/apply-transforms! system db-path data file)
       :stats          (when-not skip-stats?
                         (stats/summarize-connection conn))})))

(defn ingest-file!
  [system file-id & {:keys [db-path reset-db? skip-stats? skip-validation?]
                     :or   {reset-db? true}}]
  (let [db-path (or db-path (ladybug/db-path-for-file (h/parse-uuid file-id)))]
    (when reset-db?
      (ladybug/reset-db-path! db-path))
    (ladybug/with-connection! db-path
      (fn [conn]
        (ingest-on-connection! system conn file-id
                               :db-path db-path
                               :skip-stats? skip-stats?
                               :skip-validation? skip-validation?)))))

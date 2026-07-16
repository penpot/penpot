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
   [app.graph.arrow :as graph.arrow]
   [app.graph.arrow-simple :as arrow-simple]
   [app.graph.bulk :as bulk]
   [app.graph.ladybug :as ladybug]
   [app.graph.project.document :as project.document]
   [app.graph.project.transforms :as project.transforms]
   [app.graph.schema :as schema]
   [app.graph.stats :as stats]
   [app.srepl.helpers :as h])
  (:import
   com.ladybugdb.Connection
   org.apache.arrow.memory.BufferAllocator))

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

(defn- ingest-on-connection*!
  [system ^Connection conn file-id
   {:keys [db-path skip-stats? skip-validation? use-arrow? arrow-alloc]
    :or   {skip-stats? true use-arrow? true}}]
  (let [[file-id file] (fetch-file! system file-id)
        db-path        (or db-path (ladybug/db-path-for-file file-id))
        data           (:data file)]
    (when-not skip-validation?
      (ctf/check-file-data data))
    (l/inf :hint "graph ingest"
           :file-id (str file-id)
           :revn (:revn file)
           :db-path db-path
           :schema schema/schema-version
           :use-arrow? use-arrow?)
    (let [ddl (schema/ddl-statements)
          {:keys [nodes edges stats]}
          (project.document/projection-data data file)]
      (ladybug/exec-on-connection! conn ddl)

      (if use-arrow?
        (do
          (when-not arrow-alloc
            (ex/raise :type :internal
                      :code :arrow-allocator-unavailable
                      :hint "Arrow ingest requires an Arrow allocator"))
          (l/inf :hint "Using simple Arrow-based projection loading")
          (arrow-simple/load-projection-with-arrow-simple!
           conn {:nodes nodes :edges edges} ^BufferAllocator arrow-alloc))
        (let [staging-path (bulk/staging-dir db-path file-id)]
          (l/inf :hint "Using CSV-based projection loading")
          (bulk/load-projection! conn {:nodes nodes :edges edges} staging-path)))

      (ladybug/exec-on-connection! conn ["CHECKPOINT;"])
      {:file-id        file-id
       :revn           (:revn file)
       :name           (or (:name data) (:name file))
       :db-path        db-path
       :schema-version schema/schema-version
       :projection     {:stats stats
                        :nodes nodes
                        :edges edges}
       :transforms     (project.transforms/apply-transforms! system db-path data file)
       :stats          (when-not skip-stats?
                         (stats/summarize-connection conn))})))

(defn ingest-on-connection!
  "Project `file-id` into an already open Ladybug `conn`.

  When `:use-arrow?` is true and no `:arrow-alloc` is supplied, a temporary
  RootAllocator is created for this call and closed afterwards."
  [system ^Connection conn file-id & {:keys [use-arrow? arrow-alloc] :as opts
                                      :or   {use-arrow? true}}]
  (let [opts (cond-> opts
               (nil? (:use-arrow? opts))
               (assoc :use-arrow? true))]
    (if (and use-arrow? (nil? arrow-alloc))
      (graph.arrow/with-allocator!
        (fn [alloc]
          (ingest-on-connection*! system conn file-id
                                  (assoc opts :arrow-alloc alloc))))
      (ingest-on-connection*! system conn file-id opts))))

(defn ingest-file!
  "Ingest a file into a Ladybug database.

  By default the returned `:projection` only keeps `:stats` (not the full
  `:nodes`/`:edges` maps) so REPL/`*1*` does not retain huge projections.
  Pass `:keep-projection? true` when callers need the raw projection.

  Arrow allocators are closed after the Ladybug connection/database so
  staging buffers are not retained for the process lifetime."
  [system file-id & {:keys [db-path reset-db? skip-stats? skip-validation? use-arrow? keep-projection?]
                     :or   {reset-db? true use-arrow? true}}]
  (let [db-path (or db-path (ladybug/db-path-for-file (h/parse-uuid file-id)))
        run     (fn [arrow-alloc]
                  (ladybug/with-connection! db-path
                    (fn [conn]
                      (cond-> (ingest-on-connection*!
                               system conn file-id
                               {:db-path db-path
                                :skip-stats? skip-stats?
                                :skip-validation? skip-validation?
                                :use-arrow? use-arrow?
                                :arrow-alloc arrow-alloc})
                        (not keep-projection?)
                        (update :projection select-keys [:stats])))))]
    (when reset-db?
      (ladybug/reset-db-path! db-path))
    (if use-arrow?
      ;; Allocator outside connection: close after Ladybug drops Arrow tables.
      (graph.arrow/with-allocator! run)
      (run nil))))

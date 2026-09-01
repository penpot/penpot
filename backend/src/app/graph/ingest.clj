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
   [app.graph.ladybug :as ladybug]
   [app.graph.meta :as graph.meta]
   [app.graph.projection.document :as projection.document]
   [app.graph.projection.transforms :as projection.transforms]
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
  [system ^Connection conn file-id ^BufferAllocator allocator
   {:keys [db-path skip-stats? skip-validation?] :or {skip-stats? true}}]
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
    (let [ddl (schema/ddl-statements)
          {:keys [nodes edges stats]}
          (projection.document/projection-data data file)]
      (ladybug/exec-on-connection! conn ddl)
      (graph.arrow/load-projection! conn {:nodes nodes :edges edges} allocator)
      (ladybug/exec-on-connection! conn ["CHECKPOINT;"])
      (let [transforms (projection.transforms/apply-transforms! system conn data file)]
        ;; Written last: its presence doubles as the build-complete marker.
        (graph.meta/write! conn {:file-id file-id
                                 :revn    (:revn file)})
        {:file-id        file-id
         :revn           (:revn file)
         :name           (or (:name data) (:name file))
         :db-path        db-path
         :schema-version schema/schema-version
         :projection     {:stats stats
                          :nodes nodes
                          :edges edges}
         :transforms     transforms
         :stats          (when-not skip-stats?
                           (stats/summarize-connection conn))}))))

(defn ingest-on-connection!
  "Project `file-id` into an already open Ladybug `conn`.

  Takes an `:arrow-alloc` when the caller already owns one; otherwise it makes
  a short-lived allocator around this call. A caller that opened the connection
  itself should pass its own, because the allocator has to be closed *after*
  the connection — see `app.graph.arrow/with-allocator!`."
  [system ^Connection conn file-id & {:keys [arrow-alloc] :as opts}]
  (if arrow-alloc
    (ingest-on-connection*! system conn file-id arrow-alloc opts)
    (graph.arrow/with-allocator!
      (fn [allocator] (ingest-on-connection*! system conn file-id allocator opts)))))

(defn ingest-file!
  [system file-id & {:keys [db-path reset-db? skip-stats? skip-validation?]
                     :or   {reset-db? true}}]
  (let [db-path (or db-path (ladybug/db-path-for-file (h/parse-uuid file-id)))]
    (when reset-db?
      (ladybug/reset-db-path! db-path))
    ;; Allocator outermost: Ladybug holds the staged Arrow buffers until its
    ;; tables are dropped, which is no later than connection close, so the
    ;; allocator must be closed after the connection and the database.
    (graph.arrow/with-allocator!
      (fn [allocator]
        (ladybug/with-connection! db-path
          (fn [conn]
            (ingest-on-connection*! system conn file-id allocator
                                    {:db-path db-path
                                     :skip-stats? skip-stats?
                                     :skip-validation? skip-validation?})))))))

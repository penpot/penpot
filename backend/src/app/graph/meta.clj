;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.meta
  "`GraphMeta`: the graph's own account of who built it and from what.

  A projected graph is a cache of a file at a revision, built by a known
  schema. The row records both, so a reader can decide whether to reuse the
  database or rebuild it: a `schema_version` that no longer matches the
  registry, or a `source_revn` behind the file's, means the cache is stale.

  The row is written *last* in a build, so its presence also marks the build
  complete.

  Keyed by `source_file_id` rather than holding a single row: a closure graph
  is a union of per-file builds, and each contributing file keeps its own
  provenance."
  (:require
   [app.common.time :as ct]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes])
  (:import
   com.ladybugdb.Connection))

(set! *warn-on-reflection* true)

(def table
  "GraphMeta")

(def producer
  "penpot")

(def ddl
  "DDL for the provenance table."
  (str "CREATE NODE TABLE `" table "` ("
       "`source_file_id` UUID, "
       "`producer` STRING, "
       "`producer_version` STRING, "
       "`schema_version` STRING, "
       "`source_revn` INT64, "
       "`built_at` TIMESTAMP, "
       "PRIMARY KEY (`source_file_id`));"))

(defn write!
  "Record what this build produced for `file-id`."
  [^Connection conn {:keys [file-id revn]}]
  (ladybug/exec-on-connection! conn [ddl])
  (ladybug/exec-on-connection!
   conn
   [(str "MERGE (m:`" table "` {source_file_id: " (ladybug/format-uuid file-id) "}) "
         "SET m.producer = " (ladybug/format-string producer) ", "
         "m.producer_version = " (ladybug/format-string (or (System/getenv "PENPOT_BUILD") "devenv")) ", "
         "m.schema_version = " (ladybug/format-string nodes/schema-version) ", "
         "m.source_revn = " (ladybug/format-int (or revn 0)) ", "
         "m.built_at = " (ladybug/format-timestamp (ct/now)) ";")]))


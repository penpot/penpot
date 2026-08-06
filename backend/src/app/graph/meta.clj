;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.meta
  "`GraphMeta`: the graph's own account of who built it, from what, and what
  it did.

  A projected graph is a cache of a file at a revision, built by a known
  schema. The row records both, so a reader can decide whether to reuse the
  database or rebuild it: a `schema_version` that no longer matches the
  registry, or a `source_revn` behind the file's, means the cache is stale.

  Ingestion is a partial port, so a graph can arrive with any subset of the
  pipeline applied. `transforms` names what this build did, and the parity
  consumer computes the complement and runs only that in Python. The ids cross
  a language boundary as data, so they are kebab-case strings rather than
  keywords and must stay byte-identical to the consumer's own list.

  The row is written *last* in a build, so its presence also marks the build
  complete.

  Keyed by `source_file_id` rather than holding a single row: a closure graph
  is a union of per-file builds, and each contributing file keeps its own
  provenance."
  (:require
   [app.common.time :as ct]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes]
   [clojure.string :as str])
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
       "`transforms` STRING[], "
       "`built_at` TIMESTAMP, "
       "PRIMARY KEY (`source_file_id`));"))

(def projection-transforms
  "Transform ids this backend satisfies while *projecting*, before any
  transform pass runs.

  A reader cares whether the result is in the graph, not how it got there. The
  consumer derives `IsChildOf` from persisted `shapes` arrays in a later pass;
  `app.graph.project.document/project-shape-ids` emits the edges during the
  tree walk. Same id, same observable graph. The two denormalizations are the
  same case."
  ["add-document"
   "link-contained-shapes"
   "denormalize-page-id"
   "denormalize-component-id"])

(defn- format-transforms
  [ids]
  (str "[" (->> (sort (distinct ids))
                (map ladybug/format-string)
                (str/join ", "))
       "]"))

(defn write!
  "Record what this build produced for `file-id`.

  `transform-ids` are the ids applied *on top of* `projection-transforms`, so
  a caller only names what its transform pass did."
  [^Connection conn {:keys [file-id revn transform-ids]}]
  (ladybug/exec-on-connection! conn [ddl])
  (ladybug/exec-on-connection!
   conn
   [(str "MERGE (m:`" table "` {source_file_id: " (ladybug/format-uuid file-id) "}) "
         "SET m.producer = " (ladybug/format-string producer) ", "
         "m.producer_version = " (ladybug/format-string (or (System/getenv "PENPOT_BUILD") "devenv")) ", "
         "m.schema_version = " (ladybug/format-string nodes/schema-version) ", "
         "m.source_revn = " (ladybug/format-int (or revn 0)) ", "
         "m.transforms = " (format-transforms (concat projection-transforms transform-ids)) ", "
         "m.built_at = " (ladybug/format-timestamp (ct/now)) ";")]))


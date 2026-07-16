;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema
  "Ladybug DDL facade for the graph-backed Penpot vertical slice.

  Node metadata and DDL generation live in `app.graph.schema.nodes`."
  (:require
   [app.graph.schema.nodes :as nodes]))

(def schema-version
  nodes/schema-version)

(def container-node-tables
  nodes/container-tables)

(def shape-node-tables
  nodes/shape-tables)

(def node-tables
  (mapv (fn [{:keys [table schema]}]
          {:name table :schema schema})
        nodes/node-types))

(defn ddl-statements
  []
  (nodes/ddl-statements))

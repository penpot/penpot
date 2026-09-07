;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.stats
  (:require
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.nodes :as nodes]))

(defn- count-on-connection
  [conn statement]
  (or (ladybug/query-scalar-on-connection! conn statement) 0))

(defn- rel-table-names
  "Relationship tables present in the open database.

  Read from the catalog so a newly ported transform's edges are counted
  without this namespace being told about it."
  [conn]
  (->> (ladybug/query-on-connection!
        conn "CALL show_tables() WHERE type = 'REL' RETURN name;" :max-rows 1000)
       :rows
       (map first)))

(defn summarize-connection
  "Return node/edge counts using an open Ladybug connection."
  [conn]
  {:nodes (into {}
                (map (fn [table]
                       [table (count-on-connection
                               conn
                               (str "MATCH (n:" (nodes/match-label table) ") "
                                    "RETURN count(n) AS " table "_c;"))])
                     (map :table nodes/node-types)))
   :edges (into {}
                (map (fn [rel]
                       [(keyword rel)
                        (count-on-connection
                         conn
                         (str "MATCH ()-[e:`" rel "`]->() RETURN count(e) AS c;"))]))
                (rel-table-names conn))})

(defn summarize
  "Return node/edge counts from the graph database."
  [db-path]
  (ladybug/with-connection! db-path summarize-connection))

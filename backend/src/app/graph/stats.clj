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
   :edges {:IsChildOf (count-on-connection
                       conn
                       "MATCH ()-[e:IsChildOf]->() RETURN count(e) AS IsChildOf_c;")
           :IsInstanceOf (count-on-connection
                          conn
                          "MATCH ()-[e:IsInstanceOf]->() RETURN count(e) AS IsInstanceOf_c;")}})

(defn summarize
  "Return node/edge counts from the graph database."
  [db-path]
  (ladybug/with-connection! db-path summarize-connection))

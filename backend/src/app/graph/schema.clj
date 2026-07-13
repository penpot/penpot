;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema
  "Ladybug DDL for the graph-backed Penpot vertical slice.

  Table names follow beadpot conventions. This is an intentionally small
  subset of the full schema and will grow incrementally."
  (:require
   [clojure.string :as str]))

(def schema-version
  "penpot-graph-slice-1")

(def node-tables
  [{:name "Document"
    :columns [["id" "UUID"]
              ["name" "STRING"]
              ["version" "INT64"]
              ["revision" "INT64"]]}
   {:name "Page"
    :columns [["id" "UUID"]
              ["name" "STRING"]
              ["index" "INT64"]]}
   {:name "Frame"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Rectangle"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Group"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Circle"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Path"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Text"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Boolean"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "Image"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}
   {:name "SVGRaw"
    :columns [["id" "UUID"]
              ["name" "STRING"]]}])

(def shape-node-tables
  (mapv :name (drop 2 node-tables)))

(defn- create-node-table-ddl
  [{:keys [name columns]}]
  (let [cols (str/join ", "
                       (concat
                        (map (fn [[col type]]
                               (str "`" col "` " type))
                             columns)
                        ["PRIMARY KEY (`id`)"]))]
    (str "CREATE NODE TABLE `" name "` (" cols ");")))

(def is-child-of-ddl
  (str "CREATE REL TABLE `IsChildOf` ("
       "FROM `Page` TO `Document`, "
       (str/join ", "
                 (map (fn [shape]
                        (str "FROM `" shape "` TO `Page`"))
                      shape-node-tables))
       ", `position` INT64);"))

(defn ddl-statements
  []
  (conj (vec (map create-node-table-ddl node-tables))
        is-child-of-ddl))

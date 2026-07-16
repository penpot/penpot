;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.nodes
  "Single source of truth for graph node tables.

  Each registry entry declares Penpot Malli sources plus projection
  options (`:drop`, optional `:extra`). Derived artifacts — Ladybug
  DDL, CSV columns, validation, type dispatch — all flow from that."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.graph.schema.projection :as projection]
   [app.graph.schema.types :as types]
   [clojure.string :as str]))

(def schema-version
  "penpot-graph-slice-3")

;; beadpot/graph/schemas.py drop_fields
(def ^:private document-projection
  {:source ctf/schema:file
   :drop   [:data]})

(def ^:private page-projection
  {:source ctp/schema:page
   :drop   [:objects]})

(def ^:private component-projection
  {:source ctk/schema:component
   :drop   [:objects]
   ;; Soft-delete flag used at runtime; not in schema:component.
   :extra  [:map
            [:deleted {:optional true} :boolean]
            [:annotation {:optional true} :string]]})

(def ^:private shape-projection
  {:drop [:type]})

(def ^:private shape-node-types
  [{:table "Frame"     :penpot-type :frame   :container? true}
   {:table "Group"     :penpot-type :group   :container? true}
   {:table "Boolean"   :penpot-type :bool    :container? true}
   {:table "SVGRaw"    :penpot-type :svg-raw :container? true}
   {:table "Rectangle" :penpot-type :rect}
   {:table "Circle"    :penpot-type :circle}
   {:table "Path"      :penpot-type :path}
   {:table "Text"      :penpot-type :text}
   {:table "Image"     :penpot-type :image}])

(defn- resolve-schema
  [{:keys [schema source drop extra penpot-type]}]
  (or schema
      (when penpot-type
        (projection/project-shape-schema penpot-type
                                         {:drop  drop
                                          :extra extra}))
      (projection/project-schema source
                                 {:drop  drop
                                  :extra extra})))

(defn- shape-node-entry
  [{:keys [table penpot-type container?] :as entry}]
  (let [projection (-> shape-projection
                       (merge (:projection entry))
                       (assoc :penpot-type penpot-type))]
    {:table       table
     :pk          :id
     :penpot-type penpot-type
     :container?  container?
     :projection  projection
     :schema      (resolve-schema projection)}))

(def node-types
  "Ordered node registry."
  (into [{:table "Document"
          :pk    :id
          :projection document-projection
          :schema (resolve-schema document-projection)}
         {:table "Page"
          :pk    :id
          :projection page-projection
          :schema (resolve-schema page-projection)}
         {:table "Component"
          :pk    :id
          :projection component-projection
          :schema (resolve-schema component-projection)}]
        (map shape-node-entry shape-node-types)))

(def ^:private by-table
  (into {} (map (juxt :table identity) node-types)))

(def ^:private by-penpot-type
  (into {} (keep (fn [{:keys [penpot-type table]}]
                   (when penpot-type [penpot-type table]))
                 node-types)))

(def container-tables
  (into #{} (comp (filter :container?) (map :table)) node-types))

(def shape-tables
  (into [] (comp (filter :penpot-type) (map :table)) node-types))

(defn table-for-type
  "Map a Penpot shape `:type` keyword to a Ladybug node table name."
  [penpot-type]
  (get by-penpot-type (keyword penpot-type)))

(defn node-entry
  [table]
  (get by-table table))

(defn projection-for
  "Return the projection options map for `table`."
  [table]
  (:projection (node-entry table)))

(defn- entry-child-schema
  "Return the value schema from a Malli map entry (`[k s]` or `[k props s]`)."
  [entry]
  (if (> (count entry) 2)
    (nth entry 2)
    (nth entry 1)))

(defn column-ladybug-type
  "Ladybug column type for projected key `k` on `table`."
  [table k]
  (some (fn [entry]
          (when (= k (first entry))
            (types/ladybug-type (entry-child-schema entry))))
        (projection/schema-map-entries (:schema (node-entry table)))))

(defn column-keys
  "Projected column keys for `table`, in registry order."
  [table]
  (mapv first (projection/schema-map-entries (:schema (node-entry table)))))

(defn columns
  "Projected column names for `table`, in registry order."
  [table]
  (mapv name (column-keys table)))

(def ^:private validate-node-fn
  (memoize
   (fn [table]
     (let [{:keys [schema]} (node-entry table)]
       (sm/check-fn schema
                    :type :validation
                    :code (keyword "graph-node-projection" (str/lower-case table))
                    :hint (str "invalid graph node projection for " table))))))

(defn- projection-error-hint
  [table explain]
  (str "invalid graph node projection for " table
       (when explain
         (str "\n" (sm/humanize-explain explain)))))

(defn validate-node
  "Validate and return projected node attrs for `table`."
  [table value]
  (let [{:keys [schema]} (node-entry table)]
    (try
      ((validate-node-fn table) value)
      (catch clojure.lang.ExceptionInfo e
        (let [data    (ex-data e)
              explain (or (::sm/explain data)
                          (sm/explain schema value))]
          (ex/raise :type :validation
                    :code (keyword "graph-node-projection" (str/lower-case table))
                    :hint (projection-error-hint table explain)
                    :table table
                    ::sm/explain explain
                    :cause e))))))

(defn- get-projected-attr
  [attrs k]
  (or (get attrs k)
      (when (keyword? k) (get attrs (name k)))))

(defn- raise-empty-projection!
  [table attrs]
  (ex/raise :type :validation
            :code (keyword "graph-node-projection" (str/lower-case table))
            :hint (str "empty graph node projection for " table
                       "; columns=" (count (column-keys table))
                       " shape-keys=" (vec (keys attrs)))))

(defn project-attrs
  "Select and validate the projected columns for `table` from `attrs`."
  [table attrs]
  (let [projected (into {}
                        (keep (fn [k]
                                (when-let [v (get-projected-attr attrs k)]
                                  [k v]))
                              (column-keys table)))]
    (when (empty? projected)
      (raise-empty-projection! table attrs))
    (validate-node table projected)))

(defn match-label
  "Cypher node label for MATCH; backtick-wrapped when required by Ladybug."
  [table]
  (if (#{"Group" "Boolean"} table)
    (str "`" table "`")
    table))

(defn cypher-property-key
  "Backtick-wrapped property key for inline Cypher literals."
  [k]
  (str "`" (name k) "`"))

(defn- create-node-table-ddl
  [{:keys [table pk schema]}]
  (let [cols (for [entry (projection/schema-map-entries schema)
                   :let [k     (first entry)
                         child (entry-child-schema entry)]]
               (str "`" (name k) "` " (types/ladybug-type child)))]
    (str "CREATE NODE TABLE `" table "` ("
         (str/join ", " (concat cols [(str "PRIMARY KEY (`" (name pk) "`)")]))
         ");")))

(defn is-child-of-ddl
  []
  (str "CREATE REL TABLE `IsChildOf` ("
       "FROM `Page` TO `Document`, "
       "FROM `Component` TO `Document`, "
       (str/join ", "
                 (concat
                  (map (fn [shape]
                         (str "FROM `" shape "` TO `Page`"))
                       shape-tables)
                  (for [shape shape-tables
                        container container-tables]
                    (str "FROM `" shape "` TO `" container "`"))))
       ", `position` INT64);"))

(defn is-instance-of-ddl
  "Frame instance heads → Component (beadpot `IsInstanceOf`)."
  []
  "CREATE REL TABLE `IsInstanceOf` (FROM `Frame` TO `Component`);")

(defn ddl-statements
  []
  (conj (mapv create-node-table-ddl node-types)
        (is-child-of-ddl)
        (is-instance-of-ddl)))
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.nodes
  "Single source of truth for graph node tables.

  Each registry entry declares Penpot Malli sources plus projection
  options (`:drop`, optional `:extra`). Derived artifacts — Ladybug
  DDL, Arrow fields, validation, type dispatch — all flow from that.

  This registry is the single source of the graph schema. A Ladybug column
  gets its name and its type once, at table creation, and there is no
  widening afterwards. Every divergence between a Penpot key and its column
  is recorded in `app.graph.schema.contract`."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.graph.ladybug :as ladybug]
   [app.graph.schema.contract :as contract]
   [app.graph.schema.projection :as projection]
   [app.graph.schema.types :as types]
   [clojure.string :as str]))

(def schema-version
  "penpot-graph-slice-4")

(def ^:private document-projection
  {:source ctf/schema:file
   :drop   [:data]
   ;; Attributes a file map carries that `ctf/schema:file` does not declare.
   ;;
   ;; They belong here rather than in that schema, even though the graph wants
   ;; them, because `schema:file` is on the *write* path too:
   ;; `app.binfile.common/update-file!` derives its UPDATE columns from a file
   ;; map's keys, so declaring `:backend` there made it try to write a `backend`
   ;; column, which the `file` table does not have — it is synthesized on read.
   ;; A projection `:extra` is local to the graph and cannot reach a write.
   ;;
   ;; `:options` is lifted out of `:data` before the blob is dropped
   ;; (`app.graph.projection.document/document-attrs`); the rest come off the file
   ;; map as `get-file` returns it.
   :extra  [:map
            [:options {:optional true} [:maybe :map]]
            [:backend {:optional true} [:maybe :string]]
            [:comment-thread-seqn {:optional true} [:maybe :int]]
            [:ignore-sync-until {:optional true} [:maybe ::ct/inst]]]})

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

(defn column-name
  "Graph column name for projected key `k` on `table`."
  [_table k]
  (contract/column-name k))

(defn column-ladybug-type
  "Ladybug column type for projected key `k` on `table`."
  [table k]
  (some (fn [entry]
          (when (= k (first entry))
            (contract/ladybug-type (column-name table k)
                                   (types/ladybug-type (entry-child-schema entry)))))
        (projection/schema-map-entries (:schema (node-entry table)))))

(defn column-keys
  "Projected column keys for `table`, in registry order.

  Keys the contract drops on this table are omitted, so the column order, the
  Arrow batch, and the DDL cannot disagree about what exists."
  [table]
  (into []
        (comp (map first)
              (remove #(contract/drop-key? table %)))
        (projection/schema-map-entries (:schema (node-entry table)))))

(defn columns
  "Projected column names for `table`, in registry order."
  [table]
  (mapv #(column-name table %) (column-keys table)))

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
  "The attribute under `k`, keyword or string key.

  `if-some`, not `or`: `false` and `0` are values, and falling through on them
  is how `opacity 0` became `nil` and then the column default."
  [attrs k]
  (if-some [v (get attrs k)]
    v
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
  ;; `some?`, not truthiness: `false` and `0` are values. Dropping them sent
  ;; `opacity 0` to the column default of 1.0 — a fully transparent shape
  ;; projected as opaque.
  (let [projected (into {}
                        (keep (fn [k]
                                (let [v (get-projected-attr attrs k)]
                                  (when (some? v) [k v])))
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
  "Backtick-wrapped column name for inline Cypher literals."
  [table k]
  (str "`" (column-name table k) "`"))

(defn column-map-key-fn
  "How a MAP column of `table` renders its keys.

  A MAP's keys are values, not schema, so they keep the spelling their consumer
  parsed — `applied_tokens` is keyed in camelCase. Both writers need this, so it
  lives next to the column's type rather than in either of them."
  [table k]
  (contract/map-key-fn (column-name table k)))

(defn format-column-value
  "Cypher literal for `v` in column `k` of `table`.

  The single place that knows both the column's Ladybug type and the contract
  detail that a MAP column may render its keys differently from `name` — used
  by the bulk loader's post-COPY fixups and by the incremental sync alike, so
  the two cannot disagree about a value's shape."
  [table k v]
  (ladybug/format-typed-value (column-ladybug-type table k)
                              v
                              (column-map-key-fn table k)))

(defn- create-node-table-ddl
  [{:keys [table pk]}]
  (let [cols (for [k (column-keys table)]
               (str "`" (column-name table k) "` " (column-ladybug-type table k)))]
    (str "CREATE NODE TABLE `" table "` ("
         (str/join ", " (concat cols
                                [(str "PRIMARY KEY (`" (column-name table pk) "`)")]))
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
  "Frame instance heads → Component."
  []
  "CREATE REL TABLE `IsInstanceOf` (FROM `Frame` TO `Component`);")

(defn- shape-to-shape-rel-ddl
  "A rel table over the full shape × shape product.

  Created up-front rather than on demand: the bulk loader must never race on
  lazy table creation, and a consumer can then tell \"this producer cannot
  emit that pair\" from \"this document happens to have none\"."
  [rel props]
  (str "CREATE REL TABLE `" rel "` ("
       (str/join ", " (for [from shape-tables
                            to   shape-tables]
                        (str "FROM `" from "` TO `" to "`")))
       (when (seq props) (str ", " (str/join ", " props)))
       ");"))

(defn refers-to-ddl
  "Instance shape → its homologue in the component main instance, resolved
  from `shape-ref`."
  []
  (shape-to-shape-rel-ddl "RefersTo" nil))

(defn fills-swap-slot-ddl
  "Swapped-in shape → the slot shape it replaces."
  []
  (shape-to-shape-rel-ddl "FillsSwapSlot" ["`slot_id` UUID"]))

(defn ddl-statements
  []
  (-> (mapv create-node-table-ddl node-types)
      (conj (is-child-of-ddl))
      (conj (is-instance-of-ddl))
      (conj (refers-to-ddl))
      (conj (fills-swap-slot-ddl))))
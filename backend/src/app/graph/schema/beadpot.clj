;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.beadpot
  "beadpot's graph schema, as a checked-in value.

  `resources/app/graph/beadpot-schema.json` is produced by
  `bp graph schema export` and read back off an empty database beadpot itself
  creates, so it states what beadpot *does*: table names, column names in
  order, Ladybug types, the legal (from, to) pairs of each relationship family,
  and each column's default.

  This namespace does not generate the DDL — that stays derived from Penpot's
  own Malli schemas (`app.graph.schema.nodes`), so the backend remains
  self-contained and a change to the design model shows up here as a diff to
  review rather than as a silent schema change. What the manifest *is* used for
  is the things Penpot cannot know on its own:

  - **defaults.** beadpot writes a Pydantic field default where the file has no
    such attribute, so a shape with no `blocked` key holds `false`, not NULL.
    A consumer told NULL sees a missing feature where beadpot showed it a false
    one. The defaults live in beadpot's models; reading them from here keeps
    them from being restated in Clojure and drifting.
  - **review.** `backend_tests.graph-contract-test` diffs the DDL this backend
    emits against the manifest, so a divergence is a failing test naming the
    column, not something a training set discovers later.

  Regenerate with `bp graph schema export -o
  backend/resources/app/graph/beadpot-schema.json` after any change to
  beadpot's node or edge models."
  (:require
   [app.common.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private resource-path
  "app/graph/beadpot-schema.json")

(defn- read-manifest
  []
  (if-let [resource (io/resource resource-path)]
    (with-open [reader (io/reader resource)]
      (json/read reader :key-fn keyword))
    (throw (ex-info "beadpot schema manifest missing from resources"
                    {:path resource-path}))))

(def manifest
  "The parsed manifest. Delayed so a missing resource fails where it is used."
  (delay (read-manifest)))

(defn- index-tables
  [tables]
  (into {} (map (juxt :table identity)) tables))

(def node-tables
  (delay (index-tables (:node_tables @manifest))))

(def rel-tables
  (delay (index-tables (:rel_tables @manifest))))

(defn table
  "The manifest entry for `table-name`, node or rel."
  [table-name]
  (or (get @node-tables table-name)
      (get @rel-tables table-name)))

(defn columns
  "Column entries for `table-name`, in beadpot's order."
  [table-name]
  (:columns (table table-name) []))

(defn column
  "The manifest entry for one column, or nil."
  [table-name column-name]
  (some #(when (= column-name (:name %)) %) (columns table-name)))

(def defaults
  "`table -> {column-name -> default}`, omitting columns with no default.

  A JSON `null` means \"no default\": beadpot leaves the column NULL when the
  attribute is unset, and so should we."
  (delay
    (into {}
          (map (fn [[table-name entry]]
                 [table-name
                  (into {}
                        (keep (fn [{:keys [name default]}]
                                (when (some? default) [name default])))
                        (:columns entry))]))
          (merge @node-tables @rel-tables))))

(defn column-default
  "beadpot's default for `column-name` on `table-name`, or nil."
  [table-name column-name]
  (get-in @defaults [table-name column-name]))

(def ^:private type-aliases
  "Declared type spelling → the spelling Ladybug's catalog reports.

  The manifest is read back off a real database, so it holds catalog spellings;
  DDL is written in whatever Ladybug accepts. `BOOLEAN` is accepted and comes
  back as `BOOL`, so a literal comparison would decide the two sides disagree
  about every boolean column."
  {"BOOLEAN" "BOOL"})

(defn normalize-type
  "A Ladybug type string in the spelling the catalog uses.

  Replaces aliases anywhere in the string, so a `BOOLEAN` nested inside a
  `STRUCT(...)` normalizes too."
  [ladybug-type]
  (when ladybug-type
    (-> (reduce-kv (fn [s declared reported] (str/replace s declared reported))
                   ladybug-type
                   type-aliases)
        ;; STRUCT field names are written backticked (a grid cell has a field
        ;; called `column`); the catalog reports them bare.
        (str/replace "`" ""))))

(defn typed-column-default
  "beadpot's default for a column, but only when the types agree.

  A default is expressed in the column's type — `[]` for a `DOUBLE[2][]`,
  `{}` for a `JSON`, `[1,0,0,1,0,0]` for a `DOUBLE[6]` transform. Where this
  backend still emits a different type for that column (`app.graph.schema.types`
  is coarser than beadpot's encodings in a handful of places, tracked by
  `bp graph schema diff`), the default would be written in a shape the column
  cannot hold. So it is withheld until the types match, and the set of columns
  that get defaults grows as the types converge."
  [table-name column-name ladybug-type]
  (when (= (normalize-type ladybug-type)
           (normalize-type (:type (column table-name column-name))))
    (column-default table-name column-name)))

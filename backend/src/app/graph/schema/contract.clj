;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.contract
  "The graph-schema contract shared with beadpot.

  Penpot is authoritative on the *design model*: what a shape is, what a
  component means, which attributes exist. beadpot is authoritative on the
  *graph schema*: the table and column names, and the Ladybug types, that
  downstream consumers (ML graph mappings, featurization) read. A graph this
  backend writes must therefore be indistinguishable, to those consumers, from
  one beadpot's Python pipeline writes.

  Everything that could drift between the two lives here, as data:

  - `column-name` maps a Penpot key to its beadpot column. The rule is
    snake_case of the key; `renames` records every exception.
  - `dropped-keys` names Penpot keys that deliberately have no column.
  - `type-overrides` pins the Ladybug type where the Malli-derived one
    (`app.graph.schema.types`) differs from beadpot's and the difference is
    load-bearing for a consumer.

  Each entry carries its reason. A new divergence must be added here, which is
  the point: `backend_tests.graph_contract_test` walks the checked-in beadpot
  manifest (`resources/app/graph/beadpot-schema.json`, produced by
  `bp graph schema export`) and fails on anything this namespace does not
  account for. Schema drift becomes a failing test with a precise message
  instead of a silently renamed column in a training set."
  (:require
   [app.common.json :as json]
   [clojure.string :as str]))

(def ^:private renames
  "Penpot key → beadpot column name, where the two differ.

  Keyed by the Penpot key alone: no shape type gives one of these a second
  meaning, so a per-table map would only add ceremony."
  {;; beadpot names the discriminant after the table (`BooleanNode`), not
   ;; after Penpot's `:bool` shape type.
   :bool-type      "boolean_type"

   ;; beadpot keeps the wire name `component-root` out of the graph because
   ;; the column records what the *file* saved, which can lag what the shape
   ;; tree implies — `saved_` marks it as the stored value, not a derivation.
   :component-root "saved_component_root"

   ;; Penpot stores a list under a singular key; beadpot pluralizes it.
   :shadow         "shadows"

   ;; beadpot spells out the revision number.
   :revn           "revision"})

(def dropped-keys
  "Penpot keys projected by the Malli registry that get no beadpot column.

  Dropping is the right call only when the column would be dead weight
  downstream; anything a consumer might learn from belongs in beadpot instead
  (see `pending-beadpot-columns`)."
  {:deleted-at
   "Only non-nil for a soft-deleted file, and a deleted file is never ingested."

   :pixel-grid-color
   "Viewer chrome: the color of the editor's pixel grid, not design content."

   :pixel-grid-opacity
   "Viewer chrome, as above."})

(def pending-beadpot-columns
  "Penpot keys that *should* become beadpot columns but do not exist there yet.

  Distinct from `dropped-keys` on purpose: these are a debt beadpot owes,
  not a decision to discard data. The contract test reports them separately so
  a new upstream attribute cannot be quietly buried in the drop list."
  {:background-blur
   "Landed upstream behind a default-on flag; beadpot has no field for it yet."})

(def ^:private per-table-dropped
  "Keys dropped only on certain tables.

  `:grids` is the standing case: Penpot's shape schema admits it on every
  shape, but only a Frame ever carries one, and beadpot models it on Frame
  alone. Emitting an always-null column on ten other tables would widen every
  multi-table scan for nothing."
  {:grids #{"Boolean" "Circle" "Group" "Image" "Path" "Rectangle" "SVGRaw" "Text"}})

(def type-overrides
  "Ladybug column type per beadpot column name, where beadpot's differs.

  `app.graph.schema.types` derives a type from the Malli schema, which is the
  right default but coarser than beadpot in places: a Malli `:map` becomes
  `JSON`, where beadpot may use a native Ladybug MAP or a fixed-size array
  that a consumer can read as a tensor without parsing.

  Only load-bearing divergences are pinned here, in the order they became
  load-bearing; the rest are reported by the contract test and closed by
  moving the whole DDL onto the beadpot manifest."
  {;; `LinkAppliedTokens` (beadpot) reads this with `map_keys` /
   ;; `map_extract`; as JSON the transform cannot run at all.
   "applied_tokens" "MAP(STRING, STRING)"

   ;; `grc/schema:rect` is an inline `:and` over a map, not the registered
   ;; `::grc/rect`, so `app.graph.schema.types` cannot recognize it by type.
   ;; Four doubles rather than the eight-field struct: `x1`/`y1`/`x2`/`y2` are
   ;; derivable from `x`/`y`/`width`/`height`, and a fixed-size array is a
   ;; tensor row a consumer reads without parsing.
   "selrect"     "DOUBLE[4]"
   "svg_viewbox" "DOUBLE[4]"

   ;; `:fills` is an `:or` — the packed `app.common.types.fills` value or a
   ;; plain vector of fill maps — so the schema alone cannot say it is a
   ;; collection. It always is one, and a fill has enough optional shape
   ;; (solid, gradient, image) that JSON per element is the honest element type.
   "fills" "JSON[]"})

(def ^:private map-key-fns
  "How to render the *keys* of a MAP column, per column.

  Column names are snake_case because they are graph schema; the keys inside a
  MAP are not — they are values, and beadpot models them as whatever it parsed
  from the wire. `applied_tokens` is keyed by shape attribute in the camelCase
  form Penpot's own JSON encoder produces (`app.common.json/write-camel-key`),
  which is what beadpot's `AppliedTokenKey` holds and what
  `UsesToken.for_property` therefore carries: `strokeWidth`, not
  `stroke-width`."
  {"applied_tokens" json/write-camel-key})

(defn map-key-fn
  "Key renderer for a MAP column; `name` unless the column says otherwise."
  [column]
  (get map-key-fns column name))

(defn column-name
  "The beadpot column name for Penpot key `k`.

  Default: snake_case of the key. `renames` overrides."
  [k]
  (or (get renames k)
      (str/replace (name k) "-" "_")))

(defn drop-key?
  "Should key `k` be omitted from `table`'s columns?"
  [table k]
  (or (contains? dropped-keys k)
      (contains? (get per-table-dropped k #{}) table)))

(defn ladybug-type
  "beadpot's Ladybug type for `column`, or `fallback` when it agrees."
  [column fallback]
  (get type-overrides column fallback))

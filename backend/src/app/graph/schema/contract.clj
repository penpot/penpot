;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.contract
  "Deliberate choices in Penpot's graph schema, recorded as data.

  Penpot must pick a spelling and a type for every graph column. A Ladybug
  column gets both once, at table creation, and neither widens afterwards. The
  choices are therefore worth making deliberately and worth recording.

  Three of them live here:

  - `column-name` maps a Penpot key to its column. The rule is snake_case of
    the key, and `renames` records every exception.
  - `dropped-keys` and `per-table-dropped` name Penpot keys that deliberately
    get no column.
  - `type-overrides` pins the Ladybug type where the Malli-derived one
    (`app.graph.schema.types`) is coarser than the column deserves.

  Each entry carries its reason. A divergence from the default rule is then a
  diff to review rather than a silent rename."
  (:require
   [app.common.json :as json]
   [clojure.string :as str]))

(def ^:private renames
  "Penpot key to column name, where the column is not snake_case of the key.

  Keyed by the Penpot key alone: no shape type gives one of these a second
  meaning, so a per-table map would only add ceremony."
  {;; `bool` collides with the Ladybug type name, so the column is named after
   ;; the table (`Boolean`) rather than after Penpot's `:bool` shape type.
   :bool-type      "boolean_type"

   ;; The column records what the file saved, which can lag what the shape
   ;; tree implies. The `saved_` prefix marks it as the stored value rather
   ;; than a derivation.
   :component-root "saved_component_root"

   ;; The value is a list, so the plural is accurate.
   :shadow         "shadows"

   ;; The column spells the revision number out.
   :revn           "revision"})

(def dropped-keys
  "Penpot keys projected by the Malli registry that get no column.

  Dropping is right only when the column would be dead weight for every reader
  of the graph. A key a reader might learn from belongs in `unprojected-keys`
  instead."
  {:deleted-at
   "Only non-nil for a soft-deleted file, and a deleted file is never ingested."

   :pixel-grid-color
   "Viewer chrome: the color of the editor's pixel grid, not design content."

   :pixel-grid-opacity
   "Viewer chrome, as above."})

(def unprojected-keys
  "Penpot keys that should become graph columns and do not have one yet.

  Distinct from `dropped-keys` on purpose: these are a debt the projection
  owes, not a decision to discard data. Keeping the two apart means a new
  upstream attribute cannot be quietly buried in the drop list."
  {:background-blur
   "Landed upstream behind a default-on flag. No column for it yet."})

(def ^:private per-table-dropped
  "Keys dropped only on certain tables.

  `:grids` is the standing case: Penpot's shape schema admits it on every
  shape, but only a Frame ever carries one. Emitting an always-null column on
  ten other tables would widen every multi-table scan for nothing."
  {:grids #{"Boolean" "Circle" "Group" "Image" "Path" "Rectangle" "SVGRaw" "Text"}})

(def type-overrides
  "Ladybug column type per column name, where the derived type is too coarse.

  `app.graph.schema.types` derives a type from the Malli schema, which is the
  right default but coarser than the column deserves in places: a Malli `:map`
  becomes `JSON`, where a native Ladybug MAP or a fixed-size array lets a
  consumer read a tensor row without parsing.

  Only load-bearing divergences are pinned here, in the order they became
  load-bearing."
  {;; Must be a native MAP: a JSON blob cannot be indexed by key in Cypher, so
   ;; `map_keys` and `map_extract` cannot reach a single token at all.
   "applied_tokens" "MAP(STRING, STRING)"

   ;; `grc/schema:rect` is an inline `:and` over a map, not the registered
   ;; `::grc/rect`, so `app.graph.schema.types` cannot recognize it by type.
   ;; Four doubles rather than the eight-field struct: `x1`/`y1`/`x2`/`y2` are
   ;; derivable from `x`/`y`/`width`/`height`, and a fixed-size array is a
   ;; tensor row a consumer reads without parsing.
   "selrect"     "DOUBLE[4]"

   ;; The SVG provenance attributes are typed `:map` in the shape schema on
   ;; purpose. Legacy files hold them as plain maps rather than as
   ;; `::grc/rect` and `::gmt/matrix` records, and a tighter *schema* would
   ;; reject those files
   ;; (`app.common.types.shape/schema:shape-generic-attrs`). A tighter
   ;; *column* is free: `app.graph.schema.values/coerce` reads either form.
   "svg_viewbox"   "DOUBLE[4]"
   "svg_transform" "DOUBLE[6]"

   ;; `:fills` is an `:or` over the packed `app.common.types.fills` value and
   ;; a plain vector of fill maps, so the schema alone cannot say it is a
   ;; collection. It always is one, and a fill has enough optional shape
   ;; (solid, gradient, image) that JSON per element is the honest element
   ;; type.
   "fills" "JSON[]"})

(def ^:private map-key-fns
  "How to render the *keys* of a MAP column, per column.

  A column name is schema, so it is snake_case. The keys inside a MAP are
  values, so they keep the spelling their producer used. `applied_tokens` is
  keyed by shape attribute in the camelCase form
  `app.common.json/write-camel-key` produces: `strokeWidth`, not
  `stroke-width`."
  {"applied_tokens" json/write-camel-key})

(defn map-key-fn
  "Key renderer for a MAP column. `name` unless the column says otherwise."
  [column]
  (get map-key-fns column name))

(defn column-name
  "The graph column name for Penpot key `k`.

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
  "The pinned Ladybug type for `column`, or `fallback` when nothing is pinned."
  [column fallback]
  (get type-overrides column fallback))

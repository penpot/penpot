;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.projection
  "Derive Ladybug node column schemas from Penpot Malli sources.

  Same model as beadpot's `drop_fields`: start from the canonical schema
  and remove keys that must not become graph columns."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.types.shape :as cts]
   [malli.core :as m]))

(def ^:private malli-opts sm/default-options)

(defn- coerce-schema
  "Normalize Malli sources to a compiled schema, unwrapping `:val` nodes."
  [schema]
  (loop [s (cond
             (sm/schema? schema) schema
             :else (sm/schema schema))]
    (if (= :malli.core/val (sm/type s))
      (recur (first (sm/children s)))
      s)))

(defn- unsupported-projection-schema!
  [schema]
  (ex/raise :type :internal
            :code :unsupported-projection-schema
            :hint (str "unsupported projection schema type: "
                       (sm/type (coerce-schema schema)))))

(defn schema-map-entries
  "Map entries for `schema`, flattening `:merge` composites."
  [schema]
  (let [s (coerce-schema schema)]
    (or (seq (sm/entries s))
        (unsupported-projection-schema! schema))))

(defn- select-projected-keys
  "Project `schema` to a flat map schema, optionally dropping keys."
  [schema drop-keys]
  (let [s    (coerce-schema schema)
        keys (if (seq drop-keys)
               (remove (set drop-keys) (sm/keys s))
               (sm/keys s))]
    (sm/select-keys s (vec keys))))

(defn shape-type-schema
  "Return the compiled Penpot Malli branch for shape type `penpot-type`.

  `m/entries` on the shape `:multi` yields MapEntries whose values are
  compiled branch schemas (wrapped in `:val`). `m/children` returns raw
  entry forms and must not be used here."
  [penpot-type]
  (let [kw    (keyword penpot-type)
        multi (sm/schema cts/schema:shape-attrs)]
    (or (some (fn [entry]
                (when (= kw (key entry))
                  (val entry)))
              (m/entries multi malli-opts))
        (ex/raise :type :validation
                  :code :unknown-shape-type
                  :hint (str "unknown penpot shape type: " kw)))))

(defn project-schema
  "Build a graph node schema from canonical Malli `source`.

  Options:
  - `:drop`  - keys removed from the source (beadpot `drop_fields`)
  - `:extra` - optional extra `[:map ...]` merged on top"
  [source {:keys [drop extra]}]
  (let [projected (select-projected-keys source drop)]
    (if extra
      (sm/merge projected (coerce-schema extra))
      projected)))

(defn project-shape-schema
  "Project `:drop` from the Penpot schema for `penpot-type`."
  [penpot-type opts]
  (project-schema (shape-type-schema penpot-type) opts))

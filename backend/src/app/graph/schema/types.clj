;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.schema.types
  "Map Malli schemas to Ladybug column types.

  Analogue of beadpot's `get_ladybug_type` (util/ladybug.py)."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [malli.core :as m]))

(def ^:private malli-opts sm/default-options)

(def ^:private base-type->ladybug
  {::sm/uuid        "UUID"
   ::sm/safe-number "DOUBLE"
   ::sm/safe-double "DOUBLE"
   ::sm/safe-int    "INT64"
   ::sm/number      "DOUBLE"
   ::sm/boolean     "BOOLEAN"
   ::sm/int         "INT64"
   ::ct/inst        "TIMESTAMP"
   :uuid            "UUID"
   :string          "STRING"
   :int             "INT64"
   :double          "DOUBLE"
   :float           "DOUBLE"
   :boolean         "BOOLEAN"
   :keyword         "STRING"
   :inst            "TIMESTAMP"})

(defn- normalize-schema
  [schema]
  (let [s (sm/schema schema)]
    (if (m/-ref-schema? s)
      (recur (m/deref s malli-opts))
      s)))

(defn ladybug-type
  "Return the Ladybug column type for a Malli child schema."
  [schema]
  (let [s (normalize-schema schema)
        t (m/type s)]
    (or (base-type->ladybug t)
        (case t
          (:maybe :and) (ladybug-type (first (m/children s malli-opts)))
          (:vector :sequential :set)
          (str (ladybug-type (first (m/children s malli-opts))) "[]")

          :enum "STRING"
          (:map :map-of) "JSON"
          (do
            (l/wrn :hint "unmapped malli type for ladybug column, defaulting to STRING"
                   :malli-type t)
            "STRING")))))

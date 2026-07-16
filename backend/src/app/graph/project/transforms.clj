;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.project.transforms
  "Derived graph links (instances, tokens, nested containment, etc.).

  Ports beadpot's post-projection transforms. Currently:
  - `LinkComponentInstances` → `IsInstanceOf` (Frame → Component)."
  (:require
   [app.common.logging :as l]
   [app.graph.ladybug :as ladybug])
  (:import
   com.ladybugdb.Connection))

(defn- link-component-instances!
  "Create `IsInstanceOf` edges from Frame instance heads to Component nodes.

  Same semantics as beadpot `LinkComponentInstances`: every frame with
  `:component-id` matching a non-deleted Component in this graph (main
  instance and copy roots)."
  [^Connection conn]
  (let [n (or (ladybug/query-scalar-on-connection!
               conn
               (str "MATCH (f:Frame), (c:Component) "
                    "WHERE f.`component-id` = c.id "
                    "AND NOT COALESCE(c.deleted, false) "
                    "MERGE (f)-[:IsInstanceOf]->(c) "
                    "RETURN count(*);"))
              0)]
    (l/inf :hint "graph transform IsInstanceOf"
           :edges n)
    n))

(defn apply-transforms!
  "Apply derived transformations on an already loaded graph."
  [_system ^Connection conn _data _file]
  (let [n (link-component-instances! conn)]
    {:transforms 1
     :IsInstanceOf n}))

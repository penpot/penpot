;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.project.document
  "Project a Penpot file-data map into Ladybug nodes and structural edges.

  Projects Document, Page, the full shape tree (skipping the root frame),
  and `IsChildOf` edges from shapes to their page or container parent."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.graph.ladybug :as ladybug]
   [app.graph.project.specs :as specs]
   [app.graph.schema :as graph.schema]
   [clojure.string :as str]))

(def root-frame-id
  uuid/zero)

(def ^:private shape-type->table
  {:frame   "Frame"
   :rect    "Rectangle"
   :group   "Group"
   :circle  "Circle"
   :path    "Path"
   :text    "Text"
   :bool    "Boolean"
   :image   "Image"
   :svg-raw "SVGRaw"})

(defn- create-node-statement
  [table attrs]
  (let [props (str/join ", "
                        (map (fn [[k v]]
                               (str "`" (name k) "`: " (ladybug/format-value v)))
                             attrs))]
    (str "CREATE (n:`" table "` {" props "});")))

(defn- validated-node-statement
  [check-fn table attrs]
  (create-node-statement table (check-fn attrs)))

(defn- merge-edge-statement
  [from-table from-id to-table to-id position]
  (str "MATCH (c:`" from-table "` {`id`: " (ladybug/format-uuid from-id) "}), "
       "(p:`" to-table "` {`id`: " (ladybug/format-uuid to-id) "}) "
       "MERGE (c)-[:`IsChildOf` {`position`: " (ladybug/format-int position) "}]->(p);"))

(defn- document-attrs
  [file data]
  {:id       (or (:id data) (:id file))
   :name     (or (:name data) (:name file) "Untitled")
   :version  (long (or (:version data) 67))
   :revision (long (or (:revn file) 0))})

(defn- page-attrs
  [page index]
  (cond-> {:id   (:id page)
           :name (:name page)}
    (some? index) (assoc :index (long index))))

(defn- shape-table
  [shape]
  (get shape-type->table (keyword (:type shape))))

(defn- shape-node-attrs
  [shape]
  {:id   (:id shape)
   :name (:name shape)})

(defn- container-table?
  [table]
  (contains? graph.schema/container-node-tables table))

(defn- child-shape-ids
  "Child ids in Penpot z-order (reversed from the stored :shapes list)."
  [parent]
  (when-let [shapes (:shapes parent)]
    (vec (reverse shapes))))

(declare project-shape-ids)

(defn- project-shape
  "Project one shape node and recurse into its children."
  [objects statements stats table shape parent-table parent-id position]
  (let [shape-id    (:id shape)
        statements' (conj statements
                          (validated-node-statement specs/check-shape-node table
                                                    (shape-node-attrs shape))
                          (merge-edge-statement table shape-id
                                                parent-table parent-id position))
        stats'      (update stats :shapes inc)]
    (if-let [child-ids (when (container-table? table)
                         (child-shape-ids shape))]
      (project-shape-ids objects statements' stats' table shape-id child-ids)
      [statements' stats'])))

(defn- project-shape-ids
  [objects statements stats parent-table parent-id child-ids]
  (reduce
   (fn [[stmts st] [position shape-id]]
     (if-let [shape (get objects shape-id)]
       (if-let [table (shape-table shape)]
         (project-shape objects stmts st table shape parent-table parent-id position)
         (do
           (l/wrn :hint "unsupported shape type for graph slice"
                  :shape-id (str shape-id)
                  :type (:type shape))
           [stmts st]))
       (do
         (l/wrn :hint "missing shape in page objects"
                :shape-id (str shape-id))
         [stmts st])))
   [statements stats]
   (map-indexed vector child-ids)))

(defn- project-page
  [statements stats doc-id page position]
  (let [page-id     (:id page)
        objects     (:objects page)
        root        (get objects root-frame-id)
        statements' (conj statements
                          (validated-node-statement specs/check-page "Page"
                                                    (page-attrs page position))
                          (merge-edge-statement "Page" page-id "Document" doc-id position))
        stats'      (update stats :pages inc)]
    (if-let [top-level-ids (child-shape-ids root)]
      (project-shape-ids objects statements' stats' "Page" page-id top-level-ids)
      [statements' stats'])))

(defn projection-statements
  "Build Cypher statements for projecting `data` into Ladybug.

  Returns `{:statements [...] :stats {...}}` without executing them."
  [data file]
  (let [doc-id    (or (:id data) (:id file))
        pages     (seq (reverse (:pages data)))
        initial   [(validated-node-statement specs/check-document "Document"
                                              (document-attrs file data))]
        [statements stats]
        (if (empty? pages)
          [initial {:documents 1 :pages 0 :shapes 0}]
          (reduce (fn [[stmts st] [position page-id]]
                    (if-let [page (get-in data [:pages-index page-id])]
                      (project-page stmts st doc-id page position)
                      (do
                        (l/wrn :hint "missing page in pages-index"
                               :page-id (str page-id))
                        [stmts st])))
                  [initial {:documents 1 :pages 0 :shapes 0}]
                  (map-indexed vector pages)))]
    {:statements statements
     :stats     stats}))

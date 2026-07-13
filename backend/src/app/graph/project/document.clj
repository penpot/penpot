;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.project.document
  "Project a Penpot file-data map into Ladybug nodes and structural edges.

  Vertical slice: Document, Page, top-level shapes, and `IsChildOf` edges
  mirroring beadpot's `add_document` first pass."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.graph.ladybug :as ladybug]
   [app.graph.project.specs :as specs]
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

(defn- project-page
  [statements stats doc-id page position]
  (let [page-id       (:id page)
        objects       (:objects page)
        root          (get objects root-frame-id)
        top-level-ids (when root (vec (reverse (:shapes root))))
        statements'   (conj statements
                            (validated-node-statement specs/check-page "Page"
                                                      (page-attrs page position))
                            (merge-edge-statement "Page" page-id "Document" doc-id position))
        stats'        (update stats :pages inc)]
    (if (seq top-level-ids)
       (reduce
        (fn [[stmts st] [shape-pos shape-id]]
          (if-let [shape (get objects shape-id)]
            (if-let [table (shape-table shape)]
              [(-> stmts
                   (conj (validated-node-statement specs/check-shape-node table
                                                   {:id   (:id shape)
                                                    :name (:name shape)})
                         (merge-edge-statement table (:id shape)
                                               "Page" page-id shape-pos)))
               (update st :shapes inc)]
              (do
                (l/wrn :hint "unsupported shape type for graph slice"
                       :shape-id (str shape-id)
                       :type (:type shape))
                [stmts st]))
            [stmts st]))
       [statements' stats']
       (map-indexed vector top-level-ids))
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

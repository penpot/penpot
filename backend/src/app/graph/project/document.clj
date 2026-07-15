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
   [app.graph.project.specs :as specs]
   [app.graph.schema :as graph.schema]))

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
  (specs/check-shape-node {:id   (:id shape)
                           :name (:name shape)}))

(defn- container-table?
  [table]
  (contains? graph.schema/container-node-tables table))

(defn- child-shape-ids
  "Child ids in Penpot z-order (reversed from the stored :shapes list)."
  [parent]
  (when-let [shapes (:shapes parent)]
    (vec (reverse shapes))))

(defn- initial-acc
  []
  {:nodes {}
   :edges []
   :stats {:documents 0 :pages 0 :shapes 0}})

(declare project-shape-ids)

(defn- project-shape
  [objects acc table shape parent-table parent-id position]
  (let [shape-id (:id shape)
        acc'     (-> acc
                     (update-in [:nodes table] (fnil conj []) (shape-node-attrs shape))
                     (update :edges conj {:from-table table
                                          :from-id    shape-id
                                          :to-table   parent-table
                                          :to-id      parent-id
                                          :position   position})
                     (update-in [:stats :shapes] inc))]
    (if-let [child-ids (when (container-table? table)
                         (child-shape-ids shape))]
      (project-shape-ids objects acc' table shape-id child-ids)
      acc')))

(defn- project-shape-ids
  [objects acc parent-table parent-id child-ids]
  (reduce
   (fn [acc [position shape-id]]
     (if-let [shape (get objects shape-id)]
       (if-let [table (shape-table shape)]
         (project-shape objects acc table shape parent-table parent-id position)
         (do
           (l/wrn :hint "unsupported shape type for graph slice"
                  :shape-id (str shape-id)
                  :type (:type shape))
           acc))
       (do
         (l/wrn :hint "missing shape in page objects"
                :shape-id (str shape-id))
         acc)))
   acc
   (map-indexed vector child-ids)))

(defn- project-page
  [acc doc-id page position]
  (let [page-id     (:id page)
        objects     (:objects page)
        root        (get objects root-frame-id)
        page-node   (specs/check-page (page-attrs page position))
        acc'        (-> acc
                        (update-in [:nodes "Page"] (fnil conj []) page-node)
                        (update :edges conj {:from-table "Page"
                                             :from-id    page-id
                                             :to-table   "Document"
                                             :to-id      doc-id
                                             :position   position})
                        (update-in [:stats :pages] inc))]
    (if-let [top-level-ids (child-shape-ids root)]
      (project-shape-ids objects acc' "Page" page-id top-level-ids)
      acc')))

(defn projection-data
  "Build node/edge rows for projecting `data` into Ladybug.

  Returns `{:nodes {table [attrs ...]} :edges [...] :stats {...}}`."
  [data file]
  (let [doc-id  (or (:id data) (:id file))
        doc-node (specs/check-document (document-attrs file data))
        pages   (seq (reverse (:pages data)))
        acc0    (-> (initial-acc)
                    (update-in [:nodes "Document"] (fnil conj []) doc-node)
                    (assoc-in [:stats :documents] 1))
        acc     (if (empty? pages)
                  acc0
                  (reduce (fn [acc [position page-id]]
                            (if-let [page (get-in data [:pages-index page-id])]
                              (project-page acc doc-id page position)
                              (do
                                (l/wrn :hint "missing page in pages-index"
                                       :page-id (str page-id))
                                acc)))
                          acc0
                          (map-indexed vector pages)))]
    (select-keys acc [:nodes :edges :stats])))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.project.document
  "Project a Penpot file-data map into Ladybug nodes and structural edges.

  Projects Document, Page, Component, the full shape tree (skipping the root
  frame), and `IsChildOf` edges from shapes/pages/components to their parent."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.graph.schema.nodes :as nodes]))

(def root-frame-id
  uuid/zero)

(defn- document-attrs
  [file data]
  (-> file
      (assoc :id (or (:id data) (:id file)))
      (dissoc :data)))

(defn- page-attrs
  [page index]
  (-> page
      (dissoc :objects)
      (cond-> (some? index) (assoc :index (long index)))))

(defn- component-attrs
  [component]
  (-> component
      (dissoc :objects)
      ;; schema:component requires :path; some legacy rows omit it
      (update :path #(or % ""))))

(defn- shape-table
  [shape]
  (nodes/table-for-type (:type shape)))

(defn- shape-node-attrs
  [table shape]
  (nodes/project-attrs table shape))

(defn- container-table?
  [table]
  (contains? nodes/container-tables table))

(defn- child-shape-ids
  "Child ids in Penpot z-order (reversed from the stored :shapes list)."
  [parent]
  (when-let [shapes (:shapes parent)]
    (vec (reverse shapes))))

(defn- initial-acc
  []
  {:nodes {}
   :edges []
   :stats {:documents 0 :pages 0 :components 0 :shapes 0}})

(declare project-shape-ids)

(defn- project-shape
  [objects acc table shape parent-table parent-id position]
  (let [shape-id (:id shape)
        acc'     (-> acc
                     (update-in [:nodes table] (fnil conj []) (shape-node-attrs table shape))
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
        page-node   (nodes/project-attrs "Page" (page-attrs page position))
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

(defn- project-component
  [acc doc-id component position]
  (if (:deleted component)
    acc
    (let [comp-id (:id component)
          node    (nodes/project-attrs "Component" (component-attrs component))]
      (-> acc
          (update-in [:nodes "Component"] (fnil conj []) node)
          (update :edges conj {:from-table "Component"
                               :from-id    comp-id
                               :to-table   "Document"
                               :to-id      doc-id
                               :position   position})
          (update-in [:stats :components] inc)))))

(defn- project-components
  [acc doc-id components]
  (reduce (fn [acc [position [_id component]]]
            (project-component acc doc-id component position))
          acc
          (map-indexed vector components)))

(defn projection-data
  "Build node/edge rows for projecting `data` into Ladybug.

  Returns `{:nodes {table [attrs ...]} :edges [...] :stats {...}}`."
  [data file]
  (let [doc-id   (or (:id data) (:id file))
        doc-node (nodes/project-attrs "Document" (document-attrs file data))
        pages    (seq (reverse (:pages data)))
        comps    (seq (:components data))
        acc0     (-> (initial-acc)
                     (update-in [:nodes "Document"] (fnil conj []) doc-node)
                     (assoc-in [:stats :documents] 1))
        acc      (cond-> acc0
                   (seq comps)
                   (project-components doc-id comps))
        acc      (if (empty? pages)
                   acc
                   (reduce (fn [acc [position page-id]]
                             (if-let [page (get-in data [:pages-index page-id])]
                               (project-page acc doc-id page position)
                               (do
                                 (l/wrn :hint "missing page in pages-index"
                                        :page-id (str page-id))
                                 acc)))
                           acc
                           (map-indexed vector pages)))]
    (select-keys acc [:nodes :edges :stats])))

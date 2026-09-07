;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.projection.document
  "Project a Penpot file-data map into Ladybug nodes and structural edges.

  Projects Document, Page, Component, the full shape tree (skipping the root
  frame), and `IsChildOf` edges from shapes/pages/components to their parent.

  Two denormalizations happen here rather than in a later pass, because the
  walk already has both answers in hand and a post-ingest statement would have
  to rediscover them:

  - `page-id` on every shape, from the page the walk is currently in;
  - `component-id` propagated from an instance head down to its descendants,
    from the head context the walk carries."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.graph.schema.nodes :as nodes]))

(def root-frame-id
  uuid/zero)

(defn- document-attrs
  "The Document node's attrs: the file row, minus its data blob.

  `:options` is lifted out of the blob before it goes: it is file-level
  configuration a consumer wants without opening `:data`."
  [file data]
  (-> file
      (assoc :id (or (:id data) (:id file)))
      (cond-> (:options data) (assoc :options (:options data)))
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

(defn denormalized-shape
  "`shape` with `page-id` set and an inherited `component-id` filled in.

  A shape that carries its own `component-id` keeps it; `component-ctx` only
  fills the gap for descendants (see `descend-component-ctx`)."
  [shape page-id component-ctx]
  (cond-> (assoc shape :page-id page-id)
    (and (uuid? component-ctx) (nil? (:component-id shape)))
    (assoc :component-id component-ctx)))

(defn- shape-node-attrs
  [table shape page-id component-ctx]
  (nodes/project-attrs table (denormalized-shape shape page-id component-ctx)))

(defn descend-component-ctx
  "The component context to pass to `shape`'s children.

  Inheritance stops at the nearest ancestor Frame carrying a `component-id`,
  and any intermediate shape that carries one is a barrier:

  - a Frame with its own `component-id` becomes the new context (it is an
    instance head, and its descendants belong to *it*, not to an outer head);
  - any other shape carrying a `component-id` blocks inheritance below it
    without being able to supply one, since only Frames are heads;
  - otherwise the context passes through unchanged."
  [table shape ctx]
  (let [own (:component-id shape)]
    (cond
      (and (some? own) (= table "Frame")) own
      (some? own)                         ::blocked
      :else                               ctx)))

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
  [objects acc table shape parent-table parent-id position page-id component-ctx]
  (let [shape-id (:id shape)
        acc'     (-> acc
                     (update-in [:nodes table] (fnil conj [])
                                (shape-node-attrs table shape page-id component-ctx))
                     (update :edges conj {:from-table table
                                          :from-id    shape-id
                                          :to-table   parent-table
                                          :to-id      parent-id
                                          :position   position})
                     (update-in [:stats :shapes] inc))]
    (if-let [child-ids (when (container-table? table)
                         (child-shape-ids shape))]
      (project-shape-ids objects acc' table shape-id child-ids page-id
                         (descend-component-ctx table shape component-ctx))
      acc')))

(defn- project-shape-ids
  [objects acc parent-table parent-id child-ids page-id component-ctx]
  (reduce
   (fn [acc [position shape-id]]
     (if-let [shape (get objects shape-id)]
       (if-let [table (shape-table shape)]
         (project-shape objects acc table shape parent-table parent-id position
                        page-id component-ctx)
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
      (project-shape-ids objects acc' "Page" page-id top-level-ids page-id nil)
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
        ;; `:pages` is the tab order the user sees, and `Page.index` and the
        ;; page's `IsChildOf.position` are that order. Child shapes are
        ;; reversed on the way in (`child-shape-ids`) because their stored
        ;; list runs bottom to top; pages have no such second ordering.
        pages    (seq (:pages data))
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

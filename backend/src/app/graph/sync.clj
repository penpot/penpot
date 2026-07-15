;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.sync
  "Incremental Ladybug graph updates from Penpot file-change events."
  (:require
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.graph.ladybug :as ladybug]
   [app.graph.project.specs :as specs]
   [clojure.string :as str])
  (:import
   com.ladybugdb.Connection))

(set! *warn-on-reflection* true)

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

(def ^:private supported-change-types
  #{:add-obj :mod-obj :del-obj :add-page :del-page :mod-page :mov-objects})

(defn- node-label
  [table]
  (if (#{"Group" "Boolean"} table)
    (str "`" table "`")
    table))

(defn- shape-table
  [shape]
  (get shape-type->table (keyword (:type shape))))

(defn- build-parent-map
  [edges]
  (into {}
        (map (fn [{:keys [from-id to-id to-table]}]
               [from-id {:parent-id to-id :parent-table to-table}]))
        edges))

(defn- build-children-map
  [edges]
  (reduce (fn [acc {:keys [from-id to-id]}]
            (update acc to-id (fnil conj #{}) from-id))
          {}
          edges))

(defn- resolve-page-id
  [shape-id parents pages]
  (loop [id shape-id]
    (cond
      (contains? pages id) id
      (get parents id) (recur (:parent-id (parents id)))
      :else nil)))

(defn- node-attrs-id
  [attrs]
  (cond
    (map? attrs) (or (:id attrs) (get attrs "id"))
    (and (vector? attrs) (= 2 (count attrs)))
    (let [[k v] attrs]
      (when (or (= k :id) (= k "id")) v))))

(defn- table-rows
  "Normalize a projection table value to a vector of attribute maps."
  [nodes table]
  (let [rows (or (get nodes table) (get nodes (keyword table)))]
    (cond
      (nil? rows) []
      (map? rows) [rows]
      (sequential? rows) (vec rows)
      :else [])))

(defn- document-id-from-nodes
  [nodes file-id]
  (or (some node-attrs-id (table-rows nodes "Document"))
      file-id))

(defn- page-index-entry
  [attrs]
  (let [id (node-attrs-id attrs)]
    [id {:id    id
         :name  (:name attrs)
         :index (long (:index attrs 0))}]))

(defn- index-pages
  [nodes]
  (into {} (map page-index-entry (table-rows nodes "Page"))))

(defn- shape-index-table?
  [table]
  (not (contains? #{"Document" "Page" :Document :Page} table)))

(defn- shape-index-entry
  [table attrs parents pages edges]
  (let [shape-id (node-attrs-id attrs)
        {:keys [parent-id parent-table]} (parents shape-id)
        edge (first (filter #(= shape-id (:from-id %)) edges))]
    [shape-id {:id            shape-id
               :name          (:name attrs)
               :table         table
               :parent-id     parent-id
               :parent-table  parent-table
               :position      (long (:position edge 0))
               :page-id       (resolve-page-id shape-id parents pages)}]))

(defn- index-shapes
  [nodes edges parents pages]
  (reduce
   (fn [acc [table _]]
     (into acc (map #(shape-index-entry table % parents pages edges)
                    (table-rows nodes table))))
   {}
   (filter (fn [[table _]] (shape-index-table? table)) nodes)))

(defn build-index
  "Build a sync index from a full graph projection."
  [file-id revn {:keys [nodes edges]}]
  (let [doc-id         (document-id-from-nodes nodes file-id)
        pages          (index-pages nodes)
        parents        (build-parent-map edges)
        children-index (build-children-map edges)
        shapes         (index-shapes nodes edges parents pages)]
    {:file-id  file-id
     :doc-id   doc-id
     :revn     (long revn)
     :pages    pages
     :shapes   shapes
     :children children-index}))


(defn- create-node-statement
  [table {:keys [id name version revision index]}]
  (let [label (node-label table)
        attrs (cond-> [(str "id: " (ladybug/format-uuid id))
                       (str "name: " (ladybug/format-string name))]
                (some? version)  (conj (str "version: " (ladybug/format-int version)))
                (some? revision) (conj (str "revision: " (ladybug/format-int revision)))
                (some? index)    (conj (str "index: " (ladybug/format-int index))))]
    (str "CREATE (:" label " {" (str/join ", " attrs) "});")))

(defn- delete-node-statement
  [table shape-id]
  (str "MATCH (n:" (node-label table) " {id: " (ladybug/format-uuid shape-id) "}) "
       "DETACH DELETE n;"))

(defn- create-edge-statement
  [{:keys [from-table from-id to-table to-id position]}]
  (str "MATCH (s:" (node-label from-table) " {id: " (ladybug/format-uuid from-id) "}), "
       "(p:" (node-label to-table) " {id: " (ladybug/format-uuid to-id) "}) "
       "CREATE (s)-[:IsChildOf {position: " (ladybug/format-int position) "}]->(p);"))

(defn- delete-edge-statement
  [{:keys [from-table from-id to-table to-id]}]
  (str "MATCH (s:" (node-label from-table) " {id: " (ladybug/format-uuid from-id) "})"
       "-[r:IsChildOf]->"
       "(p:" (node-label to-table) " {id: " (ladybug/format-uuid to-id) "}) "
       "DELETE r;"))

(defn- set-edge-position-statement
  [{:keys [from-table from-id to-table to-id position]}]
  (str "MATCH (s:" (node-label from-table) " {id: " (ladybug/format-uuid from-id) "})"
       "-[r:IsChildOf]->"
       "(p:" (node-label to-table) " {id: " (ladybug/format-uuid to-id) "}) "
       "SET r.position = " (ladybug/format-int position) ";"))

(defn- set-shape-name-statement
  [table shape-id name]
  (str "MATCH (s:" (node-label table) " {id: " (ladybug/format-uuid shape-id) "}) "
       "SET s.name = " (ladybug/format-string name) ";"))

(defn- set-page-name-statement
  [page-id name]
  (str "MATCH (p:Page {id: " (ladybug/format-uuid page-id) "}) "
       "SET p.name = " (ladybug/format-string name) ";"))


(defn- set-document-revision-statement
  [doc-id revn]
  (str "MATCH (d:Document {id: " (ladybug/format-uuid doc-id) "}) "
       "SET d.revision = " (ladybug/format-int revn) ";"))

(defn- resolve-parent-for-add
  [index {:keys [parent-id frame-id page-id]}]
  (let [pid (or parent-id frame-id)]
    (if (or (nil? pid) (uuid/zero? pid))
      (when page-id
        {:parent-id page-id :parent-table "Page"})
      (if-let [shape (get-in index [:shapes pid])]
        {:parent-id pid :parent-table (:table shape)}
        (when (get-in index [:pages pid])
          {:parent-id pid :parent-table "Page"})))))

(defn- default-position
  [index parent-id]
  (count (get-in index [:children parent-id] #{})))

(defn- index-add-shape!
  [index {:keys [id name table parent-id parent-table position page-id]}]
  (-> index
      (assoc-in [:shapes id]
                {:id           id
                 :name         name
                 :table        table
                 :parent-id    parent-id
                 :parent-table parent-table
                 :position     position
                 :page-id      page-id})
      (update :children update parent-id (fnil conj #{}) id)))

(defn- index-remove-shape!
  [index shape-id]
  (if-let [shape (get-in index [:shapes shape-id])]
    (-> index
        (update :shapes dissoc shape-id)
        (update :children update (:parent-id shape)
                #(disj (or % #{}) shape-id))
        (update :children dissoc shape-id))
    index))

(defn- index-add-page!
  [index {:keys [id name index doc-id]}]
  (-> index
      (assoc-in [:pages id] {:id id :name name :index index})
      (update :children update doc-id (fnil conj #{}) id)))

(defn- index-move-shape!
  [index shape-id {:keys [parent-id parent-table position page-id]}]
  (let [old-parent (get-in index [:shapes shape-id :parent-id])]
    (-> index
        (assoc-in [:shapes shape-id :parent-id] parent-id)
        (assoc-in [:shapes shape-id :parent-table] parent-table)
        (assoc-in [:shapes shape-id :position] position)
        (cond-> page-id (assoc-in [:shapes shape-id :page-id] page-id))
        (update :children update old-parent #(disj (or % #{}) shape-id))
        (update :children update parent-id (fnil conj #{}) shape-id))))

(defn- mov-object-ids
  [shapes]
  (let [coll (cond
                (nil? shapes) []
                (sequential? shapes) shapes
                (uuid? shapes) [shapes]
                (map? shapes) (if-let [id (or (:id shapes) (get shapes "id"))]
                                [id]
                                [])
                :else [])]
    (into []
          (keep (fn [shape]
                  (when shape
                    (if (uuid? shape) shape (:id shape)))))
          coll)))

(defn- mov-position
  [idx parent-id {:keys [index after-shape]}]
  (cond
    (some? index) (long index)
    after-shape (let [after-pos (get-in idx [:shapes after-shape :position])]
                  (if (some? after-pos)
                    (inc (long after-pos))
                    (default-position idx parent-id)))
    :else (default-position idx parent-id)))

(defn- apply-mov-objects
  [index {:keys [shapes page-id] :as change}]
  (let [shape-ids (mov-object-ids shapes)
        parent    (resolve-parent-for-add index
                                            (assoc change
                                                   :frame-id (:parent-id change)
                                                   :page-id page-id))]
    (cond
      (empty? shape-ids)
      {:index index :statements [] :applied? true}

      (not parent)
      {:index index :statements [] :applied? false :reason :missing-parent}

      :else
      (let [base-position (mov-position index (:parent-id parent) change)
            parent-id     (:parent-id parent)
            parent-table  (:parent-table parent)
            page-id'      (or page-id
                              (when (= parent-table "Page") parent-id)
                              (get-in index [:shapes (first shape-ids) :page-id]))]
        (loop [index       index
               statements  []
               shape-ids   (map-indexed vector shape-ids)]
          (if-let [[offset shape-id] (first shape-ids)]
            (if-let [shape (get-in index [:shapes shape-id])]
              (let [position   (+ base-position (long offset))
                    same-edge? (and (= parent-id (:parent-id shape))
                                    (= parent-table (:parent-table shape))
                                    (= position (:position shape)))
                    edge       {:from-table   (:table shape)
                                :from-id      shape-id
                                :to-table     parent-table
                                :to-id        parent-id
                                :position     position}
                    statements (if same-edge?
                                 statements
                                 (into statements
                                       (if (= parent-id (:parent-id shape))
                                         [(set-edge-position-statement edge)]
                                         [(delete-edge-statement
                                           {:from-table   (:table shape)
                                            :from-id      shape-id
                                            :to-table     (:parent-table shape)
                                            :to-id        (:parent-id shape)})
                                          (create-edge-statement edge)])))
                    index      (if same-edge?
                                 index
                                 (index-move-shape! index shape-id
                                                    {:parent-id    parent-id
                                                     :parent-table parent-table
                                                     :position     position
                                                     :page-id      page-id'}))]
                (recur index statements (rest shape-ids)))
              (recur index statements (rest shape-ids)))
            {:index      index
             :statements statements
             :applied?   true}))))))

(defn- index-remove-page!
  [index page-id]
  (let [doc-id (:doc-id index)]
    (-> index
        (update :pages dissoc page-id)
        (update :children update doc-id #(disj (or % #{}) page-id))
        (update :children dissoc page-id))))

(defn- apply-add-obj
  [index change]
  (let [{:keys [id obj page-id parent-id frame-id index]} change
        table (shape-table obj)]
    (if-not table
      {:index index :statements [] :applied? false :reason :unsupported-shape-type}
      (let [parent (resolve-parent-for-add index change)]
        (if-not parent
          {:index index :statements [] :applied? false :reason :missing-parent}
          (let [position (long (or index (default-position index (:parent-id parent))))
                attrs    (specs/check-shape-node {:id id :name (:name obj)})
                edge     (merge {:from-table table
                                 :from-id    id
                                 :to-table   (:parent-table parent)
                                 :to-id      (:parent-id parent)
                                 :position   position}
                               )]
            {:index       (index-add-shape! index
                                             {:id            id
                                              :name          (:name attrs)
                                              :table         table
                                              :parent-id     (:parent-id parent)
                                              :parent-table  (:parent-table parent)
                                              :position      position
                                              :page-id       (or page-id (when (= (:parent-table parent) "Page")
                                          (:parent-id parent)))})
             :statements  [(create-node-statement table attrs)
                           (create-edge-statement edge)]
             :applied?    true}))))))

(defn- apply-mod-obj
  [index {:keys [id operations]}]
  (if-let [shape (get-in index [:shapes id])]
    (let [name-ops (filter #(and (= :set (:type %)) (= :name (:attr %))) operations)]
      (if (empty? name-ops)
        {:index index :statements [] :applied? false :reason :unsupported-operations}
        (let [name     (:val (last name-ops))
              table    (:table shape)
              attrs    (specs/check-shape-node {:id id :name name})]
          {:index      (assoc-in index [:shapes id :name] (:name attrs))
           :statements [(set-shape-name-statement table id (:name attrs))]
           :applied?   true})))
    {:index index :statements [] :applied? false :reason :missing-shape}))

(defn- delete-order-deepest-first
  [children root-id]
  (letfn [(post-order [id]
            (into (mapcat post-order (get children id #{}))
                  [id]))]
    (post-order root-id)))

(defn- apply-del-obj
  [index {:keys [id]}]
  (if-let [shape (get-in index [:shapes id])]
    (let [to-delete (delete-order-deepest-first (:children index) id)
          statements
          (vec (concat
                (mapcat (fn [shape-id]
                          (let [{:keys [table parent-id parent-table]}
                                (get-in index [:shapes shape-id])]
                            [(delete-edge-statement
                              {:from-table   table
                               :from-id      shape-id
                               :to-table     parent-table
                               :to-id        parent-id})
                             (delete-node-statement table shape-id)]))
                        to-delete)))]
      {:index      (reduce index-remove-shape! index to-delete)
       :statements statements
       :applied?   true})
    ;; Penpot emits one :del-obj per selected shape; an earlier change in the
    ;; same batch may have already removed this node (e.g. parent + child).
    {:index index :statements [] :applied? true}))

(defn- apply-add-page
  [index {:keys [id name page]}]
  (let [page-id  (or id (:id page))
        page     (or page {:id page-id :name name})
        page     (specs/check-page {:id page-id
                                    :name (or (:name page) "Page")
                                    :index (count (:pages index))})
        doc-id   (:doc-id index)
        position (count (:pages index))
        edge     {:from-table "Page"
                  :from-id    page-id
                  :to-table   "Document"
                  :to-id      doc-id
                  :position   position}]
    {:index      (index-add-page! index
                                 {:id      page-id
                                  :name    (:name page)
                                  :index   (:index page)
                                  :doc-id  doc-id})
     :statements [(create-node-statement "Page" page)
                  (create-edge-statement edge)]
     :applied?   true}))

(defn- apply-del-page
  [index {:keys [id]}]
  (if-let [page (get-in index [:pages id])]
    (let [shape-ids (into #{}
                          (comp (filter #(= id (get-in index [:shapes % :page-id])))
                                (filter #(= "Page" (get-in index [:shapes % :parent-table])))
                          (keys (:shapes index))))
          del-shapes
          (reduce (fn [acc shape-id]
                    (let [result (apply-del-obj acc {:type :del-obj :id shape-id})]
                      (if (:applied? result)
                        (-> acc
                            (assoc :index (:index result))
                            (update :statements into (:statements result)))
                        acc)))
                  {:index index :statements []}
                  shape-ids)
          statements
          (conj (:statements del-shapes)
                (delete-edge-statement {:from-table "Page"
                                        :from-id    id
                                        :to-table   "Document"
                                        :to-id      (:doc-id index)})
                (delete-node-statement "Page" id))]
      {:index      (-> (:index del-shapes) (index-remove-page! id))
       :statements statements
       :applied?   true})
    {:index index :statements [] :applied? false :reason :missing-page}))

(defn- apply-mod-page
  [index {:keys [id name]}]
  (if (and (string? name) (get-in index [:pages id]))
    {:index      (assoc-in index [:pages id :name] name)
     :statements [(set-page-name-statement id name)]
     :applied?   true}
    {:index index :statements [] :applied? false :reason :unsupported-page-change}))

(defn- apply-change
  [index change]
  (case (:type change)
    :add-obj  (apply-add-obj index change)
    :mod-obj  (apply-mod-obj index change)
    :del-obj  (apply-del-obj index change)
    :add-page (apply-add-page index change)
    :del-page (apply-del-page index change)
    :mod-page (apply-mod-page index change)
    :mov-objects (apply-mov-objects index change)
    {:index index :statements [] :applied? false :reason :unsupported-type}))

(defn apply-changes!
  "Apply Penpot `changes` to an open Ladybug `conn` and return the updated index.

  Returns `{:index ... :revn ... :applied [...] :skipped [...]}`."
  [^Connection conn index changes revn]
  (when (> (long revn) (:revn index))
    (l/wrn :hint "graph sync revn gap"
           :file-id (str (:file-id index))
           :index-revn (:revn index)
           :change-revn revn))
  (loop [index   index
         applied []
         skipped []
         stmts   []
         changes (seq changes)]
    (if-let [change (first changes)]
      (let [{:keys [index statements applied? reason]}
            (apply-change index change)]
        (recur index
               (cond-> applied applied? (conj (:type change)))
               (cond-> skipped (not applied?) (conj {:type (:type change) :reason reason}))
               (cond-> stmts applied? (into statements))
               (rest changes)))
      (let [final-stmts (cond-> stmts
                          (and (seq applied) (:doc-id index))
                          (conj (set-document-revision-statement (:doc-id index) revn)))
            index'      (if (seq applied)
                          (assoc index :revn (long revn))
                          index)]
        (when (seq final-stmts)
          (ladybug/exec-on-connection! conn final-stmts))
        {:index   index'
         :revn    (if (seq applied) (long revn) (:revn index'))
         :applied applied
         :skipped skipped}))))

(defn supported-change?
  [change]
  (contains? supported-change-types (:type change)))

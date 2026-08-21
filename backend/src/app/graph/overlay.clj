;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.overlay
  "Datascript index over a Penpot document: identity, type, and topology.

  The overlay is an index over what Penpot's helpers read, never a
  reimplementation of them. `app.common.types.component/instance-head?`,
  `app.common.types.container/get-parent-heads`,
  `app.common.types.file/find-ref-shape` and their relatives stay the
  correctness oracle; the overlay's job is to make the questions those
  helpers answer by scanning cheap to reach at scale
  (docs.local/graph/20260819-report-graph-overlay-datascript.md).

  Entity identity is the (container, shape) pair, never the shape id
  alone. Shape ids are stable but repeat across containers: `uuid/zero`
  keys the root frame of every page, duplicated pages share non-root ids,
  and a deleted component keeps its own copy of the main-instance subtree
  under the same ids (`app.common.types.file/load-component-objects`).
  The builder therefore mints one entity per (container, shape) pair, and
  parent links are datascript entity references, so topology is interned
  by construction.

  Three results of the 2026-08-20 study are structural here
  (docs.local/graph/20260820-report-graph-overlay-datascript.md):

  - **Subtle helpers run in the builder, never in a query.**
    `ctf/find-ref-shape` is called once per copy shape at build time and
    its answer becomes the plain `:shape/refers-to` reference; a query
    never touches the helper. Unresolvable references (libraries not
    loaded) simply produce no edge.
  - **Containment carries a second form**: one global Euler-tour
    numbering, one DFS per container drawing from a single global
    counter, so `inside subtree X` is two interval comparisons. The
    counter is global because per-container counters overlap numerically
    and silently match other pages.
  - **Token applications fold the property into the attribute name**:
    `{:token/fill \"layerTwo.background\"}` on the shape itself, over the
    closed vocabulary of `app.common.types.token/all-keys`. The
    `uses-token` rule in `app.graph.overlay.queries` hides the encoding,
    so the choice stays reversible.

  The namespace is deliberately JVM-free (no imports, no interop): the
  same code moves to the browser worker as a `.cljc` rename when the
  mirror experiment lands, which is the point of choosing datascript."
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [datascript.core :as d]))

(def schema-version
  "penpot-graph-overlay-2")

(def token-attr
  "The overlay attribute carrying one applied-token property:
  `:fill` -> `:token/fill`."
  (into {} (map (fn [k] [k (keyword "token" (name k))])) cto/all-keys))

(def token-attrs
  "The closed folded-token vocabulary, one attribute per applied-token
  property of `app.common.types.token/all-keys`."
  (into #{} (vals token-attr)))

(defn token-attr->prop
  "`:token/fill` -> `:fill`."
  [attr]
  (keyword (name attr)))

(def schema
  "Only identity, type, topology, and the attributes a standing query has
  asked for. Heavy attributes (geometry, style payloads) stay in the
  document, which the holder of the overlay already has.

  Color references are stored per source (`fill`, `stroke`, text content)
  rather than as one union, because the sync path receives `:set` ops one
  attribute at a time and must be able to rebuild each contribution
  independently. The `uses-color` rule in `app.graph.overlay.queries`
  reunites them. Folded token attributes (`:token/fill`, ...) are plain
  string values and need no declaration."
  {;; document
   :document/id        {:db/unique :db.unique/identity}
   ;; containers: pages, and components that carry their own :objects
   :container/id       {:db/unique :db.unique/identity}
   :container/document {:db/valueType :db.type/ref}
   ;; shapes: identity is the (container, shape) pair
   :shape/id           {:db/index true}
   :shape/container    {:db/valueType :db.type/ref}
   :shape/parent       {:db/valueType :db.type/ref}
   :shape/name         {:db/index true}
   :shape/component-id   {:db/index true}
   :shape/component-file {:db/index true}
   :shape/shape-ref    {:db/index true}
   :shape/swap-slot    {:db/index true}
   ;; the builder-resolved reference edge: ctf/find-ref-shape's answer
   :shape/refers-to    {:db/valueType :db.type/ref}
   ;; global Euler-tour containment intervals
   :shape/enter        {:db/index true}
   :shape/exit         {:db/index true}
   ;; asset links (the beadpot ledger's asset link transforms)
   :shape/fill-color   {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :shape/stroke-color {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :shape/text-color   {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :shape/uses-typography {:db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/many}
   ;; components (the record, distinct from any container it may carry)
   :component/id       {:db/unique :db.unique/identity}
   :component/document {:db/valueType :db.type/ref}
   ;; library assets
   :color/id           {:db/unique :db.unique/identity}
   :color/document     {:db/valueType :db.type/ref}
   :typography/id      {:db/unique :db.unique/identity}
   :typography/document {:db/valueType :db.type/ref}
   :token-set/id       {:db/unique :db.unique/identity}
   :token-set/document {:db/valueType :db.type/ref}
   :token/id           {:db/unique :db.unique/identity}
   :token/name         {:db/index true}
   :token/set          {:db/valueType :db.type/ref}})

(def shape-type->table
  "The Ladybug projection's node table names, kept for parity counting and
  for console labels (`app.graph.schema.nodes/shape-node-types`)."
  {:frame   "Frame"
   :group   "Group"
   :bool    "Boolean"
   :svg-raw "SVGRaw"
   :rect    "Rectangle"
   :circle  "Circle"
   :path    "Path"
   :text    "Text"
   :image   "Image"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tempids
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def doc-tempid "doc")

(defn container-tempid
  [container-id]
  (str "c/" container-id))

(defn shape-tempid
  [container-id shape-id]
  (str "s/" container-id "/" shape-id))

(defn color-tempid [id] (str "col/" id))
(defn typography-tempid [id] (str "typ/" id))
(defn token-set-tempid [id] (str "ts/" id))
(defn token-tempid [id] (str "tk/" id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reference extraction
;;
;; These read exactly the attributes Penpot's own code reads: fill and
;; stroke color refs as `app.common.types.color/stroke->color` does,
;; typography refs from text content nodes as
;; `app.common.types.typography` does via `txt/node-seq`, swap slots via
;; `ctk/get-swap-slot`. None of the predicates is reimplemented.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fill-color-ref-ids
  "Library color ids referenced by a `:fills` vector."
  [fills]
  (into #{} (keep :fill-color-ref-id) fills))

(defn stroke-color-ref-ids
  "Library color ids referenced by a `:strokes` vector."
  [strokes]
  (into #{} (keep :stroke-color-ref-id) strokes))

(defn content-color-ref-ids
  "Library color ids referenced by fills inside text `content` nodes."
  [content]
  (into #{}
        (comp (mapcat :fills) (keep :fill-color-ref-id))
        (some-> content txt/node-seq)))

(defn content-typography-ref-ids
  "Typography ids referenced by text `content` nodes."
  [content]
  (into #{} (keep :typography-ref-id) (some-> content txt/node-seq)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Euler-tour numbering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolvable-parent?
  [objects shape]
  (let [parent-id (:parent-id shape)]
    (and (some? parent-id)
         (not= parent-id (:id shape))
         (contains? objects parent-id))))

(defn euler-numbering
  "Euler-tour intervals for one container's `:objects`, drawn from the
  global `counter`: a DFS from the container roots in stored child order,
  then a degenerate interval for any shape the tree cannot reach, so the
  whole map stays indexed. Returns [{shape-id [enter exit]} counter'].

  The counter is global across containers on purpose: per-container
  counters produce numerically overlapping ranges, and an interval query
  would silently match shapes of other pages."
  [objects counter]
  (let [roots (into []
                    (comp (filter #(not (resolvable-parent? objects %)))
                          (map :id))
                    (vals objects))]
    (loop [events  (into [] (map (fn [id] [:enter id])) roots)
           counter (long counter)
           acc     (transient {})]
      (if-let [[kind id] (peek events)]
        (let [events (pop events)]
          (case kind
            :enter
            (let [children (into []
                                 (filter #(contains? objects %))
                                 (get-in objects [id :shapes]))]
              (recur (-> events
                         (conj [:exit id])
                         (into (map (fn [c] [:enter c])) (rseq children)))
                     (inc counter)
                     (assoc! acc id [counter nil])))

            :exit
            (recur events
                   (inc counter)
                   (assoc! acc id (assoc (get acc id) 1 counter)))))
        (let [walked (persistent! acc)
              ;; degenerate intervals for orphans the tree cannot reach
              [walked counter]
              (reduce (fn [[m c] id]
                        (if (contains? m id)
                          [m c]
                          [(assoc m id [c (inc c)]) (+ c 2)]))
                      [walked counter]
                      (keys objects))]
          [walked counter])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; entity construction
;;
;; `build-ctx` carries the resolution state of one build: `:colors` and
;; `:typographies` map locally defined asset ids to entity references
;; (string tempids at build time, resolved eids on the sync path);
;; `:resolve-ref` answers a shape's `:shape-ref` with the reference of
;; the shape `ctf/find-ref-shape` names, or nil; `:numbering` maps a
;; shape id to its Euler interval within the current container.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn shape-asset-attrs
  "The asset-edge attributes of one shape, resolved through the ctx."
  [shape {:keys [colors typographies]}]
  (let [ref-vals (fn [m ids] (into [] (keep m) ids))
        fills    (ref-vals colors (fill-color-ref-ids (:fills shape)))
        strokes  (ref-vals colors (stroke-color-ref-ids (:strokes shape)))
        content  (when (= :text (:type shape)) (:content shape))
        text     (ref-vals colors (content-color-ref-ids content))
        typs     (ref-vals typographies (content-typography-ref-ids content))]
    (cond-> {}
      (seq fills)   (assoc :shape/fill-color fills)
      (seq strokes) (assoc :shape/stroke-color strokes)
      (seq text)    (assoc :shape/text-color text)
      (seq typs)    (assoc :shape/uses-typography typs))))

(defn shape-token-attrs
  "The folded applied-token attributes of one shape: one datom per applied
  property, value the applied token name, attribute from the closed
  vocabulary. Unresolvable names are stored too; the `uses-token` rule
  joins on `:token/name`, so they surface only when the token exists."
  [applied-tokens]
  (reduce-kv (fn [acc k v]
               (if-let [attr (token-attr k)]
                 (assoc acc attr v)
                 acc))
             {}
             (or applied-tokens {})))

(defn shape-attrs
  "The plain indexed attributes of one shape (no identity, no topology)."
  [shape]
  (let [swap-slot (ctk/get-swap-slot shape)]
    (cond-> (merge {:shape/type (:type shape)}
                   (shape-token-attrs (:applied-tokens shape)))
      (some? (:name shape))           (assoc :shape/name (:name shape))
      (some? (:component-id shape))   (assoc :shape/component-id (:component-id shape))
      (some? (:component-file shape)) (assoc :shape/component-file (:component-file shape))
      (some? (:shape-ref shape))      (assoc :shape/shape-ref (:shape-ref shape))
      (some? swap-slot)               (assoc :shape/swap-slot swap-slot))))

(defn shape-entity
  "Entity map for one shape of one container.

  `container-ref` is the container entity reference: the container tempid
  at build time, the resolved eid on the sync path. The parent reference
  falls back to it when the parent is absent from `objects` or
  self-referential, which covers the page root frame (its `:parent-id` is
  itself) and the root copy of a component container (its parent lives on
  a page)."
  [container-ref container-id objects shape {:keys [resolve-ref numbering] :as ctx}]
  (let [[enter exit] (get numbering (:id shape))
        ref          (when resolve-ref (resolve-ref shape))]
    (merge (cond-> {:db/id           (shape-tempid container-id (:id shape))
                    :shape/id        (:id shape)
                    :shape/container container-ref
                    :shape/parent    (if (resolvable-parent? objects shape)
                                       (shape-tempid container-id (:parent-id shape))
                                       container-ref)}
             (some? enter) (assoc :shape/enter enter :shape/exit exit)
             (some? ref)   (assoc :shape/refers-to ref))
           (shape-attrs shape)
           (shape-asset-attrs shape ctx))))

(defn container-entities
  "Entities for every shape of one container's `:objects` map.

  The whole map is indexed, not the tree walk from the root: a shape the
  tree cannot reach is a document defect the index should expose to
  queries rather than hide (the Ladybug projection walks and drops such
  shapes with a warning; this is a recorded divergence)."
  [container-ref container-id objects ctx]
  (into []
        (map (fn [shape]
               (shape-entity container-ref container-id objects shape ctx)))
        (vals objects)))

(defn component-entity
  [component]
  (cond-> {:db/id              (container-tempid (:id component))
           :component/id       (:id component)
           :component/document doc-tempid}
    (some? (:name component))
    (assoc :component/name (:name component))
    (:deleted component)
    (assoc :component/deleted true)
    (some? (:main-instance-id component))
    (assoc :component/main-instance-id (:main-instance-id component))
    (some? (:main-instance-page component))
    (assoc :component/main-instance-page (:main-instance-page component))))

(defn- component-tx
  "The component record entity, plus a container over its own `:objects`
  when it carries one (deleted components keep the main-instance subtree,
  `app.common.types.file/load-component-objects`)."
  [component ctx]
  (let [objects (:objects component)]
    (cond-> [(component-entity component)]
      (seq objects)
      (-> (conj {:db/id              (container-tempid (:id component))
                 :container/id       (:id component)
                 :container/kind     :component
                 :container/document doc-tempid})
          (into (container-entities (container-tempid (:id component))
                                    (:id component) objects ctx))))))

(defn- page-tx
  [page ctx]
  (into [(cond-> {:db/id              (container-tempid (:id page))
                  :container/id       (:id page)
                  :container/kind     :page
                  :container/document doc-tempid}
           (some? (:name page)) (assoc :container/name (:name page)))]
        (container-entities (container-tempid (:id page))
                            (:id page) (:objects page) ctx)))

(defn- tokens-tx
  "Token set and token entities from the file's tokens lib.

  Only identity, type and set membership: values, resolution and themes
  stay in the lib, which `app.common.types.tokens-lib` already answers."
  [tokens-lib]
  (into []
        (mapcat (fn [set*]
                  (let [set-id   (ctob/get-id set*)
                        set-name (ctob/get-name set*)]
                    (cons {:db/id              (token-set-tempid set-id)
                           :token-set/id       set-id
                           :token-set/name     set-name
                           :token-set/document doc-tempid}
                          (for [token (vals (ctob/get-tokens- set*))]
                            {:db/id      (token-tempid (:id token))
                             :token/id   (:id token)
                             :token/name (:name token)
                             :token/type (:type token)
                             :token/set  (token-set-tempid set-id)})))))
        (some-> tokens-lib ctob/get-sets)))

(defn- ref-resolver
  "The build-time `:resolve-ref`: `ctf/find-ref-shape` once per copy
  shape, against one ctn container, its answer minted as the target's
  build tempid. The subtle helper runs here, in the builder, never in a
  query; its fostered-children and swap fallbacks stay its own."
  [data file-id ctn-container]
  (let [file {:id file-id :data data}]
    (fn [shape]
      (when (:shape-ref shape)
        (when-let [ref (ctf/find-ref-shape file ctn-container {} shape
                                           :include-deleted? true
                                           :with-context? true)]
          (when-let [target-container-id (:id (:container (meta ref)))]
            (shape-tempid target-container-id (:id ref))))))))

(defn- build-asset-maps
  [data]
  {:colors       (into {} (map (fn [[id _]] [id (color-tempid id)]))
                       (:colors data))
   :typographies (into {} (map (fn [[id _]] [id (typography-tempid id)]))
                       (:typographies data))})

(defn build-tx
  "The full transaction that projects file `data` into an empty overlay.

  `file` supplies `:id` and `:name` when `data` does not carry them. The
  Euler counter threads globally across containers, components first,
  pages after, so every interval in the file is disjoint."
  [data file]
  (let [doc-id (or (:id data) (:id file))
        assets (build-asset-maps data)
        head   (-> [(cond-> {:db/id doc-tempid :document/id doc-id}
                      (some? (:name file)) (assoc :document/name (:name file)))]
                   (into (map (fn [[id color]]
                                (cond-> {:db/id (color-tempid id)
                                         :color/id id
                                         :color/document doc-tempid}
                                  (some? (:name color)) (assoc :color/name (:name color)))))
                         (:colors data))
                   (into (map (fn [[id typ]]
                                (cond-> {:db/id (typography-tempid id)
                                         :typography/id id
                                         :typography/document doc-tempid}
                                  (some? (:name typ)) (assoc :typography/name (:name typ)))))
                         (:typographies data))
                   (into (tokens-tx (:tokens-lib data))))
        [tx _counter]
        (as-> [head 0] state
          (reduce (fn [[tx counter] component]
                    (let [objects (:objects component)
                          [numbering counter]
                          (if (seq objects)
                            (euler-numbering objects counter)
                            [{} counter])
                          ctx (assoc assets
                                     :numbering numbering
                                     :resolve-ref
                                     (ref-resolver data doc-id
                                                   (ctn/make-container component :component)))]
                      [(into tx (component-tx component ctx)) counter]))
                  state
                  (vals (:components data)))
          (reduce (fn [[tx counter] page-id]
                    (if-let [page (get-in data [:pages-index page-id])]
                      (let [[numbering counter]
                            (euler-numbering (:objects page) counter)
                            ctx (assoc assets
                                       :numbering numbering
                                       :resolve-ref
                                       (ref-resolver data doc-id
                                                     (ctn/make-container page :page)))]
                        [(into tx (page-tx page ctx)) counter])
                      [tx counter]))
                  state
                  (:pages data)))]
    tx))

(defn build
  "Project file `data` into a fresh overlay. Pure: returns a datascript
  database value."
  ([data] (build data nil))
  ([data file]
   (d/db-with (d/empty-db schema) (build-tx data file))))

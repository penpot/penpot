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

  The namespace is deliberately JVM-free (no imports, no interop): the
  same code moves to the browser worker as a `.cljc` rename when the
  mirror experiment lands, which is the point of choosing datascript."
  (:require
   [app.common.types.text :as txt]
   [app.common.types.component :as ctk]
   [app.common.types.tokens-lib :as ctob]
   [datascript.core :as d]))

(def schema-version
  "penpot-graph-overlay-1")

(def schema
  "Only identity, type, topology, and the attributes a standing query has
  asked for. Heavy attributes (geometry, style payloads) stay in the
  document, which the holder of the overlay already has.

  Color references are stored per source (`fill`, `stroke`, text content)
  rather than as one union, because the sync path receives `:set` ops one
  attribute at a time and must be able to rebuild each contribution
  independently. The `uses-color` rule in `app.graph.overlay.queries`
  reunites them."
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
   :token/set          {:db/valueType :db.type/ref}
   ;; applied tokens: one entity per (shape, property, token), because an
   ;; edge with a payload needs a relation entity in a triple store
   :token-use/shape    {:db/valueType :db.type/ref}
   :token-use/token    {:db/valueType :db.type/ref}})

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
;; entity construction
;;
;; `asset-ctx` abstracts over build and sync: `:colors`, `:typographies`
;; map locally defined asset ids to entity references (string tempids at
;; build time, resolved eids at sync time), and `:tokens-by-name` maps a
;; token name to the references of every token carrying it. Only
;; resolvable references become edges, mirroring beadpot's `node_exists`
;; guard.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolvable-parent?
  [objects shape]
  (let [parent-id (:parent-id shape)]
    (and (some? parent-id)
         (not= parent-id (:id shape))
         (contains? objects parent-id))))

(defn shape-asset-attrs
  "The asset-edge attributes of one shape, resolved through `asset-ctx`."
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

(defn shape-attrs
  "The plain indexed attributes of one shape (no identity, no topology)."
  [shape]
  (let [swap-slot (ctk/get-swap-slot shape)]
    (cond-> {:shape/type (:type shape)}
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
  [container-ref container-id objects shape asset-ctx]
  (merge {:db/id           (shape-tempid container-id (:id shape))
          :shape/id        (:id shape)
          :shape/container container-ref
          :shape/parent    (if (resolvable-parent? objects shape)
                             (shape-tempid container-id (:parent-id shape))
                             container-ref)}
         (shape-attrs shape)
         (shape-asset-attrs shape asset-ctx)))

(defn token-use-entities
  "One (shape, property, token) entity per applied token that resolves.

  Token names are unique within a set but not across sets, so one applied
  name may match several tokens; every match links, exactly as beadpot's
  name join does (`beadpot.graph.transform.tokens/LinkAppliedTokens`)."
  [shape-ref applied-tokens tokens-by-name]
  (for [[prop token-name] applied-tokens
        token-ref (get tokens-by-name token-name)]
    {:token-use/shape shape-ref
     :token-use/token token-ref
     :token-use/prop  prop}))

(defn container-entities
  "Entities for every shape of one container's `:objects` map, plus the
  applied-token relation entities.

  The whole map is indexed, not the tree walk from the root: a shape the
  tree cannot reach is a document defect the index should expose to
  queries rather than hide (the Ladybug projection walks and drops such
  shapes with a warning; this is a recorded divergence)."
  [container-ref container-id objects asset-ctx]
  (into []
        (mapcat (fn [shape]
                  (cons (shape-entity container-ref container-id objects shape asset-ctx)
                        (token-use-entities (shape-tempid container-id (:id shape))
                                            (:applied-tokens shape)
                                            (:tokens-by-name asset-ctx)))))
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
  [component asset-ctx]
  (let [objects (:objects component)]
    (cond-> [(component-entity component)]
      (seq objects)
      (-> (conj {:db/id              (container-tempid (:id component))
                 :container/id       (:id component)
                 :container/kind     :component
                 :container/document doc-tempid})
          (into (container-entities (container-tempid (:id component))
                                    (:id component) objects asset-ctx))))))

(defn- page-tx
  [page asset-ctx]
  (into [(cond-> {:db/id              (container-tempid (:id page))
                  :container/id       (:id page)
                  :container/kind     :page
                  :container/document doc-tempid}
           (some? (:name page)) (assoc :container/name (:name page)))]
        (container-entities (container-tempid (:id page))
                            (:id page) (:objects page) asset-ctx)))

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

(defn- build-tokens-by-name
  "Token name -> tempids of every token carrying that name."
  [tokens-lib]
  (reduce (fn [acc set*]
            (reduce-kv (fn [acc _ token]
                         (update acc (:name token) (fnil conj [])
                                 (token-tempid (:id token))))
                       acc
                       (ctob/get-tokens- set*)))
          {}
          (some-> tokens-lib ctob/get-sets)))

(defn- build-asset-ctx
  [data]
  {:colors         (into {} (map (fn [[id _]] [id (color-tempid id)]))
                         (:colors data))
   :typographies   (into {} (map (fn [[id _]] [id (typography-tempid id)]))
                         (:typographies data))
   :tokens-by-name (build-tokens-by-name (:tokens-lib data))})

(defn build-tx
  "The full transaction that projects file `data` into an empty overlay.

  `file` supplies `:id` and `:name` when `data` does not carry them."
  [data file]
  (let [doc-id (or (:id data) (:id file))
        ctx    (build-asset-ctx data)]
    (-> [(cond-> {:db/id doc-tempid :document/id doc-id}
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
        (into (tokens-tx (:tokens-lib data)))
        (into (mapcat #(component-tx (val %) ctx)) (:components data))
        (into (mapcat #(page-tx (get-in data [:pages-index %]) ctx))
              (filter #(get-in data [:pages-index %]) (:pages data))))))

(defn build
  "Project file `data` into a fresh overlay. Pure: returns a datascript
  database value."
  ([data] (build data nil))
  ([data file]
   (d/db-with (d/empty-db schema) (build-tx data file))))

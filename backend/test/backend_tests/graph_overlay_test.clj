;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.graph-overlay-test
  "The semantic suite of the datascript graph overlay: the beadpot graph
  test suite (beadpot:tests/graph/transform) ported from Cypher to
  Datalog, one fixture file.

  Every deftest names the beadpot test it ports and the Penpot helper
  that is the correctness oracle. A backported assertion that disagrees
  with a helper is a beadpot bug: the helper's answer is asserted and the
  divergence is noted in the docstring. Beadpot's idempotency tests have
  no port here: `app.graph.overlay/build` is a pure function of the
  document, so linking twice cannot duplicate an edge by construction.

  The fixture is built exclusively with the common test helpers
  (`app.common.test-helpers.*`), so it stays a legal file
  (`thf/validate-file!` runs before the overlay is built)."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.queries :as queries]
   [clojure.test :as t]
   [datascript.core :as d]))

(t/use-fixtures :each thi/test-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the fixture file
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sample-fill
  "A fill carrying a library color reference."
  [color-id]
  {:fill-color "#000000"
   :fill-opacity 1
   :fill-color-ref-id color-id})

(defn- sample-stroke
  "A stroke carrying a library color reference."
  [color-id]
  {:stroke-style :solid
   :stroke-alignment :inner
   :stroke-width 1
   :stroke-color "#000000"
   :stroke-opacity 1
   :stroke-color-ref-id color-id})

(defn- refs-content
  "Text content whose text nodes carry `:typography-ref-id` and, when
  `color-id` is given, a fill referencing it. Only text nodes are touched:
  the content schema forbids these attributes on the root and
  paragraph-set nodes."
  [text typography-id color-id]
  (let [content (txt/change-text nil text)]
    (txt/transform-nodes
     txt/is-text-node?
     (fn [node]
       (cond-> (assoc node :typography-ref-id typography-id)
         (some? color-id)
         (assoc :fills [(sample-fill color-id)])))
     content)))

(defn- refs-text-shape
  "A text shape whose content nodes carry the given references."
  [typography-id color-id]
  (-> (cts/setup-shape {:type :text :x 0 :y 0})
      (assoc :content (refs-content "Typography sample" typography-id color-id)
             :position-data nil)))

(defn- document-containers
  "Every [container-id objects] pair the overlay indexes: the pages, plus
  the `:objects` snapshot a deleted component carries
  (`app.common.types.file/load-component-objects`)."
  [data]
  (concat (map (fn [page] [(:id page) (:objects page)])
               (vals (:pages-index data)))
          (keep (fn [[cid component]]
                  (when (seq (:objects component))
                    [cid (:objects component)]))
                (:components data))))

(defn- semantic-fixture
  "One rich file exercising every indexed attribute:

  - page 1 holds a board with three children, two text shapes whose
    content nodes reference a typography (one of them an undefined id),
    and a rect referencing an undefined colour;
  - :comp-1 (frame, child, grandchild) has a copy with labelled children;
  - :comp-2 is deleted through a raw :del-component change, so its record
    keeps the main-instance subtree in its own :objects;
  - :comp-a/:comp-b/:comp-c model a variant swap: the copy of :comp-c
    carries a nested instance of :comp-a which is swapped for :comp-b,
    seeding the swapped-in head with a swap slot;
  - two token sets hold four tokens; three are applied to shapes, one of
    them under two attributes, and one applied name the lib never defines;
  - page 2 holds one rect referencing a library colour.

  Returns {:file file :data data :db db}."
  []
  ;; Asset ids that are labelled but never added to the library: references
  ;; to them must produce no edge.
  (thi/new-id! :color-ghost)
  (thi/new-id! :typo-ghost)

  (let [file (-> (thf/sample-file :semantic-file :page-label :main-page)
                 (ths/add-sample-library-color :color-fill :name "Fill red" :color "#ff0000")
                 (ths/add-sample-library-color :color-stroke :name "Stroke blue" :color "#0000ff")
                 (ths/add-sample-library-color :color-unused :name "Unused green" :color "#00ff00")
                 (ths/add-sample-typography :typo-main :name "Main typography")
                 (ths/add-sample-typography :typo-unused :name "Unused typography")
                 (tho/add-frame :board :name "Board")
                 (ths/add-sample-shape :board-rect {:type :rect
                                                    :name "Board rect"
                                                    :parent-label :board
                                                    :fills [(sample-fill (thi/id :color-fill))]
                                                    :strokes [(sample-stroke (thi/id :color-stroke))]})
                 (ths/add-sample-shape :board-circle {:type :circle
                                                      :name "Board circle"
                                                      :parent-label :board
                                                      :fills [(sample-fill (thi/id :color-ghost))]})
                 (tho/add-text :board-text "Board label" :text-params {:parent-label :board})
                 (ths/add-sample-shape :typo-text (refs-text-shape (thi/id :typo-main) (thi/id :color-fill)))
                 (ths/add-sample-shape :ghost-typo-text (refs-text-shape (thi/id :typo-ghost) nil))
                 (ths/add-sample-shape :ghost-rect {:type :rect
                                                    :name "Ghost rect"
                                                    :fills [(sample-fill (thi/id :color-ghost))]})
                 (tho/add-frame :comp-1-root :name "C1 root")
                 (ths/add-sample-shape :comp-1-child {:type :rect
                                                      :name "C1 child"
                                                      :parent-label :comp-1-root})
                 (ths/add-sample-shape :comp-1-grandchild {:type :circle
                                                           :name "C1 grandchild"
                                                           :parent-label :comp-1-child})
                 (thc/make-component :comp-1 :comp-1-root)
                 (thc/instantiate-component :comp-1 :comp-1-copy
                                            :children-labels [:comp-1-copy-child :comp-1-copy-grandchild])
                 (tho/add-frame :comp-2-root :name "C2 root")
                 (ths/add-sample-shape :comp-2-child {:type :rect
                                                      :name "C2 child"
                                                      :parent-label :comp-2-root})
                 (thc/make-component :comp-2 :comp-2-root))]

    (let [file (thf/apply-changes file {:redo-changes [{:type :del-component
                                                        :id (thi/id :comp-2)}]})
          file (-> file
                   (tho/add-frame :comp-a-root :name "CA root")
                   (thc/make-component :comp-a :comp-a-root)
                   (tho/add-frame :comp-b-root :name "CB root")
                   (thc/make-component :comp-b :comp-b-root)
                   (tho/add-frame :comp-c-root :name "CC root")
                   (thc/instantiate-component :comp-a :nested-a :parent-label :comp-c-root)
                   (thc/make-component :comp-c :comp-c-root)
                   (thc/instantiate-component :comp-c :comp-c-copy :children-labels [:nested-a-copy])
                   (thc/component-swap :nested-a-copy :comp-b :swapped-b))

          file (-> file
                   (tht/add-tokens-lib)
                   (tht/update-tokens-lib
                    (fn [lib]
                      (-> lib
                          (ctob/add-set (ctob/make-token-set :id (thi/new-id! :token-set-a)
                                                             :name "core"))
                          (ctob/add-set (ctob/make-token-set :id (thi/new-id! :token-set-b)
                                                             :name "layout"))
                          (ctob/add-token (thi/id :token-set-a)
                                          (ctob/make-token :id (thi/new-id! :token-color-fill)
                                                           :name "color.fill"
                                                           :type :color
                                                           :value "#112233"))
                          (ctob/add-token (thi/id :token-set-a)
                                          (ctob/make-token :id (thi/new-id! :token-color-stroke)
                                                           :name "color.stroke"
                                                           :type :color
                                                           :value "#445566"))
                          (ctob/add-token (thi/id :token-set-b)
                                          (ctob/make-token :id (thi/new-id! :token-size)
                                                           :name "size.dim"
                                                           :type :dimensions
                                                           :value 100))
                          (ctob/add-token (thi/id :token-set-b)
                                          (ctob/make-token :id (thi/new-id! :token-spacing)
                                                           :name "spacing.gap"
                                                           :type :spacing
                                                           :value 8))))))

          file (-> file
                   ;; one token applied under two attributes: two uses
                   (tht/apply-token-to-shape :board-rect "size.dim" [:width :height] [:width :height] 100)
                   ;; one token applied by two shapes: two uses
                   (tht/apply-token-to-shape :board-rect "color.fill" [:fill] [:fill] "#112233")
                   (tht/apply-token-to-shape :board-circle "color.fill" [:fill] [:fill] "#112233")
                   ;; an applied name the lib never defines: no use
                   (tht/apply-token-to-shape :ghost-rect "ghost.token" [:fill] [:fill] "#000000"))

          file (-> file
                   (thf/add-sample-page :second-page :name "Second page")
                   (ths/add-sample-shape :page2-rect {:type :rect
                                                      :name "P2 rect"
                                                      :fills [(sample-fill (thi/id :color-fill))]}))]

      (thf/validate-file! file)
      {:file file
       :data (:data file)
       :db   (overlay/build (:data file) file)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest component-inventory-matches-the-component-record-map
  ;; beadpot:tests/graph/transform/test_components.py::test_component_nodes_created
  ;; Oracle: the `:components` map of the data, and `ctkl/get-component`
  ;; include-deleted behaviour. The overlay indexes every record, deleted
  ;; ones included; the Ladybug projection drops deleted components, so the
  ;; two counts diverge on this fixture by design.
  (let [{:keys [data db]} (semantic-fixture)
        records    (:components data)
        deleted-id (thi/id :comp-2)
        deleted    (ctkl/get-component data deleted-id true)]

    (t/is (= (count records) (:components (queries/stats db))))
    (t/is (= (count (ctkl/components-seq data))
             (- (:components (queries/stats db))
                (count (ctkl/deleted-components-seq data)))))

    ;; the deleted record is an entity flagged :component/deleted
    (t/is (true? (:deleted deleted)))
    (t/is (true? (:component/deleted (d/entity db (d/entid db [:component/id deleted-id])))))

    ;; the oracle: include-deleted? controls visibility; the overlay keeps it
    (t/is (nil? (ctkl/get-component data deleted-id)))
    (t/is (some? (ctkl/get-component data deleted-id true)))

    ;; the :del-component change snapshotted the main-instance subtree into
    ;; the record's own :objects, so the overlay has a component container
    ;; over that copy
    (t/is (seq (:objects deleted)))
    (let [container-eid (queries/container-eid db deleted-id)
          snapshot-ids  (d/q '[:find [?s ...]
                               :in $ ?id
                               :where
                               [?s :shape/container ?c]
                               [?c :container/id ?id]]
                             db deleted-id)]
      (t/is (= :component (:container/kind (d/entity db container-eid))))
      (t/is (= (count (:objects deleted)) (count snapshot-ids)))
      (doseq [sid (keys (:objects deleted))]
        (t/is (some? (queries/shape-eid db container-eid sid))
              (str "snapshot shape " sid))))

    ;; the inventory the rest of the suite leans on, pinned once:
    ;; 22 page shapes + 2 root frames + 2 snapshot shapes = 26 shape
    ;; entities; 2 pages + the deleted component's snapshot = 3 containers
    (t/is (= {:documents    1
              :containers   3
              :shapes       26
              :components   5
              :colors       3
              :typographies 2
              :token-sets   2
              :tokens       4
              :token-uses   4}
             (dissoc (queries/stats db) :datoms)))))

(t/deftest components-are-children-of-the-document
  ;; beadpot:tests/graph/transform/test_components.py::test_component_nodes_are_children_of_document
  ;; Oracle: the document entity of the overlay. Every component record,
  ;; deleted ones included, is a child of the one document entity.
  (let [{:keys [data db]} (semantic-fixture)
        doc-eid  (d/entid db [:document/id (:id data)])
        attached (d/q '[:find ?c ?d
                        :where
                        [?c :component/document ?d]]
                      db)]
    (t/is (= (count (:components data)) (count attached)))
    (t/is (every? #(= doc-eid (second %)) attached))))

(t/deftest refers-to-pairs-match-shape-refs
  ;; beadpot:tests/graph/transform/test_components.py::test_refers_to_edges_match_shape_refs
  ;; One RefersTo per resolvable shape-ref, each pointing at the referenced
  ;; shape. Oracle: `ctk/is-main-of?` on the page objects. The rule itself
  ;; is unscoped; the page scoping here mirrors the Ladybug projection,
  ;; which materialises page shapes only.
  (let [{:keys [data db]} (semantic-fixture)
        pages    (vals (:pages-index data))
        pairs    (d/q '[:find ?sid ?tid
                        :in $ %
                        :where
                        (on-page ?s)
                        (refers-to ?s ?t)
                        (on-page ?t)
                        [?s :shape/id ?sid]
                        [?t :shape/id ?tid]]
                      db queries/rules)
        shapes   (mapcat #(vals (:objects %)) pages)
        refs     (filter :shape-ref shapes)
        on-page? (fn [id] (some #(contains? (:objects %) id) pages))]

    ;; every on-page shape with a resolvable :shape-ref has an outgoing pair
    (t/is (= (set (map :id (filter #(on-page? (:shape-ref %)) refs)))
             (set (map first pairs))))

    ;; every pair is a main link in the oracle's reading, and the target id
    ;; equals the source's :shape-ref
    (doseq [[sid tid] pairs]
      (let [objects (some (fn [p] (when (contains? (:objects p) sid) (:objects p)))
                          pages)
            source  (get objects sid)
            target  (get objects tid)]
        (t/is (some? source))
        (t/is (ctk/is-main-of? target source))))

    ;; all six copy shapes on the fixture resolve
    (t/is (= 6 (count pairs)))))

(t/deftest instance-heads-link-only-to-live-components
  ;; beadpot:tests/graph/transform/test_components.py::test_all_instance_heads_linked_to_their_component
  ;; Oracle: `ctk/instance-of?`. The is-instance-of rule scoped to page
  ;; shapes must equal a scan of the predicate over the live component
  ;; records. The deleted component contributes nothing, even though its
  ;; orphaned main root still passes the raw predicate.
  (let [{:keys [data db]} (semantic-fixture)
        live-comps (ctkl/components-seq data)
        rule-pairs (set (d/q '[:find ?cid ?sid
                               :in $ %
                               :where
                               (on-page ?s)
                               (is-instance-of ?s ?c)
                               [?c :component/id ?cid]
                               [?s :shape/id ?sid]]
                             db queries/rules))
        expected   (set (for [component live-comps
                              [pid page] (:pages-index data)
                              [sid shape] (:objects page)
                              :when (ctk/instance-of? shape (:id data) (:id component))]
                          [(:id component) sid]))
        deleted-id (thi/id :comp-2)]

    (t/is (= expected rule-pairs))
    (t/is (= 8 (count rule-pairs)))

    ;; no link to the deleted component
    (t/is (empty? (d/q '[:find [?sid ...]
                         :in $ % ?cid
                         :where
                         (is-instance-of ?s ?c)
                         [?c :component/id ?cid]
                         [?s :shape/id ?sid]]
                       db queries/rules deleted-id)))

    ;; but its orphaned main root still passes the raw predicate
    (let [main-root (get-in data [:pages-index (thi/id :main-page)
                                  :objects (thi/id :comp-2-root)])]
      (t/is (some? main-root))
      (t/is (ctk/instance-of? main-root (:id data) deleted-id)))))

(t/deftest main-instance-frames-are-among-the-linked-heads
  ;; beadpot:tests/graph/transform/test_components.py::test_main_instances_are_linked
  ;; Oracle: the component records' :main-instance-page/:main-instance-id.
  ;; One main instance per live component, and each of those frames is an
  ;; instance head linked through `queries/instances-of`.
  (let [{:keys [data db]} (semantic-fixture)
        triples (queries/component-main-instances db)
        live    (ctkl/components-seq data)]

    (t/is (= (set (map :id (filter #(and (:main-instance-id %)
                                         (:main-instance-page %))
                                   live)))
             (set (map first triples))))
    (t/is (= (count live) (count triples)))
    (doseq [[cid pid mid] triples]
      (t/is (contains? (set (queries/instances-of db cid (:id data)))
                       [pid mid])))))

(t/deftest parent-heads-match-the-container-walk
  ;; No beadpot counterpart: this is the overlay's own pin that its parent
  ;; topology equals the document's. Oracle: `ctn/get-parent-heads` over the
  ;; page objects, order included (top-down), for every shape on every page.
  (let [{:keys [data db]} (semantic-fixture)]
    (doseq [[pid page] (:pages-index data)
            [sid shape] (:objects page)]
      (let [expected (mapv :id (ctn/get-parent-heads (:objects page) shape))
            actual   (queries/parent-heads db pid sid)]
        (t/is (= expected actual)
              (str "parent heads of " sid))))

    ;; a copy's descendant: the copy root is its only head
    (t/is (= [(thi/id :comp-1-copy)]
             (queries/parent-heads db (thi/id :main-page)
                                   (thi/id :comp-1-copy-grandchild))))

    ;; a nested instance: two heads, outer first
    (t/is (= [(thi/id :comp-c-root) (thi/id :nested-a)]
             (queries/parent-heads db (thi/id :main-page) (thi/id :nested-a))))))

(t/deftest instances-of-matches-an-instance-of-scan
  ;; beadpot:tests/graph/transform/test_components.py::test_all_instance_heads_linked_to_their_component
  ;; (the exhaustive complement: the query against the scan)
  ;; Oracle: `ctk/instance-of?` over every page's objects. The query is the
  ;; raw index lookup: it does not check whether the component record is
  ;; live, which is the is-instance-of rule's business, so the scan runs
  ;; over every component record, deleted ones included.
  (let [{:keys [data db]} (semantic-fixture)]
    (doseq [component (vals (:components data))]
      (let [expected (set (for [[pid page] (:pages-index data)
                                [sid shape] (:objects page)
                                :when (ctk/instance-of? shape (:id data) (:id component))]
                            [pid sid]))]
        (t/is (= expected (set (queries/instances-of db (:id component) (:id data))))
              (str "instances of " (:name component)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; descendants and identity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest descendants-and-shape-identity-match-the-document
  ;; beadpot:tests/graph/transform/test_denormalization.py, the page_id and
  ;; component_id denormalization suite, mapped onto the overlay's identity
  ;; claim: shape ids are not unique, the (container, shape) pair is, so
  ;; every page object, the uuid/zero root frame of every page included,
  ;; must resolve through `queries/shape-eid`. The overlay does NOT
  ;; denormalize component-id downward (beadpot and Ladybug do): the
  ;; [?a :shape/component-id _] datom must equal `ctk/instance-head?`
  ;; exactly, or the is-instance-of rule stops answering `ctk/instance-of?`.
  ;; Oracle: `cfh/get-children-ids` and `ctk/instance-head?`.
  (let [{:keys [data db]} (semantic-fixture)
        page1-id (thi/id :main-page)
        page1    (get-in data [:pages-index page1-id])
        board-id (thi/id :board)
        objects  (:objects page1)]

    ;; descendants: the overlay formulation of cfh/get-children-ids
    (t/is (= (set (cfh/get-children-ids objects board-id))
             (queries/descendant-ids db page1-id board-id)))
    (t/is (= (set (filter #(= :rect (:type (get objects %)))
                          (cfh/get-children-ids objects board-id)))
             (queries/descendant-ids-of-type db page1-id board-id :rect)))
    (t/is (= (set (filter #(= :text (:type (get objects %)))
                          (cfh/get-children-ids objects board-id)))
             (queries/descendant-ids-of-type db page1-id board-id :text)))
    (t/is (= #{} (queries/descendant-ids db page1-id (thi/id :board-rect))))

    ;; identity: every object of every page resolves, uuid/zero included
    (doseq [[pid page] (:pages-index data)
            [sid _shape] (:objects page)]
      (let [container-eid (queries/container-eid db pid)
            shape-eid     (queries/shape-eid db container-eid sid)]
        (t/is (some? shape-eid)
              (str "shape " sid " of page " pid))))

    ;; uuid/zero keys the root frame of every page: two distinct entities
    (let [p1-root (queries/shape-eid db (queries/container-eid db page1-id)
                                     uuid/zero)
          p2-root (queries/shape-eid db (queries/container-eid db (thi/id :second-page))
                                     uuid/zero)]
      (t/is (some? p1-root))
      (t/is (some? p2-root))
      (t/is (not= p1-root p2-root))
      (t/is (= 2 (count (queries/shape-eids db uuid/zero)))))

    ;; no downward denormalization: the overlay stores component-id iff the
    ;; helper sees a head
    (doseq [[pid page] (:pages-index data)
            [sid shape] (:objects page)]
      (let [entity (d/entity db (queries/shape-eid db (queries/container-eid db pid)
                                                   sid))]
        (t/is (= (boolean (ctk/instance-head? shape))
                 (boolean (some? (:shape/component-id entity))))
              (str "component-id denormalization on " sid))
        (when (:component-id shape)
          (t/is (= (:component-id shape) (:shape/component-id entity))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; swap slots
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest fills-swap-slot-matches-swap-slot-entries
  ;; beadpot:tests/graph/transform/test_variants.py::test_fills_swap_slot_count_matches_fixture
  ;; and ::test_fills_swap_slot_id_matches_touched_entry
  ;; Oracle: `ctk/get-swap-slot` over the document. The swapped-in nested
  ;; copy root carries the replaced shape's id as its swap slot; the overlay
  ;; surfaces exactly one FillsSwapSlot per document swap-slot entry whose
  ;; target resolves. beadpot's test_swap_slot_entries_stripped_from_touched
  ;; is vacuous here: the overlay never stores :touched, so there is nothing
  ;; to strip.
  (let [{:keys [data db]} (semantic-fixture)
        pairs (d/q '[:find ?sid ?sslot ?tid
                     :in $ %
                     :where
                     (on-page ?s)
                     (fills-swap-slot ?s ?t)
                     (on-page ?t)
                     [?s :shape/id ?sid]
                     [?s :shape/swap-slot ?sslot]
                     [?t :shape/id ?tid]]
                   db queries/rules)
        slots (for [[_cid objects] (document-containers data)
                    shape (vals objects)
                    :let [slot (ctk/get-swap-slot shape)]
                    :when (some? slot)]
                [(:id shape) slot])]

    ;; the count matches the swap-slot entries the helper finds
    (t/is (= (count slots) (count pairs)))

    ;; one swapped-in head, one slot, one edge
    (t/is (= 1 (count pairs)))

    (doseq [[sid sslot tid] pairs]
      ;; the slot names the replaced shape: source and target differ, and
      ;; the target is a shape on a page
      (t/is (= sslot tid))
      (t/is (not= sid tid))
      (t/is (= (thi/id :swapped-b) sid))
      (t/is (= (thi/id :nested-a) tid)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tokens
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest token-inventory-matches-the-tokens-lib
  ;; beadpot:tests/graph/transform/test_tokens.py::test_graph_round_trip_preserves_token_inventory
  ;; and ::test_graph_all_tokens_present
  ;; Oracle: the tokens lib itself. The overlay stores identity, type and
  ;; set membership; values and themes stay in the lib.
  (let [{:keys [data db]} (semantic-fixture)
        lib         (:tokens-lib data)
        tokens      (ctob/get-all-tokens lib)
        graph-names (set (d/q '[:find [?n ...]
                                :where
                                [?t :token/name ?n]]
                              db))
        graph-types (into {} (d/q '[:find ?type (count ?t)
                                    :where
                                    [?t :token/type ?type]]
                                  db))]

    (t/is (= (set (map :name tokens)) graph-names))
    (t/is (= (frequencies (map :type tokens)) graph-types))
    (t/is (= (count (ctob/get-sets lib)) (:token-sets (queries/stats db))))
    (t/is (= 4 (:tokens (queries/stats db))))

    ;; token -> set membership, per the lib's own getters
    (doseq [set* (ctob/get-sets lib)
            token (vals (ctob/get-tokens- set*))]
      (let [set-eid (d/entid db [:token-set/id (ctob/get-id set*)])
            tok-eid (d/entid db [:token/id (:id token)])]
        (t/is (some? tok-eid))
        (t/is (= set-eid (:db/id (:token/set (d/entity db tok-eid))))
              (str "set of token " (:name token)))
        (t/is (= (ctob/get-name set*)
                 (:token-set/name (d/entity db set-eid))))))))

(t/deftest applied-tokens-produce-exactly-the-resolvable-uses
  ;; beadpot:tests/graph/transform/test_tokens.py::test_link_applied_tokens_creates_exact_edge_set
  ;; plus ::test_shared_token_has_multiple_incoming_edges and
  ;; ::test_same_token_two_attributes_yields_two_edges
  ;; Oracle: a scan of every container's :applied-tokens, restricted to
  ;; names the lib defines; the overlay's token-use entities must equal it.
  ;; beadpot's enum-key parsing test is a document-format concern that
  ;; Clojure keywords make moot.
  (let [{:keys [data db]} (semantic-fixture)
        lib-names (into #{} (map :name) (ctob/get-all-tokens (:tokens-lib data)))
        expected  (set (for [[cid objects] (document-containers data)
                             shape (vals objects)
                             [prop name] (:applied-tokens shape)
                             :when (contains? lib-names name)]
                         [cid (:id shape) prop]))
        actual    (set (mapcat #(queries/shapes-using-token db %) lib-names))]

    (t/is (= expected actual))
    (t/is (= 4 (:token-uses (queries/stats db))))

    ;; a token applied by two shapes has two uses
    (let [shared (queries/shapes-using-token db "color.fill")]
      (t/is (= #{(thi/id :board-rect) (thi/id :board-circle)}
               (set (map second shared)))))

    ;; one shape applying one token under two attributes yields two triples
    (let [two-attrs (queries/shapes-using-token db "size.dim")]
      (t/is (= 2 (count two-attrs)))
      (t/is (= 1 (count (set (map second two-attrs)))))
      (t/is (= #{:width :height} (set (map #(nth % 2) two-attrs)))))

    ;; a name the lib never defines produces no edge
    (t/is (empty? (queries/shapes-using-token db "ghost.token")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; colors and typographies
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest used-colors-and-typographies-match-the-raw-refs
  ;; beadpot's LinkUsedColors and LinkUsedTypographies transforms, verified
  ;; against the raw shape maps. Oracle: the shapes whose fills, strokes, or
  ;; text-content fills carry the color's ref id, and the shapes whose text
  ;; content carries the typography's ref id, read straight off the maps.
  ;; Unresolvable refs (id not in the library) produce no edge, mirroring
  ;; beadpot's node_exists guard.
  (let [{:keys [data db]} (semantic-fixture)
        containers      (document-containers data)
        fill-refs       (fn [fills] (into #{} (keep :fill-color-ref-id) fills))
        stroke-refs     (fn [strokes] (into #{} (keep :stroke-color-ref-id) strokes))
        node-fill-refs  (fn [content]
                          (into #{} (comp (mapcat :fills) (keep :fill-color-ref-id))
                                (txt/node-seq content)))
        node-typo-refs  (fn [content]
                          (into #{} (keep :typography-ref-id) (txt/node-seq content)))
        color-users     (fn [color-id]
                          (set (for [[cid objects] containers
                                     shape (vals objects)
                                     :when (or (contains? (fill-refs (:fills shape)) color-id)
                                               (contains? (stroke-refs (:strokes shape)) color-id)
                                               (contains? (node-fill-refs (:content shape)) color-id))]
                                 [cid (:id shape)])))
        typo-users      (fn [typo-id]
                          (set (for [[cid objects] containers
                                     shape (vals objects)
                                     :when (contains? (node-typo-refs (:content shape)) typo-id)]
                                 [cid (:id shape)])))]

    (doseq [color-id (keys (:colors data))]
      (t/is (= (color-users color-id)
               (set (queries/shapes-using-color db color-id)))
            (str "users of color " color-id)))

    (doseq [typo-id (keys (:typographies data))]
      (t/is (= (typo-users typo-id)
               (set (queries/shapes-using-typography db typo-id)))
            (str "users of typography " typo-id)))

    ;; pinned membership: fill, text-content fill, and cross-page use
    (t/is (= #{[(thi/id :main-page) (thi/id :board-rect)]
               [(thi/id :main-page) (thi/id :typo-text)]
               [(thi/id :second-page) (thi/id :page2-rect)]}
             (set (queries/shapes-using-color db (thi/id :color-fill)))))
    (t/is (= #{[(thi/id :main-page) (thi/id :board-rect)]}
             (set (queries/shapes-using-color db (thi/id :color-stroke)))))
    (t/is (= #{[(thi/id :main-page) (thi/id :typo-text)]}
             (set (queries/shapes-using-typography db (thi/id :typo-main)))))
    (t/is (empty? (queries/shapes-using-typography db (thi/id :typo-unused))))

    ;; unresolvable refs produce no edge
    (t/is (empty? (queries/shapes-using-color db (thi/id :color-ghost))))
    (t/is (empty? (queries/shapes-using-typography db (thi/id :typo-ghost))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; parity counting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest ladybug-parity-counts-pin-the-fixture
  ;; Hand-computed expectations under the Ladybug projection's conventions,
  ;; so the parity fn itself is pinned: page shapes minus root frames,
  ;; pages, and live components. 22 page shapes + 2 pages + 4 live
  ;; components = 28 IsChildOf; 8 on-page instance heads against live
  ;; components; 6 on-page resolvable shape-refs; 1 on-page swap slot.
  (let [{:keys [data db]} (semantic-fixture)
        pages       (vals (:pages-index data))
        page-shapes (remove #(= uuid/zero (:id %))
                            (mapcat #(vals (:objects %)) pages))
        live-comps  (ctkl/components-seq data)
        on-page?    (fn [id] (some #(contains? (:objects %) id) pages))
        live-head?  (fn [shape]
                      (some (fn [component]
                              (ctk/instance-of? shape (:id data) (:id component)))
                            live-comps))
        derived     {:is-child-of     (+ (count page-shapes)
                                         (count pages)
                                         (count live-comps))
                     :is-instance-of  (count (filter live-head? page-shapes))
                     :refers-to       (count (filter #(and (:shape-ref %)
                                                           (on-page? (:shape-ref %)))
                                                     page-shapes))
                     :fills-swap-slot (count (filter #(let [slot (ctk/get-swap-slot %)]
                                                        (and slot (on-page? slot)))
                                                     page-shapes))}]

    (t/is (= derived (queries/ladybug-parity-counts db)))
    (t/is (= {:is-child-of     28
              :is-instance-of  8
              :refers-to       6
              :fills-swap-slot 1}
             (queries/ladybug-parity-counts db)))))

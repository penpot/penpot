;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.graph-overlay-sync-test
  "Cold build and incremental sync are two implementations of one mapping,
  and this namespace holds them to it.

  `app.graph.overlay/build` projects a whole file into a fresh overlay.
  `app.graph.overlay.sync/apply-changes` replays the change vocabulary the
  editor emits onto an overlay that is already open. The overlay the second
  one maintains must equal the overlay the first one would build from the
  changed document, or the console shows a graph no rebuild reproduces.

  The round trip: build the fixture file into A, apply a change list to A
  and the same list to the file data, rebuild the result into B, and
  compare A against B in an entity-id-independent normal form.
  `app.common.files.changes/process-changes` is the document oracle. No
  Ladybug, no Postgres, no session: two datascript database values and
  pure functions."
  (:require
   [app.common.features :as ffeat]
   [app.common.files.changes :as cp]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.file :as ctf]
   [app.common.types.library :as ctl]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.queries :as queries]
   [app.graph.overlay.sync :as sync]
   [clojure.set :as set]
   [clojure.test :as t]
   [datascript.core :as d]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the fixture file
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fixed ids: a failure should read the same on every run.
(def ^:private file-id      #uuid "00000000-0000-0000-0000-00000000f11e")
(def ^:private page-id      #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private page2-id     #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private frame-id     #uuid "00000000-0000-0000-0000-0000000000f1")
(def ^:private rect-id      #uuid "00000000-0000-0000-0000-0000000000b1")
(def ^:private circ-id      #uuid "00000000-0000-0000-0000-0000000000b2")
(def ^:private text-id      #uuid "00000000-0000-0000-0000-0000000000b3")
(def ^:private rect2-id     #uuid "00000000-0000-0000-0000-0000000000b4")
(def ^:private subchild-id  #uuid "00000000-0000-0000-0000-0000000000b5")
(def ^:private p2-shape-id  #uuid "00000000-0000-0000-0000-0000000000b6")
(def ^:private comp-root-id  #uuid "00000000-0000-0000-0000-0000000000c1")
(def ^:private comp-child-id #uuid "00000000-0000-0000-0000-0000000000c2")
(def ^:private copy-root-id  #uuid "00000000-0000-0000-0000-0000000000c3")
(def ^:private copy-child-id #uuid "00000000-0000-0000-0000-0000000000c4")
(def ^:private comp-id       #uuid "00000000-0000-0000-0000-0000000000e1")
(def ^:private color-id      #uuid "00000000-0000-0000-0000-0000000000d1")
(def ^:private token-set-id  #uuid "00000000-0000-0000-0000-0000000000d2")
(def ^:private token-id      #uuid "00000000-0000-0000-0000-0000000000d3")
(def ^:private swap-slot-id  #uuid "00000000-0000-0000-0000-000000005571")

(defn- shape
  "A valid shape for an `:add-obj` payload, from
  `app.common.test-helpers.shapes/sample-shape`: `process-change :add-obj`
  hard-validates on the JVM (`app.common.files.changes/validate-shape`),
  and hand-rolled maps do not survive it."
  [id type & {:as attrs}]
  (ths/sample-shape nil (assoc attrs :id id :type type)))

(defn- base-data
  "The untouched document the change list mutates. The library colour and
  the token live here, not in the change list, because the sync path does
  not support `:add-color` or `:set-token*` changes: both paths must see
  them from the start for the asset links to resolve."
  []
  (binding [ffeat/*current* #{"components/v2"}]
    (-> (ctf/make-file-data file-id page-id)
        (ctl/add-color {:id color-id :name "Fixture green"
                        :color "#00FF00" :opacity 1})
        (assoc :tokens-lib
               (-> (ctob/make-tokens-lib)
                   (ctob/add-set
                    (ctob/make-token-set
                     :id token-set-id
                     :name "Brand"
                     :tokens {"brand.primary" (ctob/make-token
                                               :id token-id
                                               :name "brand.primary"
                                               :type :color
                                               :value "#FF00FF")})))))))

(def ^:private swap-slot-kw
  (keyword (str "swap-slot-" swap-slot-id)))

(def ^:private changes
  "One change of every type the sync path claims to support, in the order
  an editing session would emit them, and every indexed attribute at least
  once: topology (add, reparent, delete a subtree), the `:mod-obj` set ops
  with their asset refs (`app.graph.overlay.sync/set-op-tx`), a touched
  swap slot on a shape inside a copy (`ctk/get-swap-slot`), a copy
  child's `:shape-ref` repointed at another main shape (the resolved
  `:shape/refers-to` must follow), and the whole component lifecycle from
  add to purge."
  [{:type :add-obj :page-id page-id :id frame-id
    :parent-id uuid/zero :frame-id uuid/zero
    :obj (shape frame-id :frame {:name "Board" :width 400 :height 300})}

   {:type :add-obj :page-id page-id :id rect-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape rect-id :rect {:name "Rect" :parent-id frame-id :frame-id frame-id
                               :width 100 :height 50})}

   {:type :add-obj :page-id page-id :id circ-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape circ-id :circle {:name "Circle" :parent-id frame-id :frame-id frame-id
                                 :width 40 :height 40})}

   ;; nested inside circ: the subtree :del-obj removes along with it
   {:type :add-obj :page-id page-id :id subchild-id
    :parent-id circ-id :frame-id frame-id
    :obj (shape subchild-id :rect {:name "Sub child" :parent-id circ-id :frame-id frame-id
                                   :width 10 :height 10})}

   {:type :add-obj :page-id page-id :id text-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape text-id :text {:name "Label" :parent-id frame-id :frame-id frame-id})}

   {:type :add-obj :page-id page-id :id rect2-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape rect2-id :rect {:name "Rect two" :parent-id frame-id :frame-id frame-id
                                :width 20 :height 20})}

   ;; a rename, plus two attributes whose values are falsy: `blocked false`
   ;; and `opacity 0` are values, not absences, on both paths
   {:type :mod-obj :page-id page-id :id rect-id
    :operations [{:type :set :attr :name :val "Renamed rect"}
                 {:type :set :attr :blocked :val false}
                 {:type :set :attr :opacity :val 0}]}

   ;; a fill carrying a library colour ref: the overlay links it
   {:type :mod-obj :page-id page-id :id rect2-id
    :operations [{:type :set :attr :fills
                  :val [(assoc (ths/sample-fill-color :fill-color "#ABCDEF" :fill-opacity 1)
                               :fill-color-ref-id color-id)]}]}

   ;; an applied token: one folded application (shape, property, name)
   {:type :mod-obj :page-id page-id :id rect2-id
    :operations [{:type :set :attr :applied-tokens
                  :val {:fill "brand.primary"}
                  :ignore-touched true}]}

   ;; reorder inside the same container: the overlay stores no sibling
   ;; order, so this must be invisible to it (and therefore is NOT the
   ;; :mov-objects the injected-bug test drops)
   {:type :mov-objects :page-id page-id :parent-id frame-id :index 0 :shapes [circ-id]}

   ;; reparent to the page's root frame: the parent edge must move
   {:type :mov-objects :page-id page-id :parent-id uuid/zero :index 0 :shapes [text-id]}

   ;; delete with survivors: circ and its subtree go, the frame's other
   ;; children stay
   {:type :del-obj :page-id page-id :id circ-id}

   {:type :add-page :id page2-id :name "Page two"}

   {:type :add-obj :page-id page2-id :id p2-shape-id
    :parent-id uuid/zero :frame-id uuid/zero
    :obj (shape p2-shape-id :rect {:name "Page two shape" :width 50 :height 50})}

   {:type :mod-page :id page-id :name "Page one, renamed"}

   ;; the deleted page carries shapes: the whole container must go
   {:type :del-page :id page2-id}

   ;; the main instance: root and child land on the page first, exactly
   ;; as the editor's change builder emits them (`pcb/add-component`)
   {:type :add-obj :page-id page-id :id comp-root-id
    :parent-id uuid/zero :frame-id uuid/zero
    :obj (shape comp-root-id :frame {:name "Main" :width 200 :height 200})}

   {:type :add-obj :page-id page-id :id comp-child-id
    :parent-id comp-root-id :frame-id comp-root-id
    :obj (shape comp-child-id :rect {:name "Main child" :parent-id comp-root-id
                                     :frame-id comp-root-id :width 60 :height 40})}

   {:type :add-component :id comp-id :name "Fixture component" :path "Fixture component"
    :main-instance-id comp-root-id :main-instance-page page-id}

   ;; the component membership ops the editor emits next to :add-component
   {:type :mod-obj :page-id page-id :id comp-root-id
    :operations [{:type :set :attr :component-id :val comp-id}
                 {:type :set :attr :component-file :val nil}
                 {:type :set :attr :component-root :val true}
                 {:type :set :attr :main-instance :val true}
                 {:type :set :attr :shape-ref :val nil}]}

   {:type :mod-obj :page-id page-id :id comp-child-id
    :operations [{:type :set :attr :component-id :val comp-id}
                 {:type :set :attr :main-instance :val false}]}

   ;; a copy of the component on the same page: `shape-ref` marks the
   ;; homologue, which is what makes `ctk/in-component-copy?` true. The
   ;; copy root carries `:component-file` like `ctn/make-component-instance`
   ;; sets it (the local library id), which is what lets
   ;; `ctf/find-ref-shape` resolve it at build time.
   {:type :add-obj :page-id page-id :id copy-root-id
    :parent-id uuid/zero :frame-id uuid/zero
    :obj (shape copy-root-id :frame {:name "Copy" :parent-id uuid/zero :frame-id uuid/zero
                                     :width 200 :height 200
                                     :shape-ref comp-root-id
                                     :component-id comp-id
                                     :component-file file-id
                                     :component-root true})}

   {:type :add-obj :page-id page-id :id copy-child-id
    :parent-id copy-root-id :frame-id copy-root-id
    :obj (shape copy-child-id :rect {:name "Copy child" :parent-id copy-root-id
                                     :frame-id copy-root-id :width 60 :height 40
                                     :shape-ref comp-child-id
                                     :component-id comp-id})}

   ;; repoint a copy child at a different main shape: the resolved
   ;; reference must follow on both paths (`:shape/refers-to` re-resolves
   ;; through `ctf/find-ref-shape`'s sync mirror)
   {:type :mod-obj :page-id page-id :id copy-child-id
    :operations [{:type :set :attr :shape-ref :val comp-root-id}]}

   ;; a swap slot on a shape inside the copy: only the slot is indexed,
   ;; and only because `ctk/get-swap-slot` extracts it
   {:type :mod-obj :page-id page-id :id copy-root-id
    :operations [{:type :set-touched :touched #{swap-slot-kw}}]}

   {:type :mod-component :id comp-id :name "Fixture component, renamed"}

   ;; soft delete: the component keeps the main-instance subtree as its
   ;; own container copy (`ctf/load-component-objects`), in the rebuilt
   ;; overlay and the synced one alike
   {:type :del-component :id comp-id}

   {:type :restore-component :id comp-id :page-id page-id}

   {:type :del-component :id comp-id}

   {:type :purge-component :id comp-id}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the normal form
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Entity ids are datascript internals that depend on insertion order, so
;; a parity check cannot compare them. The normal form keys every entity
;; by what identifies it in the document and replaces every reference
;; value with the target's key; cardinality-many refs become sets of
;; keys. The result is a set of [entity-key attr-map] pairs, and two
;; overlays are equal exactly when their normal forms are `=`.

(defn- entity-key
  "The document key of one entity: `[:document id]`, `[:container id]`,
  `[:component id]`, `[:shape container-id shape-id]`, `[:color id]`,
  `[:typography id]`, `[:token-set id]`, or `[:token id]`. Token-use
  entities are gone: the folded encoding stores applied tokens as shape
  attributes."
  [db eid]
  (let [ent (d/entity db eid)]
    (cond
      (some? (:document/id ent))
      [:document (:document/id ent)]

      ;; a component entity may also carry a container role; the
      ;; component key wins
      (some? (:component/id ent))
      [:component (:component/id ent)]

      (some? (:container/id ent))
      [:container (:container/id ent)]

      (some? (:shape/id ent))
      [:shape (:container/id (:shape/container ent)) (:shape/id ent)]

      (some? (:color/id ent))
      [:color (:color/id ent)]

      (some? (:typography/id ent))
      [:typography (:typography/id ent)]

      (some? (:token-set/id ent))
      [:token-set (:token-set/id ent)]

      (some? (:token/id ent))
      [:token (:token/id ent)])))

(defn- ref-key
  [keymap ref]
  (when-let [eid (:db/id ref)]
    (get keymap eid)))

(defn- ref-keys
  [keymap refs]
  (into #{} (map #(ref-key keymap %)) refs))

(defn- attr-map
  "The indexed attributes of one entity with refs replaced by keys.
  Identity attributes carried by the key itself are omitted. Attributes
  the entity does not carry appear as nil, so a presence difference
  between the two paths shows up instead of hiding."
  [keymap ent]
  (cond
    (some? (:document/id ent))
    {:document/id   (:document/id ent)
     :document/name (:document/name ent)}

    (some? (:component/id ent))
    {:component/id                 (:component/id ent)
     :component/name               (:component/name ent)
     :component/deleted            (:component/deleted ent)
     :component/main-instance-id   (:component/main-instance-id ent)
     :component/main-instance-page (:component/main-instance-page ent)
     :component/document           (ref-key keymap (:component/document ent))
     :container/id                 (:container/id ent)
     :container/kind               (:container/kind ent)
     :container/name               (:container/name ent)
     :container/document           (ref-key keymap (:container/document ent))}

    (some? (:container/id ent))
    {:container/id       (:container/id ent)
     :container/kind     (:container/kind ent)
     :container/name     (:container/name ent)
     :container/document (ref-key keymap (:container/document ent))}

    (some? (:shape/id ent))
    (merge {:shape/type            (:shape/type ent)
            :shape/name            (:shape/name ent)
            :shape/component-id    (:shape/component-id ent)
            :shape/component-file  (:shape/component-file ent)
            :shape/shape-ref       (:shape/shape-ref ent)
            :shape/refers-to       (ref-key keymap (:shape/refers-to ent))
            :shape/swap-slot       (:shape/swap-slot ent)
            :shape/parent          (ref-key keymap (:shape/parent ent))
            :shape/fill-color      (ref-keys keymap (:shape/fill-color ent))
            :shape/stroke-color    (ref-keys keymap (:shape/stroke-color ent))
            :shape/text-color      (ref-keys keymap (:shape/text-color ent))
            :shape/uses-typography (ref-keys keymap (:shape/uses-typography ent))}
           ;; folded token attributes: plain strings, presence-checked
           (into {} (map (fn [a] [a (get ent a)])) overlay/token-attrs))
    ;; Euler intervals deliberately excluded: the builder and the sync
    ;; renumber draw them from different DFS orders, so the values are
    ;; not comparable — interval containment is the invariant, pinned by
    ;; the-intervals-agree-after-the-full-replay (presence is checked
    ;; there)

    (some? (:color/id ent))
    {:color/id       (:color/id ent)
     :color/name     (:color/name ent)
     :color/document (ref-key keymap (:color/document ent))}

    (some? (:typography/id ent))
    {:typography/id       (:typography/id ent)
     :typography/name     (:typography/name ent)
     :typography/document (ref-key keymap (:typography/document ent))}

    (some? (:token-set/id ent))
    {:token-set/id       (:token-set/id ent)
     :token-set/name     (:token-set/name ent)
     :token-set/document (ref-key keymap (:token-set/document ent))}

    (some? (:token/id ent))
    {:token/id   (:token/id ent)
     :token/name (:token/name ent)
     :token/type (:token/type ent)
     :token/set  (ref-key keymap (:token/set ent))}))
(defn- normal-form
  "The overlay as entity-id-independent data: a set of
  [entity-key attr-map] pairs."
  [db]
  (let [keymap (into {}
                     (map (fn [eid] [eid (entity-key db eid)]))
                     (distinct (map :e (d/datoms db :eavt))))]
    (into #{} (map (fn [[eid key]] [key (attr-map keymap (d/entity db eid))]))
          keymap)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the round trip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- round-trip
  "Sync `change-list` into A, rebuild the changed document into B, return
  the normal-form difference and the apply-changes report."
  [change-list]
  (let [data0   (base-data)
        data1   (cp/process-changes data0 change-list false)
        result  (sync/apply-changes (overlay/build data0) change-list)
        synced  (normal-form (:db result))
        rebuilt (normal-form (overlay/build data1))]
    {:diff    (set/union (set/difference synced rebuilt)
                         (set/difference rebuilt synced))
     :applied (:applied result)
     :skipped (:skipped result)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest the-fixture-exercises-every-supported-change-type
  (t/is (= sync/supported-change-types (set (map :type changes)))))

(t/deftest every-change-in-the-list-is-applied
  (let [{:keys [applied skipped]} (round-trip changes)]
    (t/is (empty? skipped)
          (str "the fixture must exercise the sync path, not the skip path: "
               (pr-str skipped)))
    (t/is (= (map :type changes) applied))))

(t/deftest synced-graph-equals-rebuilt-graph
  (let [{:keys [diff]} (round-trip changes)]
    (t/is (empty? diff)
          (str "synced and rebuilt overlays disagree on " (pr-str diff)))))

(t/deftest every-prefix-stays-in-sync
  ;; The end state can agree while a middle state does not (an ordering
  ;; bug). Replay every prefix of the list and hold both paths equal at
  ;; every step.
  (let [data0 (base-data)]
    (doseq [n (range (inc (count changes)))]
      (let [prefix  (subvec changes 0 n)
            result  (sync/apply-changes (overlay/build data0) prefix)
            synced  (normal-form (:db result))
            rebuilt (normal-form (overlay/build (cp/process-changes data0 prefix false)))]
        (t/is (and (empty? (:skipped result))
                   (= synced rebuilt))
              (str "prefix of length " n " diverges; skipped "
                   (pr-str (:skipped result))))))))

(t/deftest soft-delete-keeps-the-component-container-copy
  ;; `ctf/delete-component` without `skip-undelete?` stores the
  ;; main-instance subtree on the component (`ctf/load-component-objects`);
  ;; the overlay mirrors it as a component container, on both paths. The
  ;; copies' builder-resolved references must move with it: after
  ;; normalisation the synced and rebuilt forms agree on
  ;; `:shape/refers-to`, and both point at the snapshot copies.
  (let [first-del (first (keep-indexed
                          (fn [i ch] (when (= :del-component (:type ch)) i))
                          changes))
        prefix    (subvec changes 0 (inc first-del))
        data0     (base-data)
        result    (sync/apply-changes (overlay/build data0) prefix)
        rebuilt   (overlay/build (cp/process-changes data0 prefix false))
        synced-m  (into {} (normal-form (:db result)))
        rebuilt-m (into {} (normal-form rebuilt))]
    (doseq [form [(normal-form (:db result)) (normal-form rebuilt)]]
      (let [keys (into #{} (map first) form)]
        (t/is (contains? keys [:shape comp-id comp-root-id])
              "the component container must hold the main-instance root copy")
        (t/is (contains? keys [:shape comp-id comp-child-id])
              "the component container must hold the main-instance child copy")
        (t/is (contains? form
                         [[:component comp-id]
                          {:component/id                 comp-id
                           :component/name               "Fixture component, renamed"
                           :component/deleted            true
                           :component/main-instance-id   comp-root-id
                           :component/main-instance-page page-id
                           :component/document           [:document file-id]
                           :container/id                 comp-id
                           :container/kind               :component
                           :container/name               nil
                           :container/document           [:document file-id]}]))))

    ;; the copies' `:shape/refers-to` equals the rebuild's, pointing at
    ;; the snapshot copies (the repointed copy child resolves to the
    ;; root copy, its :shape-ref was set to comp-root-id)
    (t/is (= (get-in rebuilt-m [[:shape page-id copy-root-id] :shape/refers-to])
             (get-in synced-m [[:shape page-id copy-root-id] :shape/refers-to])))
    (t/is (= [:shape comp-id comp-root-id]
             (get-in synced-m [[:shape page-id copy-root-id] :shape/refers-to])))
    (t/is (= [:shape comp-id comp-root-id]
             (get-in synced-m [[:shape page-id copy-child-id] :shape/refers-to])))))

(t/deftest the-diff-catches-an-injected-sync-bug
  ;; The round trip is only worth running if it fails when sync is wrong.
  ;; Drop the reparenting :mov-objects from the list the overlay sees,
  ;; keep it in the list the document sees, and the moved shape's entity
  ;; key must appear in the difference: the check is locatable, not just
  ;; boolean. Euler intervals are out of the normal form, so it is the
  ;; parent ref (the moved shape's `:shape/parent` key) that names it.
  (let [data0     (base-data)
        data1     (cp/process-changes data0 changes false)
        crippled  (remove #(and (= :mov-objects (:type %))
                                (some #{text-id} (:shapes %)))
                          changes)
        synced    (normal-form (:db (sync/apply-changes (overlay/build data0) crippled)))
        rebuilt   (normal-form (overlay/build data1))
        diff      (set/union (set/difference synced rebuilt)
                             (set/difference rebuilt synced))
        moved     (into #{} (map first) diff)]
    (t/is (contains? moved [:shape page-id text-id])
          "a sync that skips a reparent must name the moved shape's key")))

(t/deftest the-intervals-agree-after-the-full-replay
  ;; The Euler-tour invariants hold on the synced db, not only on the
  ;; built one. The sync path renumbers a container from beyond the
  ;; global maximum with a different DFS order than the builder
  ;; (`app.graph.overlay.sync/renumber-container-tx`), so interval
  ;; values are not comparable between the two paths — containment is.
  ;; Here: every shape of every container carries an interval, the
  ;; interval form answers the recursive walk exactly, and intervals are
  ;; disjoint-or-nested file-wide (a partial overlap is the signature of
  ;; a per-container counter).
  (let [db         (-> (base-data) (overlay/build) (sync/apply-changes changes) :db)
        containers (d/q '[:find [?c ...] :where [?c :container/id _]] db)]

    ;; presence: every shape of every container carries a fresh interval
    (doseq [seid (d/q '[:find [?s ...] :where [?s :shape/container _]] db)]
      (let [e (d/entity db seid)]
        (t/is (and (some? (:shape/enter e)) (some? (:shape/exit e)))
              (str "shape " (pr-str (:shape/id e)) " lost its interval"))))

    ;; containment: the interval form answers the recursive walk, per
    ;; container, per shape
    (doseq [ceid containers
            :let [cid (:container/id (d/entity db ceid))]
            seid (d/q '[:find [?s ...] :in $ ?c
                        :where [?s :shape/container ?c]]
                      db ceid)
            :let [sid (:shape/id (d/entity db seid))]]
      (t/is (= (queries/descendant-ids db cid sid)
               (queries/descendant-ids-walk db cid sid))
            (str "interval vs walk in container " cid " shape " sid)))

    ;; the global-counter claim across all containers
    (let [enters    (into {} (map (juxt :e :v)) (d/datoms db :avet :shape/enter))
          exits     (into {} (map (juxt :e :v)) (d/datoms db :avet :shape/exit))
          intervals (mapv (fn [[eid enter]] [enter (get exits eid)]) enters)
          ok?       (fn [[e0 e1] [f0 f1]]
                      (or (<= e1 f0) (<= f1 e0)
                          (and (< e0 f0) (< f1 e1))
                          (and (< f0 e0) (< e1 f1))))]
      (doseq [i (range (count intervals))
              j (range (inc i) (count intervals))]
        (t/is (ok? (nth intervals i) (nth intervals j))
              (str "intervals " (pr-str (nth intervals i)) " and "
                   (pr-str (nth intervals j)) " partially overlap"))))))

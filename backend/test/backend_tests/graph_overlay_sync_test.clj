;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.graph-overlay-sync-test
  "Cold build and incremental sync are two implementations of one mapping,
  and this namespace holds them to it.

  `app.graph.relation-overlay/build` projects a whole file into a fresh overlay.
  `app.graph.relation-overlay.sync/apply-changes` replays the change vocabulary the
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
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.types.library :as ctl]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.graph.debug :as graph.debug]
   [app.graph.relation-overlay :as overlay]
   [app.graph.relation-overlay.queries :as queries]
   [app.graph.relation-overlay.sync :as sync]
   [clojure.set :as set]
   [clojure.string :as str]
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
(def ^:private color2-id     #uuid "00000000-0000-0000-0000-0000000000d4")
(def ^:private typ-id        #uuid "00000000-0000-0000-0000-0000000000d5")
(def ^:private token-set2-id #uuid "00000000-0000-0000-0000-0000000000d6")
(def ^:private token2-id     #uuid "00000000-0000-0000-0000-0000000000d7")
(def ^:private token3-id     #uuid "00000000-0000-0000-0000-0000000000d8")

(defn- shape
  "A valid shape for an `:add-obj` payload, from
  `app.common.test-helpers.shapes/sample-shape`: `process-change :add-obj`
  hard-validates on the JVM (`app.common.files.changes/validate-shape`),
  and hand-rolled maps do not survive it."
  [id type & {:as attrs}]
  (ths/sample-shape nil (assoc attrs :id id :type type)))

(defn- base-data
  "The untouched document the change list mutates. It carries one library
  colour and one token set from the start, because the early `:mod-obj`
  rows name them: an asset has to exist before the operation that
  references it, on both paths. The change list adds a second colour, a
  typography and a second token set of its own."
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

(def ^:private typography
  (ctt/make-typography {:id typ-id :name "Fixture heading"}))

(def ^:private text-content
  "A text tree naming the typography, in the shape `txt/node-seq` walks."
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text "Label"
                                       :typography-ref-id typ-id
                                       :typography-ref-file file-id}]}]}]})

(defn- replacement-lib
  "The library a `:set-tokens-lib` change carries: a different set with a
  different token, so the replacement is visible in every entity."
  []
  (-> (ctob/make-tokens-lib)
      (ctob/add-set
       (ctob/make-token-set
        :id token-set2-id
        :name "Imported"
        :tokens {"imported.radius" (ctob/make-token
                                    :id token2-id
                                    :name "imported.radius"
                                    :type :border-radius
                                    :value "4")}))))

(def ^:private swap-slot-kw
  (keyword (str "swap-slot-" swap-slot-id)))

(def ^:private changes
  "One change of every type the sync path claims to support, in the order
  an editing session would emit them, and every indexed attribute at least
  once: topology (add, reparent, reorder, delete a subtree), the
  `:mod-obj` set ops
  with their asset refs (`app.graph.relation-overlay.sync/set-attr-tx`), a touched
  swap slot on a shape inside a copy (`ctk/get-swap-slot`), a copy
  child's `:shape-ref` repointed at another main shape (the resolved
  `:shape/refers-to` must follow), the whole component lifecycle from add
  to purge, and then the library tiers: colour, typography and the six
  token changes, each with the reference that makes its skip visible."
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

   ;; reorder inside the same container: the parent edge does not move and
   ;; the sibling order does, which is now an indexed fact (and therefore
   ;; is NOT the :mov-objects the injected-bug test drops)
   {:type :mov-objects :page-id page-id :parent-id frame-id :index 0 :shapes [circ-id]}

   ;; reparent to the page's root frame: the parent edge must move
   {:type :mov-objects :page-id page-id :parent-id uuid/zero :index 0 :shapes [text-id]}

   ;; the same arm placed by `:after-shape` instead of an index: the
   ;; document turns it into an index against the destination's current
   ;; list, so the fold reads it the same way
   {:type :mov-objects :page-id page-id :parent-id frame-id
    :after-shape rect2-id :shapes [rect-id]}

   ;; a partial reorder, the shape `process-children-reordering` takes:
   ;; the frame's children are sorted by the index each named id holds
   ;; here, and rect-id, which the change does not name, keeps the -1 key
   ;; and stays first. The sibling ordinals must follow that sort
   ;; (`app.graph.relation-overlay.sync/apply-reorder-children`).
   {:type :reorder-children :page-id page-id :parent-id frame-id
    :shapes [circ-id rect2-id]}

   ;; delete with survivors: circ and its subtree go, the frame's other
   ;; children stay and close the gap circ left
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

   ;; an `:assign` operation, the one the applier has to decode itself:
   ;; `process-operation :assign` decodes the map against the shape's own
   ;; `:type` and replays it as one `:set` per changed attribute, so a
   ;; head attribute arriving this way must move the resolution too
   {:type :mod-obj :page-id page-id :id copy-child-id
    :operations [{:type :assign :value {:name "Copy child, assigned"
                                        :shape-ref comp-child-id
                                        :component-file file-id}}]}

   ;; `:set-remote-synced` on a shape inside a copy: the classification
   ;; table calls it a no-op for this tier, and the round trip is what
   ;; proves it, because no overlay attribute carries `:remote-synced`
   {:type :mod-obj :page-id page-id :id copy-child-id
    :operations [{:type :set-remote-synced :remote-synced true}]}

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

   {:type :purge-component :id comp-id}

   ;; ---- library colours, the largest unsupported class in the stream
   ;; measured by pp:vcs:the-real-change-stream-is-attribute-shaped

   ;; a fill on rect naming a colour the library does not hold yet: the raw
   ;; id lands in the index and no reference does, because a rebuild
   ;; resolves a reference only for the ids the library carries. The add
   ;; below has to link this shape, which is the repair the round closes.
   ;; The editor emits the asset first, so this order arrives from a save
   ;; that carries only the use, with the asset in an earlier save.
   {:type :mod-obj :page-id page-id :id rect-id
    :operations [{:type :set :attr :fills
                  :val [(assoc (ths/sample-fill-color :fill-color "#0000FF" :fill-opacity 1)
                               :fill-color-ref-id color2-id)]}]}

   {:type :add-color :color {:id color2-id :name "Fixture blue"
                             :color "#0000FF" :opacity 1}}

   ;; a stroke naming the colour added one change earlier: the editor's
   ;; order, which resolves at the operation and needs no repair
   {:type :mod-obj :page-id page-id :id rect2-id
    :operations [{:type :set :attr :strokes
                  :val [{:stroke-color "#0000FF" :stroke-opacity 1
                         :stroke-width 1 :stroke-style :solid
                         :stroke-alignment :center
                         :stroke-color-ref-id color2-id
                         :stroke-color-ref-file file-id}]}]}

   ;; `ctl/set-color` replaces the colour whole, so the name is replaced
   {:type :mod-color :color {:id color2-id :name "Fixture blue, renamed"
                             :color "#0000EE" :opacity 1}}

   ;; the worst single skip of the lot: `ctl/delete-color` leaves every
   ;; referring shape alone, so a rebuild drops the colour entity *and*
   ;; rect2's `:shape/fill-color` datom, and a fold that only retracted
   ;; the entity would keep the ref
   {:type :del-color :id color-id}

   ;; ---- library typographies, the same three shapes over one ref

   ;; the same shape as the colour above: a text operation names a
   ;; typography the library does not hold, and the add repairs the ref
   {:type :mod-obj :page-id page-id :id text-id
    :operations [{:type :set :attr :content :val text-content}]}

   {:type :add-typography :typography typography}

   {:type :mod-obj :page-id page-id :id text-id
    :operations [{:type :set :attr :content :val text-content}]}

   ;; `ctyl/update-typography` merges, so a payload without a name would
   ;; keep the old one; this one carries a name
   {:type :mod-typography :typography {:id typ-id :name "Fixture heading, renamed"}}

   {:type :del-typography :id typ-id}

   ;; ---- design tokens

   {:type :set-token-set :id token-set2-id
    :attrs {:id token-set2-id :name "Semantic"}}

   {:type :set-token :set-id token-set2-id :token-id token2-id
    :attrs {:id token2-id :name "semantic.bg" :type :color :value "#123456"}}

   ;; the merge branch: `:type` is absent from the payload and survives
   {:type :set-token :set-id token-set2-id :token-id token2-id
    :attrs {:name "semantic.background"}}

   ;; the renamed token is the one rect2 applies, so the `uses-token`
   ;; join has to answer the same on both paths afterwards
   {:type :set-token :set-id token-set-id :token-id token-id
    :attrs {:name "brand.primary.500"}}

   ;; a set's name is its path, so a move is a rename
   {:type :move-token-set :from-path ["Semantic"] :to-path ["Group" "Semantic"]
    :before-path nil :before-group nil}

   {:type :rename-token-set-group :set-group-path ["Group"] :set-group-fname "Grouped"}

   {:type :move-token-set-group :from-path ["Grouped"] :to-path ["Nested" "Grouped"]
    :before-path nil :before-group nil}

   ;; the two delete branches: a token by id, then the set that held it
   {:type :set-token :set-id token-set2-id :token-id token2-id :attrs nil}

   {:type :set-token-set :id token-set2-id :attrs nil}

   ;; the whole library replaced, which is what an import emits
   {:type :set-tokens-lib :tokens-lib (replacement-lib)}])

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
            ;; the sibling ordinal is authored and comparable between the
            ;; two paths, unlike the intervals below, so it is compared
            :shape/order           (:shape/order ent)
            :shape/fill-color      (ref-keys keymap (:shape/fill-color ent))
            :shape/stroke-color    (ref-keys keymap (:shape/stroke-color ent))
            :shape/text-color      (ref-keys keymap (:shape/text-color ent))
            :shape/uses-typography (ref-keys keymap (:shape/uses-typography ent))
            ;; the raw ids beside the references, as sets: the extractors
            ;; return sets, so the stored order is not the document's
            :shape/fill-color-ref-id   (into #{} (:shape/fill-color-ref-id ent))
            :shape/stroke-color-ref-id (into #{} (:shape/stroke-color-ref-id ent))
            :shape/text-color-ref-id   (into #{} (:shape/text-color-ref-id ent))
            :shape/typography-ref-id   (into #{} (:shape/typography-ref-id ent))}
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
;; the classification table
;;
;; One row per member of the change vocabulary, so a type Penpot adds
;; upstream fails this namespace by name instead of being skipped in
;; silence. The criterion is the datom rather than the document: a change
;; is `:no-op` when a rebuild after it produces the datoms the fold
;; already holds, `:covered` when the applier reproduces the difference,
;; and `:partial` when it reproduces a named subset of it.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private change-coverage
  {;; shapes and containers
   :add-obj     {:coverage :covered
                 :why "the shape entity, its parent edge, its indexed attributes and its container's fresh intervals"}
   :mod-obj     {:coverage :covered
                 :why "every operation the vocabulary defines, over the attributes the overlay indexes"}
   :del-obj     {:coverage :covered
                 :why "the shape and its subtree, and Penpot's one change per selected shape is idempotent here"}
   :mov-objects {:coverage :covered
                 :why "the parent edge and the child list, the container renumber and the re-resolution of the moved subtrees, for the moves `app.common.files.changes/valid-move?` accepts; a change the gate refuses moves none of its shapes, which is the document's own answer and rewrites no datom here"}
   :reorder-children {:coverage :covered
                      :why "the sibling ordinals, from the parent's own list sorted as `process-children-reordering` sorts it: a partial order over the children, the unnamed ones first, and a parent inside a component copy left alone unless the change allows altering copies"}
   :add-page    {:coverage :covered
                 :why "a container entity and every shape the page carries"}
   :del-page    {:coverage :covered
                 :why "the container and its shapes"}
   :mod-page    {:coverage :covered
                 :why "the only page attribute the overlay indexes is the name, and the mirror writes it"}

   ;; components
   :add-component     {:coverage :covered :why "the component record"}
   :mod-component     {:coverage :covered
                       :why "the record, and the component's own container copy when the change carries objects"}
   :del-component     {:coverage :covered
                       :why "the deleted flag and the main-instance snapshot a soft delete stores"}
   :restore-component {:coverage :covered
                       :why "the undelete, the dropped copy and the re-resolution back to the page"}
   :purge-component   {:coverage :covered :why "the record and its container copy"}

   ;; library assets
   :add-color      {:coverage :covered
                    :why "the colour entity, and the reference of every shape that named the id before the library held it, read from the stored raw id"}
   :mod-color      {:coverage :covered
                    :why "the stored name, replaced whole as `ctl/set-color` replaces the colour"}
   :del-color      {:coverage :covered
                    :why "the colour entity and every resolved reference to it"}
   :add-typography {:coverage :covered
                    :why "the typography entity, with the same repair of the shapes that named the id first"}
   :mod-typography {:coverage :covered
                    :why "the stored name, merged as `ctyl/update-typography` merges"}
   :del-typography {:coverage :covered
                    :why "the typography entity and every reference to it"}

   ;; design tokens
   :set-tokens-lib         {:coverage :covered
                            :why "every token-set and token entity, rewritten from the incoming library"}
   :set-token              {:coverage :covered
                            :why "add, rename, displace and delete; the identity is the payload's own `:id`, which `changes_builder.cljc::set-token` builds from the token it names, and a taken name is one retraction"}
   :set-token-set          {:coverage :covered
                            :why "add, total replacement, displace and delete, with the set's tokens reconciled; a taken name retracts the set standing there, with its tokens"}
   :rename-token-set-group {:coverage :partial
                            :why "the prefix rewrite over every set under the group; a new name held by a set outside the group refuses"}
   :move-token-set         {:coverage :partial
                            :why "the rename, and the reorder that changes no stored datom; a destination name already held refuses"}
   :move-token-set-group   {:coverage :partial
                            :why "the prefix rewrite and the reorder; a destination that already holds sets refuses, because the move drops them from the library"}

   ;; the residue: nothing these write reaches a stored datom
   :fix-obj                     {:coverage :no-op :why "it repairs a parent's `:shapes` vector, which the overlay does not store"}
   :reg-objects                 {:coverage :no-op :why "it writes geometry, which stays in the document"}
   :mov-page                    {:coverage :no-op :why "it reorders the page list, and the overlay stores no page order"}
   :set-plugin-data             {:coverage :no-op :why "plugin data is not indexed"}
   :set-comment-thread-position {:coverage :no-op :why "comment thread positions are not indexed"}
   :set-guide                   {:coverage :no-op :why "guides are not indexed"}
   :set-flow                    {:coverage :no-op :why "flows are not indexed"}
   :set-default-grid            {:coverage :no-op :why "default grids are not indexed"}
   :add-media                   {:coverage :no-op :why "media lives in the document and the overlay holds no media entity"}
   :mod-media                   {:coverage :no-op :why "the same reason as an add's"}
   :del-media                   {:coverage :no-op :why "the same reason as an add's"}
   :set-token-theme             {:coverage :no-op :why "themes are not indexed"}
   :set-tokens-status           {:coverage :no-op :why "the active theme and set status is not indexed"}
   :set-base-font-size          {:coverage :no-op :why "file options are not indexed"}
   :set-tokens-source           {:coverage :no-op :why "the builder reads the file's own `:tokens-lib` whatever the source, so pointing the file at a library writes no datom"}})

(def ^:private operation-coverage
  "One row per member of `app.common.files.changes/schema:operation`, the
  second filter the change-type count does not show."
  {:set               {:coverage :covered
                       :why "the attributes the overlay indexes, and nil for the rest by design"}
   :assign            {:coverage :covered
                       :why "decoded against the shape's stored `:shape/type` and replayed as one `:set` per attribute"}
   :set-touched       {:coverage :covered
                       :why "only the swap slot `ctk/get-swap-slot` extracts is indexed, and the mirror writes it"}
   :set-remote-synced {:coverage :no-op
                       :why "it writes `:remote-synced`, and no overlay attribute carries that name; the Ladybug shape tables do carry the column"}})

(def ^:private ladybug-only-gaps
  "The change types that corrupt the Ladybug mirror, because
  `app.graph.schema.nodes` gives that projection a column for every key of
  the Penpot schema: the nine this tier still skips, plus
  `:reorder-children`, which this tier covers and that mirror still drops
  (the `IsChildOf` edge position and the container's `shapes` column keep
  the old order). They are the `graph-worker` branch's work, not this
  one's, and this set exists so the two lists cannot drift apart in
  silence."
  #{:reorder-children :fix-obj :reg-objects :mov-page :set-plugin-data
    :set-comment-thread-position :set-guide :set-flow :set-default-grid
    :set-base-font-size})

(defn- types-with
  [table & coverages]
  (into #{}
        (keep (fn [[type* row]]
                (when (contains? (set coverages) (:coverage row)) type*)))
        table))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the attribute vocabulary
;;
;; The third filter, after the change types and the operations: the
;; attributes a shape entity carries. They do not have one writer each. An
;; operation writes `:shape/name` and its neighbours through
;; `app.graph.relation-overlay.sync/set-attr-tx`, a structural pass writes
;; `:shape/refers-to` (`reresolve-refs-tx`) and the sibling ordinal
;; (`order-tx`, which is also how the builder draws it), and the builder
;; writes the identity and topology edges and `:shape/type`. The comparison
;; asks whether every attribute `app.graph.relation-overlay/schema` declares over
;; shape entities, plus the folded token vocabulary, has a writer, and
;; names its kind. An attribute nothing can write fails by name rather than passing
;; as derived, which is the failure mode the operation filter had before
;; the table closed it: the batch reports applied, the fold holds no datom
;; for the attribute, and a rebuild holds one.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private writer-kinds
  "The three places an attribute a shape carries comes from."
  #{:operation :pass :builder})

(def ^:private shape-attr-writers
  "Every attribute a shape entity carries, and where it is written: an
  `:operation` (the `:set` attribute that carries it), a structural
  `:pass`, or the `:builder` alone. The folded token attributes ride both
  the builder and the `:applied-tokens` operation, and the sibling ordinal
  rides the builder and the structural pass that maintains it."
  (merge
   {;; written by an operation, through `set-attr-tx`
    :shape/name                [[:operation :name]]
    :shape/component-id        [[:operation :component-id]]
    :shape/component-file      [[:operation :component-file]]
    :shape/shape-ref           [[:operation :shape-ref]]
    :shape/swap-slot           [[:operation :touched]]
    :shape/fill-color          [[:operation :fills]]
    :shape/stroke-color        [[:operation :strokes]]
    :shape/text-color          [[:operation :content]]
    :shape/uses-typography     [[:operation :content]]
    :shape/fill-color-ref-id   [[:operation :fills]]
    :shape/stroke-color-ref-id [[:operation :strokes]]
    :shape/text-color-ref-id   [[:operation :content]]
    :shape/typography-ref-id   [[:operation :content]]}
   ;; written by a structural pass, never by one operation
   {:shape/refers-to [[:pass "reresolve-refs-tx"]]
    :shape/order     [[:pass "order-tx"] [:builder "shape-entity"]]}
   ;; written by the builder alone
   {:shape/id        [[:builder "shape-entity"]]
    :shape/container [[:builder "shape-entity"]]
    :shape/parent    [[:builder "shape-entity"]]
    :shape/type      [[:builder "shape-attrs"]]}
   ;; the folded token vocabulary, from `app.common.types.token/all-keys`
   (into {}
         (map (fn [attr] [attr [[:builder "shape-token-attrs"]
                                [:operation :applied-tokens]]]))
         overlay/token-attrs)))

(def ^:private excluded-shape-attrs
  "The declared shape attributes the comparison does not compare, with the
  reason, as the normal form excludes the Euler intervals.
  `renumber-container-tx` writes both intervals on the fold and the
  builder writes them on the rebuild, but the two draw their numbers from
  different DFS orders, so the values are not comparable and only
  containment is pinned (`the-intervals-agree-after-the-full-replay`)."
  {:shape/enter "the fold and the rebuild number the intervals differently; containment is the invariant"
   :shape/exit  "the fold and the rebuild number the intervals differently; containment is the invariant"})

(defn- declared-shape-attrs
  "The attributes `app.graph.relation-overlay/schema` declares over shape entities,
  plus the folded token vocabulary that needs no declaration."
  []
  (into (into #{} (filter (fn [attr] (= "shape" (namespace attr)))) (keys overlay/schema))
        overlay/token-attrs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the round trip, the table, and the gate
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

(t/deftest a-set-written-onto-a-taken-name-leaves-one-set
  ;; The shared change list asserts that nothing skips, so a collision row
  ;; cannot live there; this folds a list of its own and compares it against
  ;; a rebuild, in the shape `round-trip` uses. `base-data` already holds
  ;; `token-set-id` named "Brand" with `token-id` under it, so the second
  ;; change below lands on that name and both entities must leave.
  (let [change-list [{:type :set-token-set :id token-set2-id
                      :attrs {:id token-set2-id :name "Semantic"}}
                     {:type :set-token-set :id token-set2-id
                      :attrs {:id token-set2-id :name "Brand" :tokens {}}}]
        result      (sync/apply-changes (overlay/build (base-data)) change-list)
        folded      (:db result)
        {:keys [diff skipped]} (round-trip change-list)]
    (t/is (empty? skipped)
          (str "a displacement must not skip: " (pr-str skipped)))
    (t/is (empty? diff)
          (str "fold and rebuild disagree on " (pr-str diff)))
    (t/is (nil? (d/entid folded [:token-set/id token-set-id]))
          "the displaced set left the library")
    (t/is (nil? (d/entid folded [:token/id token-id]))
          "and so did the token under it")))

(t/deftest a-token-written-onto-a-taken-name-leaves-one-token
  ;; The two writes a taken token name can arrive from, each folded as a
  ;; list of two changes and compared against the rebuild. The rename is
  ;; the branch whose document outcome reads the token map's order
  ;; (`pp:graph:token-rename-displacement-is-order-dependent`); this list
  ;; is the ordering where the holder is the one that goes, which is the
  ;; reading the mirror takes.
  (let [check (fn [label change-list displaced kept]
                (let [result (sync/apply-changes (overlay/build (base-data)) change-list)
                      folded (:db result)
                      {:keys [diff skipped]} (round-trip change-list)]
                  (t/is (empty? skipped)
                        (str label ": the displacement must not skip " (pr-str skipped)))
                  (t/is (empty? diff)
                        (str label ": fold and rebuild disagree on " (pr-str diff)))
                  (t/is (nil? (d/entid folded [:token/id displaced]))
                        (str label ": the displaced token left the library"))
                  (t/is (some? (d/entid folded [:token/id kept]))
                        (str label ": the token that took the name stayed"))))]
    ;; a fresh id added under a name the set already holds
    (check "add onto a taken name"
           [{:type :set-token :set-id token-set-id :token-id token2-id
             :attrs {:id token2-id :name "second" :type :color :value "#123456"}}
            {:type :set-token :set-id token-set-id :token-id token3-id
             :attrs {:id token3-id :name "second" :type :color :value "#654321"}}]
           token2-id token3-id)
    ;; an existing token renamed onto a name another token holds
    (check "rename onto a taken name"
           [{:type :set-token :set-id token-set-id :token-id token2-id
             :attrs {:id token2-id :name "second" :type :color :value "#123456"}}
            {:type :set-token :set-id token-set-id :token-id token2-id
             :attrs {:id token2-id :name "brand.primary" :type :color :value "#123456"}}]
           token-id token2-id)))

(t/deftest the-standing-queries-answer-the-same-from-the-fold-and-the-rebuild
  ;; The raw ids ride beside the resolved references, and the standing
  ;; queries read the references: `uses-color` reunites the three colour
  ;; sources, `shapes-using-typography` reads the ref, and `uses-token`
  ;; keys off the folded name. The prefix ends before the typography and
  ;; colour deletions so each query has an answer to compare, and the
  ;; colour answer names both sources the repair fills in: rect's fill,
  ;; which landed before the colour existed, and rect2's stroke.
  (let [n       (inc (first (keep-indexed
                             (fn [i ch] (when (= :mod-typography (:type ch)) i))
                             changes)))
        prefix  (subvec changes 0 n)
        data0   (base-data)
        synced  (:db (sync/apply-changes (overlay/build data0) prefix))
        rebuilt (overlay/build (cp/process-changes data0 prefix false))]
    (t/is (= #{[page-id rect-id] [page-id rect2-id]}
             (queries/shapes-using-color synced color2-id)))
    (t/is (= (queries/shapes-using-color synced color2-id)
             (queries/shapes-using-color rebuilt color2-id)))
    (t/is (= #{[page-id text-id]}
             (queries/shapes-using-typography synced typ-id)))
    (t/is (= (queries/shapes-using-typography synced typ-id)
             (queries/shapes-using-typography rebuilt typ-id)))
    (t/is (= #{[page-id rect2-id :fill]}
             (queries/shapes-using-token synced "brand.primary")))
    (t/is (= (queries/shapes-using-token synced "brand.primary")
             (queries/shapes-using-token rebuilt "brand.primary")))))

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
    ;; the snapshot copies. The copy child's last `:shape-ref` write is
    ;; the `:assign` operation, so it resolves to the child copy, and the
    ;; copy root resolves to the root copy.
    (t/is (= (get-in rebuilt-m [[:shape page-id copy-root-id] :shape/refers-to])
             (get-in synced-m [[:shape page-id copy-root-id] :shape/refers-to])))
    (t/is (= [:shape comp-id comp-root-id]
             (get-in synced-m [[:shape page-id copy-root-id] :shape/refers-to])))
    (t/is (= [:shape comp-id comp-child-id]
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

(t/deftest the-diff-catches-a-dropped-reorder
  ;; The mirror is load-bearing, and this is the assertion that says so.
  ;; Drop the reorder from the list the overlay sees and keep it in the
  ;; list the document sees: the two shapes the reorder moved are named by
  ;; the difference, each with the ordinal the fold holds and the ordinal
  ;; the rebuild derived.
  (let [data0    (base-data)
        data1    (cp/process-changes data0 changes false)
        crippled (remove #(= :reorder-children (:type %)) changes)
        synced   (into {} (normal-form (:db (sync/apply-changes (overlay/build data0)
                                                                crippled))))
        rebuilt  (into {} (normal-form (overlay/build data1)))
        moved    (into {}
                       (keep (fn [[key attrs]]
                               (when (not= (:shape/order attrs)
                                           (:shape/order (get rebuilt key)))
                                 [key [(:shape/order attrs)
                                       (:shape/order (get rebuilt key))]])))
                       synced)]
    (t/is (= {[:shape page-id rect-id]  [1 0]
              [:shape page-id rect2-id] [0 1]}
             moved)
          "a fold that drops the reorder must name the two shapes it left behind")))

(t/deftest the-intervals-agree-after-the-full-replay
  ;; The Euler-tour invariants hold on the synced db, not only on the
  ;; built one. The sync path renumbers a container from beyond the
  ;; global maximum with a different DFS order than the builder
  ;; (`app.graph.relation-overlay.sync/renumber-container-tx`), so interval
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the sibling ordinal
;;
;; `:shape/order` is the position a shape holds in its parent's `:shapes`
;; vector, and it is the one piece of order the index owns: the intervals
;; answer containment and are excluded from the comparison, so a consumer
;; holding an overlay and no document reads this attribute to learn which
;; child of which parent a shape is
;; (`pp:graph:sibling-order-needs-an-authored-attribute`).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ordinals
  "The sibling ordinal of every shape, keyed by the shape's own id: what
  a consumer reads out of the index without the document."
  [db]
  (into {} (d/q '[:find ?id ?o :where [?s :shape/id ?id] [?s :shape/order ?o]] db)))

(defn- document-containers
  "Every container of one decoded document as [container-id objects]: the
  pages in file order, then the components that carry their own copy."
  [data]
  (into (vec (keep (fn [page-id]
                     (when-let [page (get-in data [:pages-index page-id])]
                       [page-id (:objects page)]))
                   (:pages data)))
        (keep (fn [[component-id component]]
                (when-let [objects (:objects component)]
                  [component-id objects])))
        (:components data)))

(t/deftest the-sibling-ordinal-recovers-each-shape-position
  ;; The ordinal's acceptance, against the document's own vector: for every
  ;; container, parent and child, the child at index i of `:shapes` carries
  ;; ordinal i in the index. Both paths are checked, because the builder
  ;; derives the ordinal from the vector and the fold maintains it from the
  ;; changes, and a consumer cannot tell which one produced the index it
  ;; holds.
  (let [data0  (base-data)
        data1  (cp/process-changes data0 changes false)
        built  (overlay/build data1)
        folded (:db (sync/apply-changes (overlay/build data0) changes))]
    (doseq [[label db] [["built" built] ["folded" folded]]
            [cid objects] (document-containers data1)
            [pid parent] objects
            [i sid] (map-indexed vector (:shapes parent))]
      (let [ceid (queries/container-eid db cid)
            e    (d/entity db (queries/shape-eid db ceid sid))]
        (t/is (= i (:shape/order e))
              (str label ": shape " (pr-str sid) " at index " i " of "
                   (pr-str pid) "'s children in container " (pr-str cid)
                   " reads ordinal " (pr-str (:shape/order e))))))))

(t/deftest an-insertion-rewrites-no-siblings-ordinal
  ;; The blast radius the acceptance asks for: applying one insertion
  ;; through the sync path adds a datom for the appended child and rewrites
  ;; the ordinal of no shape that was already present. The
  ;; five-thousand-object measurement is the entry's own; this is the same
  ;; claim at fixture size, where a regression fails here rather than in a
  ;; script nobody runs.
  (let [data0  (base-data)
        prefix (subvec changes 0 6)
        db     (-> (overlay/build data0) (sync/apply-changes prefix) :db)
        before (ordinals db)
        new-id (uuid/next)
        result (sync/apply-changes
                db
                [{:type :add-obj :page-id page-id :id new-id
                  :parent-id frame-id :frame-id frame-id
                  :obj (shape new-id :rect {:name "Added" :parent-id frame-id
                                            :frame-id frame-id
                                            :width 5 :height 5})}])
        after  (ordinals (:db result))]
    (t/is (empty? (:skipped result))
          (str "the insertion must not skip: " (pr-str (:skipped result))))
    (t/is (= before (dissoc after new-id))
          "an insertion must leave every ordinal a shape already carried alone")
    (t/is (= 4 (get after new-id))
          "and the appended child lands after the frame's four children")))

(t/deftest a-reorder-inside-a-component-copy-needs-allow-altering-copies
  ;; `process-children-reordering` leaves copy child ordering to component
  ;; sync unless the change allows altering copies. A parent carrying a
  ;; `:shape-ref` is a copy on both paths, so the same reorder reaches
  ;; neither child list without the flag and both with it.
  (let [root    (uuid/next)
        child-1 (uuid/next)
        child-2 (uuid/next)
        setup   [{:type :add-obj :page-id page-id :id root
                  :parent-id uuid/zero :frame-id uuid/zero
                  :obj (shape root :frame {:name "Copy root" :parent-id uuid/zero
                                           :frame-id uuid/zero
                                           :width 100 :height 100
                                           :shape-ref rect-id})}
                 {:type :add-obj :page-id page-id :id child-1
                  :parent-id root :frame-id root
                  :obj (shape child-1 :rect {:name "First" :parent-id root
                                             :frame-id root :width 10 :height 10})}
                 {:type :add-obj :page-id page-id :id child-2
                  :parent-id root :frame-id root
                  :obj (shape child-2 :rect {:name "Second" :parent-id root
                                             :frame-id root :width 10 :height 10})}]
        reorder (fn [flags]
                  (conj (vec setup)
                        (merge {:type :reorder-children :page-id page-id
                                :parent-id root
                                :shapes [child-2 child-1]}
                               flags)))]
    (doseq [[label flags expected] [["without the flag" {}
                                     {child-1 0 child-2 1}]
                                    ["with the flag" {:allow-altering-copies true}
                                     {child-2 0 child-1 1}]]]
      (let [result (sync/apply-changes (overlay/build (base-data)) (reorder flags))
            {:keys [diff skipped]} (round-trip (reorder flags))]
        (t/is (empty? skipped)
              (str label ": the reorder must not skip: " (pr-str skipped)))
        (t/is (empty? diff)
              (str label ": fold and rebuild disagree on " (pr-str diff)))
        (t/is (= expected (select-keys (ordinals (:db result)) [child-1 child-2]))
              (str label ": the copy's child order moved the wrong way"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the move gate
;;
;; `process-change :mov-objects` refuses the whole change when any shape it
;; names may not move, and a shape may not land in its own subtree, in a
;; component copy, or in its main component. The mirror asks the document's
;; own predicate for that answer, so a move the document leaves alone is a
;; move the fold leaves alone, and a change the document moves nothing for
;; rewrites no datom here.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private with-a-copy-on-the-page
  "The fixture's changes up to the component record's rename: the main
  instance's subtree stands on the page, and `copy-root-id` carries a
  `:shape-ref`, so a move into it is a move into a component copy."
  (vec (take-while #(not= :mod-component (:type %)) changes)))

(def ^:private before-circ-leaves
  "The fixture's changes up to the `:del-obj` of circ-id: `subchild-id` is
  still the frame's grandchild, which is what makes a move of the frame
  under it a move under its own descendant."
  (vec (take-while #(not= :del-obj (:type %)) changes)))

(defn- parent-shape-id
  "The shape id of the parent the index gives `sid` inside `container-id`:
  the answer a consumer reads out of the fold without the document."
  [db container-id sid]
  (let [ceid (queries/container-eid db container-id)]
    (:shape/id (:shape/parent (d/entity db (queries/shape-eid db ceid sid))))))

(defn- fold-and-rebuild
  "The fixture after `change-list` on both paths: the changed document, the
  folded index, the rebuilt index, and the fold's report. `round-trip`
  compares the second and third in normal form; a test that names an entity
  needs the databases themselves."
  [change-list]
  (let [data0  (base-data)
        data1  (cp/process-changes data0 change-list false)
        result (sync/apply-changes (overlay/build data0) change-list)]
    {:document data1
     :folded   (:db result)
     :rebuilt  (overlay/build data1)
     :applied  (:applied result)
     :skipped  (:skipped result)}))

(t/deftest a-move-the-gate-refuses-leaves-the-parent-edges-alone
  ;; The measurement `pp:graph:the-move-mirror-ignores-the-validity-gate`
  ;; records, on this fixture: the destination is a component copy, so the
  ;; document refuses to move the main instance's child into it, and the fold
  ;; used to follow the move anyway, leaving the fold with a parent edge the
  ;; document never had.
  (let [change (conj with-a-copy-on-the-page
                     {:type :mov-objects :page-id page-id
                      :parent-id copy-root-id :shapes [comp-child-id]})
        {:keys [document folded rebuilt applied skipped]} (fold-and-rebuild change)]
    (t/is (empty? skipped)
          (str "a refused move is not a skip: " (pr-str skipped)))
    (t/is (= :mov-objects (peek applied))
          "the change is applied, because the index still equals a rebuild")
    (t/is (= comp-root-id
             (get-in document [:pages-index page-id :objects comp-child-id :parent-id]))
          "the document kept the main instance's child where it was")
    (t/is (= [comp-root-id comp-root-id]
             [(parent-shape-id folded page-id comp-child-id)
              (parent-shape-id rebuilt page-id comp-child-id)])
          "and both indexes kept the parent the document kept")
    (t/is (= (normal-form folded) (normal-form rebuilt))
          "the parent edge was the whole difference this case had")))

(t/deftest a-move-into-a-copy-follows-allow-altering-copies
  ;; `allow-altering-copies` is the document's own lift of the copy
  ;; restriction, which a component swap carries, and the mirror passes it
  ;; through rather than deciding for itself: the same move into a copy is
  ;; refused without the flag and follows with it, on both paths.
  (let [change  (fn [flags]
                  (conj with-a-copy-on-the-page
                        (merge {:type :mov-objects :page-id page-id
                                :parent-id copy-root-id :shapes [rect-id]}
                               flags)))
        refused (fold-and-rebuild (change {}))
        allowed (fold-and-rebuild (change {:allow-altering-copies true}))]
    (t/is (= [frame-id frame-id]
             [(parent-shape-id (:folded refused) page-id rect-id)
              (parent-shape-id (:rebuilt refused) page-id rect-id)])
          "without the flag the copy still refuses the move")
    (t/is (= (normal-form (:folded refused)) (normal-form (:rebuilt refused))))
    (t/is (= [copy-root-id copy-root-id]
             [(parent-shape-id (:folded allowed) page-id rect-id)
              (parent-shape-id (:rebuilt allowed) page-id rect-id)])
          "and with it both indexes follow the move the document makes")
    (t/is (= (normal-form (:folded allowed)) (normal-form (:rebuilt allowed))))))

(t/deftest one-invalid-shape-moves-none-of-the-shapes-with-it
  ;; The half a per-shape mirror cannot see: the gate is all-or-nothing for
  ;; the change, so the frame that may not land under its own descendant
  ;; keeps the text beside it where it was. The control is the same change
  ;; with the frame left out, which moves the text on both paths — the
  ;; invalid shape's presence is the only difference between the two.
  (let [change  (fn [shape-ids]
                  (conj before-circ-leaves
                        {:type :mov-objects :page-id page-id
                         :parent-id subchild-id :shapes shape-ids}))
        refused (fold-and-rebuild (change [frame-id text-id]))
        control (fold-and-rebuild (change [text-id]))]
    (t/is (empty? (:skipped refused))
          (str "a refused move is not a skip: " (pr-str (:skipped refused))))
    (t/is (= [uuid/zero uuid/zero]
             [(parent-shape-id (:folded refused) page-id frame-id)
              (parent-shape-id (:rebuilt refused) page-id frame-id)])
          "neither index moved the frame the gate refuses")
    (t/is (= [uuid/zero uuid/zero]
             [(parent-shape-id (:folded refused) page-id text-id)
              (parent-shape-id (:rebuilt refused) page-id text-id)])
          "and the text stayed with it, as the document left it")
    (t/is (= (normal-form (:folded refused)) (normal-form (:rebuilt refused))))
    (t/is (empty? (:skipped control))
          (str "the control is a valid move: " (pr-str (:skipped control))))
    (t/is (= [subchild-id subchild-id]
             [(parent-shape-id (:folded control) page-id text-id)
              (parent-shape-id (:rebuilt control) page-id text-id)])
          "while the same change without the invalid shape moves the text")
    (t/is (= (normal-form (:folded control)) (normal-form (:rebuilt control))))))

(t/deftest a-move-under-its-own-descendant-returns-and-folds-to-the-rebuild
  ;; The second measurement: the mirror followed this move, closed a cycle in
  ;; its parent edges, and walked `subtree-eids` for ever — eight seconds in,
  ;; the stack still bore it. The gate refuses the move, so there is no cycle
  ;; to enter and the change lands as an applied change.
  (let [change (conj before-circ-leaves
                     {:type :mov-objects :page-id page-id
                      :parent-id subchild-id :shapes [frame-id]})
        {:keys [document folded rebuilt applied skipped]} (fold-and-rebuild change)]
    (t/is (empty? skipped)
          (str "a refused move is not a skip: " (pr-str skipped)))
    (t/is (= :mov-objects (peek applied))
          "the change is applied, because the index still equals a rebuild")
    (t/is (= uuid/zero
             (get-in document [:pages-index page-id :objects frame-id :parent-id]))
          "the document refuses to nest the frame under its own descendant")
    (t/is (= [uuid/zero uuid/zero]
             [(parent-shape-id folded page-id frame-id)
              (parent-shape-id rebuilt page-id frame-id)])
          "and neither index followed it")
    (t/is (= (normal-form folded) (normal-form rebuilt)))))

(t/deftest the-subtree-walk-returns-on-a-cyclic-index
  ;; The gate is the first half of the repair and this is the second: an
  ;; index that already holds a cycle must not make the walk run for ever,
  ;; because `subtree-eids` runs over every moved subtree and every deleted
  ;; one. `app.common.files.helpers/get-children-ids`, which walks the same
  ;; closure in the document, carries its own `processed` set for the same
  ;; reason. The deref is the assertion: a walk that does not return fails
  ;; here rather than hanging the run.
  (let [db     (-> (overlay/build (base-data))
                   (sync/apply-changes (subvec changes 0 4))
                   :db)
        ceid   (queries/container-eid db page-id)
        circ   (queries/shape-eid db ceid circ-id)
        sub    (queries/shape-eid db ceid subchild-id)
        ;; circ under its own child: a cycle of two, which no accepted change
        ;; produces and a rebuilt index can still hold
        db     (d/db-with db [[:db/add circ :shape/parent sub]])
        walked (deref (future (#'sync/subtree-eids db circ)) 5000 ::timeout)]
    (t/is (not= ::timeout walked)
          "the walk must return on a cyclic index")
    (t/is (= [circ sub] walked)
          "and name each shape of the cycle once")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the staleness gate
;;
;; Three pieces: a pure verdict over one batch, a session that records
;; it, and two read gates that refuse while it is set. The session here
;; is synthetic, because the verdict is what needs pinning and a real
;; `load-session!` would need a file in Postgres.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private a-query
  "[:find ?e :where [?e :document/id _]]")

(def ^:private clean-change
  {:type :mod-page :id page-id :name "Renamed by the sync loop"})

(def ^:private unsupported-change
  "A change type this tier has no mirror for, which is what the staleness
  gate catches: plugin data reaches no overlay attribute. It was
  `:reorder-children` until the sibling ordinal landed and that type
  became mirrorable."
  {:type :set-plugin-data
   :object-type :shape
   :object-id rect-id
   :page-id page-id
   :namespace :fixture
   :key "the-plugin"
   :value "{:anything true}"})

(defn- with-session
  "Call `f` with one session registered in `app.graph.debug`: the fixture
  overlay in an atom and the metadata a load would have written, with no
  database, no msgbus and no sync loop. The registry is a private
  `defonce`, and reaching it is the seam a session-level assertion needs."
  [f]
  (let [registry @#'graph.debug/sessions
        profile  (uuid/next)]
    (try
      (swap! registry assoc (str profile)
             {:db-atom    (atom (overlay/build (base-data)))
              :file-id    file-id
              :meta       {:name           "Fixture"
                           :revn           1
                           :graph-revn     1
                           :schema-version overlay/schema-version}
              :profile-id profile
              :loaded-at  (ct/now)})
      (f profile)
      (finally
        (swap! registry dissoc (str profile))))))

(defn- deliver-batch!
  "One msgbus file-change message, delivered the way the sync loop
  delivers it."
  [profile revn change-list]
  (#'graph.debug/apply-file-change! profile {:file-id file-id
                                             :revn    revn
                                             :changes change-list}))

(defn- refusal
  [thunk]
  (try (thunk) nil (catch Throwable cause (ex-data cause))))

(t/deftest the-staleness-verdict-pins-its-three-cases
  (t/is (nil? (sync/staleness 41 42 {:applied [:mod-obj] :skipped []}))
        "a contiguous revision with nothing skipped is not stale")
  (t/is (= {:reason :revision-gap :graph-revn 41 :revn 43}
           (sync/staleness 41 43 {:applied [:mod-obj] :skipped []}))
        "a revision that skips a number is a message this index never saw")
  (t/is (= {:reason :skipped :types [:set-plugin-data]}
           (sync/staleness 41 42 {:applied []
                                  :skipped [{:type :set-plugin-data
                                             :reason :unsupported-type}]}))
        "a skipped change names its type")
  (t/is (= :apply-failed (:reason (sync/staleness 41 42 {:error "boom"}))))
  (t/is (= sync/stale-reasons
           (into #{} (keep :reason)
                 [(sync/staleness 41 43 {})
                  (sync/staleness 41 42 {:skipped [{:type :set-plugin-data}]})
                  (sync/staleness 41 42 {:error "boom"})]))
        "the three cases are the three the namespace names"))

(t/deftest a-clean-contiguous-batch-leaves-the-session-fresh
  (with-session
    (fn [profile]
      (deliver-batch! profile 2 [clean-change])
      (let [status (graph.debug/sync-status profile)]
        (t/is (= 2 (:graph-revn status))
              "the revision is stamped for every message")
        (t/is (nil? (:stale status)))
        (t/is (nil? (:reason status)))))))

(t/deftest a-skipped-change-marks-the-session-stale-for-good
  (with-session
    (fn [profile]
      (deliver-batch! profile 2 [unsupported-change])
      (let [status (graph.debug/sync-status profile)]
        (t/is (= :skipped (:reason status)))
        (t/is (= [:set-plugin-data] (get-in status [:stale :types])))
        (t/is (= 2 (:graph-revn status))
              "a batch that applied nothing still advanced the file"))
      (deliver-batch! profile 3 [clean-change])
      (t/is (= :skipped (:reason (graph.debug/sync-status profile)))
            "a later clean batch must not clear the mark: only a rebuild does"))))

(t/deftest a-revision-gap-marks-the-session-stale
  (with-session
    (fn [profile]
      (deliver-batch! profile 4 [clean-change])
      (t/is (= {:reason :revision-gap :graph-revn 1 :revn 4}
               (:stale (graph.debug/sync-status profile)))))))

(t/deftest a-batch-that-throws-marks-the-session-stale
  (with-session
    (fn [profile]
      (with-redefs [sync/apply-changes (fn [& _] (throw (ex-info "boom" {})))]
        (deliver-batch! profile 2 [clean-change]))
      (t/is (= :apply-failed (:reason (graph.debug/sync-status profile)))))))

(t/deftest a-stale-session-refuses-both-reads
  (with-session
    (fn [profile]
      (t/is (some? (:rows (graph.debug/query-session! profile a-query)))
            "a fresh session answers")
      (t/is (some? (:nodes (graph.debug/export-graph-data! profile))))

      (deliver-batch! profile 2 [unsupported-change])

      (let [data (refusal #(graph.debug/query-session! profile a-query))]
        (t/is (= :graph-index-stale (:code data)))
        (t/is (= :skipped (:reason data))))
      (t/is (= :graph-index-stale
               (:code (refusal #(graph.debug/export-graph-data! profile))))
            "the graph view is the second read gate")
      (t/is (= :skipped (:reason (graph.debug/sync-status profile)))
            "and the status names the reason the reads refused"))))

(t/deftest the-classification-table-names-every-change-type
  ;; The point of the table: a type Penpot adds upstream fails here by
  ;; name rather than being skipped in silence. `:default` is a dispatch
  ;; fallback, not a member of the vocabulary.
  (let [dispatched (disj (set (keys (methods cp/process-change))) :default)
        rows       (set (keys change-coverage))]
    (t/is (= dispatched rows)
          (str "change types with no row: " (pr-str (set/difference dispatched rows))
               "; rows naming no change type: " (pr-str (set/difference rows dispatched)))))
  (t/is (every? (comp string? :why val) change-coverage)
        "every row carries its reason")
  (t/is (= sync/supported-change-types (types-with change-coverage :covered :partial))
        "the applier's dispatch and the table's verdict are one list")
  (t/is (empty? (set/intersection (types-with change-coverage :no-op)
                                  sync/supported-change-types))
        "a type that cannot reach a datom must not claim support")
  (t/is (set/subset? (set/difference ladybug-only-gaps sync/supported-change-types)
                     (types-with change-coverage :no-op))
        "the Ladybug residue this tier does not cover is a subset of this tier's no-ops")
  (t/is (contains? ladybug-only-gaps :reorder-children)
        "a reorder stays a named gap on the Ladybug mirror")
  (t/is (contains? (types-with change-coverage :covered) :reorder-children)
        "and it is covered here, which is what the ordinal bought"))

(t/deftest the-mov-objects-row-names-the-gate-it-covers
  ;; The row is the table's claim about which moves the mirror covers, and
  ;; the mirror covers exactly the moves the document's validity gate
  ;; accepts. A row that names the decision it rests on and states the
  ;; answer for a change the gate refuses is one a reader can hold the
  ;; applier to, and the claim goes stale loudly where the row goes stale.
  (let [why (:why (change-coverage :mov-objects))]
    (t/is (str/includes? why "valid-move?")
          "the row names the decision the mirror asks")
    (t/is (str/includes? why "refuses")
          "and states what a change the gate refuses moves")))

(t/deftest the-classification-table-names-every-operation
  (let [dispatched (disj (set (keys (methods cp/process-operation))) :default)
        rows       (set (keys operation-coverage))]
    (t/is (= dispatched rows)
          (str "operations with no row: " (pr-str (set/difference dispatched rows))
               "; rows naming no operation: " (pr-str (set/difference rows dispatched)))))
  (t/is (every? (comp string? :why val) operation-coverage))
  (t/is (= #{:set :assign :set-touched} (types-with operation-coverage :covered))
        "the three operations that reach an overlay attribute")
  (t/is (= #{:set-remote-synced} (types-with operation-coverage :no-op))))

(t/deftest every-declared-shape-attribute-names-its-writer
  ;; The third filter, and the one that fails by name: a shape attribute
  ;; the builder starts indexing must arrive here with its writer, or the
  ;; fold writes no datom for it while a rebuild does, and no gate sees the
  ;; difference because nothing was skipped.
  (let [declared   (declared-shape-attrs)
        named      (set/union (set (keys shape-attr-writers))
                              (set (keys excluded-shape-attrs)))
        unwritten  (set/difference declared named)
        undeclared (set/difference named declared)
        unnamed    (into #{}
                         (keep (fn [[attr writers]]
                                 (when (or (empty? writers)
                                           (not-every? (fn [[kind writer]]
                                                         (and (contains? writer-kinds kind)
                                                              (some? writer)))
                                                       writers))
                                   attr)))
                         shape-attr-writers)]
    (t/is (empty? unwritten)
          (str "shape attributes with no writer and no named exclusion: "
               (pr-str (sort unwritten))))
    (t/is (empty? undeclared)
          (str "writers declared for attributes the schema does not carry: "
               (pr-str (sort undeclared))))
    (t/is (empty? unnamed)
          (str "attributes whose writer entry names no writer: "
               (pr-str (sort unnamed))))
    (t/is (every? (comp string? val) excluded-shape-attrs)
          "every excluded attribute carries its reason")
    (t/is (= #{:shape/enter :shape/exit} (set (keys excluded-shape-attrs)))
          "the intervals are the only attributes the comparison excludes")))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.cases
  "Component-behaviour cases authored on the composable test model, shared by
   both runners: the frontend interpreter (the real app, in
   `frontend-tests.composable-tests.comp.sync-test`) and the pure runner
   (`app.common.test-helpers.composable.comp.runner`, in
   `common-tests.logic.composable-sync-test`).

   Each case is a zero-arg function returning `{:setup :operation :asserter}`.
   Assertions use `clojure.test` (`cljs.test` in CLJS), inline through `Test`
   operations and/or in the optional trailing `:asserter`, which receives the
   resulting situation of each enumerated variant. A case contains only the user
   edits: the runner syncs copies on its own, and that is what the cases
   assert.

   The cases (see `mem:frontend/composable-component-tests` for details):
     B — an override on a copy child survives a later main change (touched gate).
     C — a sweep (one-of) over several attribute changes, each propagating.
     D — a shape added to the main is structurally propagated (ref-integrity).
     E — a middle shape removed from the main; survivors keep order.
     F — reordering a shape in the main; order propagates, identity preserved.
     H — locality: a library main's change reaches the consuming file's copy on
         the explicit library sync (cross-file propagation).
     I — undo reverses an edit AND its propagation.
     P — redo applies an undone edit and its propagation again.
     K — the synchronisation sweep: depth x edit-targets, with override-precedence
         and reset checkpoints.
     L — the swap sweep: swaps at any subset of nesting levels.
     M — the variant-switch sweep: case L with variant switches.
     O — a variant switch keeps a copy override only where the mains agree.
     N — geometry sync with rotated instances (needs the frontend placement step).
     Rotation — a touched copy child survives a main rotation (frontend only too)."
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]
   [app.common.types.component :as ctk]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(def ^:private red "#ff0000")
(def ^:private green "#00ff00")
(def ^:private blue "#0000ff")

;; (Cases A, G, J retired — subsumed by case K's depth-swept propagation scenario.)

(defn copy-override-survives-later-main-change
  "Case B."
  []
  (let [override (n/change-attr :copy-child :fills green)]  ; touch the copy first
    {:setup     setup/simple-component-with-labeled-copy
     ;; override the copy, then change the main; sync runs after each edit. The
     ;; override must survive (touched-flag gate).
     :operation (tm/in-sequence
                 [override
                  (n/change-attr :main-child :fills red)])
     :asserter  (fn [situation]
                  (let [copy-child (setup/copy-instance situation)]
                    (t/is (n/has-attr? override copy-child))
                    (t/is (contains? (:touched copy-child) :fill-group))
                    (t/is (some? (:shape-ref copy-child)))))}))

(defn attribute-sweep-propagates-to-clean-copy
  "Case C."
  []
  (let [sweep (tm/one-of
               [(n/change-attr :main-child :fills red)
                (n/change-attr :main-child :opacity 0.5)])]
    {:setup     setup/simple-component-with-copy
     :operation (tm/in-sequence [sweep])
     :asserter  (fn [situation]
                  (let [chosen (tm/get-choice situation sweep)]
                    (t/is (some? chosen))
                    (t/is (n/has-attr? chosen (setup/copy-instance situation)))))}))

(defn add-shape-to-main-propagates-to-clean-copy
  "Case D."
  []
  (let [add (n/add-child :main-root :main-child-2)]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [add])
     :asserter  (fn [situation]
                  (let [copy-root (setup/copy-root situation)
                        main-new  (n/added-shape add situation)
                        copy-new  (n/materialized-instance-child add situation copy-root)]
                    (t/is (some? copy-new))
                    (t/is (ctk/is-main-of? main-new copy-new))
                    (t/is (ctst/parent-of? copy-root copy-new))
                    (t/is (nil? (:touched copy-new)))))}))

(defn remove-shape-from-main-propagates-to-clean-copy
  "Case E."
  []
  (let [removal (n/remove-child :main-child2)]
    {:setup     setup/component-with-many-children
     :operation (tm/in-sequence [removal])
     :asserter  (fn [situation]
                  (let [copy-root (setup/copy-root situation)
                        order     (vec (:shapes copy-root))
                        c1        (setup/copy-child situation 1)
                        c3        (setup/copy-child situation 3)]
                    (t/is (= 2 (count order)))
                    (t/is (= (nth order 0) (:id c1)))
                    (t/is (= (nth order 1) (:id c3)))
                    (t/is (some? (:shape-ref c1)))
                    (t/is (some? (:shape-ref c3)))
                    (t/is (nil? (:touched c1)))
                    (t/is (nil? (:touched c3)))
                    (t/is (nil? (:touched copy-root)))))}))

(defn move-shape-in-main-propagates-order-to-clean-copy
  "Case F."
  []
  (let [move (n/move-child :main-child1 :main-root 2)]
    {:setup      setup/component-with-many-children
     :operation  (tm/in-sequence [move])
     ;; the undo of the sync's move leaves the copy children reordered
     :undo-check {:known-failures [{:bug   "F41"
                                    :fails {:undo         #{[:object :copy-root :shapes]}
                                            :variant-undo #{[:object :copy-root :shapes]}}
                                    :op    move}]}
     :asserter   (fn [situation]
                   (let [copy-root (setup/copy-root situation)
                         order     (vec (:shapes copy-root))
                         c1        (setup/copy-child situation 1)
                         c2        (setup/copy-child situation 2)
                         c3        (setup/copy-child situation 3)]
                     (t/is (= (nth order 0) (:id c2)))
                     (t/is (= (nth order 1) (:id c1)))
                     (t/is (= (nth order 2) (:id c3)))
                     (t/is (some? (:shape-ref c1)))
                     (t/is (some? (:shape-ref c2)))
                     (t/is (some? (:shape-ref c3)))
                     (t/is (nil? (:touched c1)))
                     (t/is (nil? (:touched c2)))
                     (t/is (nil? (:touched c3)))))}))

(defn undo-reverts-edit-and-its-propagation
  "Case I. Change the main (which propagates to the clean copy), then undo. A
   single undo reverses the whole logical action — the edit AND its
   propagation — so the copy returns to baseline and is left untouched."
  []
  (let [original "#abcdef"                    ; the labeled setup's starting fill
        baseline (n/change-attr :main-child :fills original)  ; expected-VALUE descriptor
        change   (n/change-attr :main-child :fills red)]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [change (n/undo)])
     :asserter  (fn [situation]
                  (let [copy (setup/copy-instance situation)
                        main (setup/main-instance situation)]
                    ;; the edit was reversed on the main …
                    (t/is (n/has-attr? baseline main))
                    ;; … and on the copy (the propagation was reversed too) …
                    (t/is (n/has-attr? baseline copy))
                    ;; … leaving the copy clean.
                    (t/is (nil? (:touched copy)))))}))

(defn redo-reapplies-edit-and-its-propagation
  "Case P. Change the main (which propagates to the clean copy), undo, then
   redo. The redo applies the edit and its propagation again, so the copy
   shows the main's new value and is left untouched."
  []
  (let [change (n/change-attr :main-child :fills red)]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [change (n/undo) (n/redo)])
     :asserter  (fn [situation]
                  (let [copy (setup/copy-instance situation)
                        main (setup/main-instance situation)]
                    (t/is (n/has-attr? change main))
                    (t/is (n/has-attr? change copy))
                    (t/is (nil? (:touched copy)))))}))

(defn library-change-propagates-across-file-boundary-on-sync
  "Case H. The main lives in a linked LIBRARY, the copy in the consuming
   (current) file. The library main has diverged (setup applied `red` to it,
   leaving the copy stale). In-file sync does NOT cross the file boundary; the
   app propagates via the library-UPDATE action, so the operation is
   `sync-from-library`. `expected` is only an expected-VALUE descriptor (its
   target is irrelevant; `has-attr?` uses just attr+value)."
  []
  (let [expected (n/change-attr :main-child :fills red)]
    {:setup     #(setup/cross-file-component-with-copy red)
     :operation (tm/in-sequence [(n/sync-from-library)])
     :asserter  (fn [situation]
                  (t/is (n/has-attr? expected (setup/copy-instance situation))))}))

(defn synchronisation-scenarios
  "Case K. CONSOLIDATED SCENARIO SWEEP — one composition standing in for many
   cases. Built from the sync-scenario operations on an empty situation. It
   sweeps:
     - DEPTH 0/1/2 via two independent `(optional (make-nested-component ...))`
     - which EDITS were made via three independent `(optional change-*)`
   and asserts, at INLINE checkpoints, the override-precedence and reset rules.
   The change targets are the tracked lineage rects, so the same composition
   holds at any depth. Subsumes the flat/nested propagation cases (A/G/J)."
  []
  (let [m "main"
        change-remote (n/change-property (n/remote-rect-of m) :fills red)
        change-main   (n/change-property (n/main-rect-of m) :fills green)
        change-copy   (n/change-property (n/copy-rect-of m) :fills blue)
        copy-rect     (fn [s] (n/lineage-copy-rect s m))
        ;; precedence at the copy: copy override wins; else main; else remote.
        expected-after-edits
        (fn [s]
          (cond
            (tm/applied? s change-copy)   (n/has-property-of change-copy (tm/shape-by-id s (copy-rect s)))
            (tm/applied? s change-main)   (n/has-property-of change-main (tm/shape-by-id s (copy-rect s)))
            (tm/applied? s change-remote) (n/has-property-of change-remote (tm/shape-by-id s (copy-rect s)))
            :else true))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m red)
                  ;; depth sweep: two independent optionals give depths 0/1/2
                  ;; (depth 1 appears twice — harmless) without nesting a
                  ;; Sequence inside an optional.
                  (tm/optional (n/make-nested-component m))
                  (tm/optional (n/make-nested-component m))
                  (n/instantiate-copy m)
                  (tm/optional change-remote)
                  (tm/optional change-main)
                  (tm/optional change-copy)
                  (tm/test-that (fn [s] (t/is (expected-after-edits s))))
                  ;; force a copy override, observe it wins, then reset it away
                  change-copy
                  (tm/test-that
                   (fn [s] (t/is (n/has-property-of change-copy (tm/shape-by-id s (copy-rect s))))))
                  (n/reset-copy-instance m)
                  (tm/test-that
                   (fn [s]
                     ;; after reset: main's value if main changed, else remote's
                     ;; if remote changed (else the original — not asserted).
                     (cond
                       (tm/applied? s change-main)   (t/is (n/has-property-of change-main (tm/shape-by-id s (copy-rect s))))
                       (tm/applied? s change-remote) (t/is (n/has-property-of change-remote (tm/shape-by-id s (copy-rect s))))
                       :else true)))])}))

(defn- level-color
  "The fill colour of the rect currently at lineage `name`'s nesting level `i`."
  [s name i]
  (-> (tm/shape-by-id s (n/level-rect s name i)) :fills first :fill-color))

(def ^:private base-color "#aaaaaa")
(def ^:private swap-colors ["#ff0000" "#00ff00" "#0000ff"])   ; level 0/1/2 targets

(def ^:private level-0-reflow-left
  ;; what one undo of a first step at level 0 leaves in place
  #{[:object :sync-main-innercopy-0 :component-id]
    [:object :sync-main-innercopy-0 :shape-ref]
    [:object :sync-main-innercopy-0 :shapes]
    [:object :sync-main-innercopy-0 :touched]
    [:object :sync-main-innercopy-0 :width]
    [:object :sync-main-innercopy-0 :height]
    [:object :sync-main-innercopy-0 :selrect]
    [:object :sync-main-innercopy-1 :width]
    [:object :sync-main-innercopy-1 :height]
    [:object :sync-main-innercopy-1 :selrect]
    [:object :sync-main-innercopy-2 :width]
    [:object :sync-main-innercopy-2 :height]
    [:object :sync-main-innercopy-2 :selrect]
    [:object :unlabelled :component-id]
    [:object :unlabelled :shapes]
    [:object :unlabelled :missing]
    [:object :unlabelled :extra]})

(def ^:private level-1-reflow-left
  ;; what one undo of a first step at level 1 leaves in place
  #{[:object :unlabelled :component-id]
    [:object :unlabelled :shape-ref]
    [:object :unlabelled :shapes]
    [:object :unlabelled :touched]
    [:object :unlabelled :missing]
    [:object :unlabelled :extra]
    [:object :sync-main-innercopy-1 :width]
    [:object :sync-main-innercopy-1 :height]
    [:object :sync-main-innercopy-1 :selrect]
    [:object :sync-main-innercopy-2 :width]
    [:object :sync-main-innercopy-2 :height]
    [:object :sync-main-innercopy-2 :selrect]})

(defn- first-steps-break-undo
  "Known failures of the frontend round trip for the sweeps over `steps`, the
   optional steps at levels 0, 1 and 2: the first of them to run at level 0 or
   1 reflows the nested frames, and that reflow lands in undo entries of its
   own (F43), so one undo does not revert the whole step."
  [steps]
  (let [mark (fn [op pred left entries]
               {:bug     "F43"
                :fails   {:undo       left
                          :undo-index #{[:undo-index entries]}}
                :op      op
                :runners #{:frontend}
                :when    pred})]
    [(mark (nth steps 0) (constantly true) level-0-reflow-left 5)
     (mark (nth steps 1) #(not (tm/applied? % (nth steps 0))) level-1-reflow-left 2)]))

(defn swap-scenarios
  "Case L. SWAP SWEEP — build a 3-level nesting, then OPTIONALLY swap the
   nested component at each level for a differently-coloured one, and assert
   the colour that surfaces at every level. A swap at level i propagates to
   level i and every OUTER (higher-index) level, until a swap at a higher level
   overrides it. So the colour at level i is the swap at the HIGHEST index
   j <= i that was applied, else the base colour."
  []
  (let [m       "main"
        targets ["s0" "s1" "s2"]
        ;; swap[i] swaps level i's nested component for target lineage i (color i)
        swaps   (mapv (fn [i] (n/swap-component m i (nth targets i))) (range 3))
        expected-at
        (fn [s i]
          ;; the colour of the applied swap at the highest j <= i, else base
          (or (some (fn [j] (when (tm/applied? s (nth swaps j)) (nth swap-colors j)))
                    (range i -1 -1))
              base-color))]
    {:setup      setup/empty-situation
     :undo-check {:known-failures (first-steps-break-undo swaps)}
     :operation  (tm/in-sequence
                  (concat
                   [(n/create-component m base-color)]
                   ;; a target lineage per level
                   (map-indexed (fn [i c] (n/create-component (nth targets i) c)) swap-colors)
                   [(n/make-nested-component m) (n/make-nested-component m) (n/make-nested-component m)]
                   ;; optionally swap at each level
                   (map (fn [sw] (tm/optional sw)) swaps)
                   [(tm/test-that
                     (fn [s]
                       (doseq [i (range 3)]
                         (t/is (= (expected-at s i) (level-color s m i))
                               (str "level " i)))))]))}))

(defn variant-switch-scenarios
  "Case M. VARIANT-SWITCH SWEEP — the variant-switch flavour of case L. Build
   a variant SET of peer members and nest the base member at EVERY level (so
   each level has a variant head, just as case L's swap target exists at every
   level). Then OPTIONALLY switch the variant head at each level to a
   differently-coloured sibling and assert the colour that surfaces at every
   level. A variant switch is a keep-touched swap, so it propagates like case
   L: the colour at level i is the switch at the HIGHEST index j <= i that was
   applied, else the base member's colour."
  []
  (let [m       "main"        ; the nesting lineage (holds the nesting-data)
        vset    "vset"        ; the variant set
        vals    ["v0" "v1" "v2" "v3"]
        colors  (into [base-color] swap-colors) ; base + sibling colours
        ;; switch[i] switches level i's variant head to member i+1 (colour i). The
        ;; single variant instance has a corresponding (switchable) head at every
        ;; level — `nested-head` IS the deepest instance there — so we can switch at
        ;; ANY level, exactly like case L's per-level swap.
        switches (mapv (fn [i] (n/switch-variant (n/nested-head-of m i) (nth vals (inc i))))
                       (range 3))
        expected-at
        (fn [s i]
          ;; same precedence as case L
          (or (some (fn [j] (when (tm/applied? s (nth switches j)) (nth swap-colors j)))
                    (range i -1 -1))
              base-color))]
    {:setup      setup/empty-situation
     :undo-check {:known-failures (first-steps-break-undo switches)}
     :operation  (tm/in-sequence
                  (concat
                   ;; the nesting lineage, and the variant set (members = [value color])
                   [(n/create-component m base-color)
                    (n/make-variant-container vset (mapv vector vals colors))]
                   ;; introduce the variant instance ONCE (innermost), then wrap it
                   ;; with plain nesting so each outer level CONTAINS the one below
                   ;; (progressive nesting, like case L). nested-head at every level
                   ;; is then the variant (the deepest instance), so a switch at
                   ;; level i targets it and propagates OUTWARD — exactly like case
                   ;; L's swap.
                   [(n/make-nested-component-with-variant m vset "v0")
                    (n/make-nested-component m)
                    (n/make-nested-component m)]
                   ;; optionally switch each level's variant head to its target sibling
                   (map (fn [sw] (tm/optional sw)) switches)
                   [(tm/test-that
                     (fn [s]
                       (doseq [i (range 3)]
                         (t/is (= (expected-at s i) (level-color s m i))
                               (str "level " i)))))]))}))

(defn variant-switch-keeps-override-only-where-mains-agree
  "Case O. A copy overrides the fill of its nested variant's rect, then the
   variant head inside the copy is switched to the other member. Sweeps a
   variant set whose members agree on the rect fill and one where they
   differ. Per decision (a) of `mem:common/component-sync-contract`, the
   override is carried only when the old and new mains agree: then the rect
   keeps it and stays touched; otherwise it takes the new member's fill and
   is not touched."
  []
  (let [m          "main"
        agree      (n/make-variant-container "vset" [["a" base-color] ["b" base-color]])
        differ     (n/make-variant-container "vset" [["a" base-color] ["b" red]])
        override   (n/change-property (n/copy-rect-of m) :fills green)
        ;; the variant head inside the copy holds the copy rect
        copy-head  (fn [s] (:parent-id (tm/shape-by-id s (n/lineage-copy-rect s m))))
        ;; after the switch, the copy rect is the one copying member b's rect
        switched   (fn [s]
                     (let [b-rect  (:rect-id (n/variant-member s "vset" "b"))
                           objects (:objects (thf/current-page (tm/file s)))]
                       (d/seek #(= b-rect (:shape-ref %)) (vals objects))))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color)
                  (tm/one-of [agree differ])
                  (n/make-nested-component-with-variant m "vset" "a")
                  (n/instantiate-copy m)
                  override
                  (n/switch-variant copy-head "b")])
     :asserter  (fn [situation]
                  (let [rect (switched situation)]
                    (t/is (some? rect))
                    (if (tm/applied? situation agree)
                      (do
                        (t/is (n/has-property-of override rect))
                        (t/is (contains? (:touched rect) :fill-group)))
                      (do
                        (t/is (= red (-> rect :fills first :fill-color)))
                        (t/is (not (contains? (:touched rect) :fill-group)))))))}))

(defn geometry-sync-with-rotated-instances
  "Case N. Regression sweep for #10109, with #13267's semantics as its
   complement: an instance root's transformation is inherited, overridable
   content — asymmetric to position, which is free per-instance placement.

   On the simple component-with-copy, sweep three axes: optionally rotate the
   COPY as a whole (an override of its root's placement — must not block
   propagation), optionally rotate the MAIN as a whole (inherited content — an
   untouched copy must follow it), and apply ONE of a property edit (fills) or
   a geometry edit (height) to the main child. In EVERY variant the chosen edit
   must arrive at the copy child; the copy's rotation is its own 45° if the
   copy was rotated (override wins), else the main's 45° if the main was
   rotated (clean copy follows), else 0; and only a rotated copy's ROOT is
   touched (:geometry-group) — its child merely follows and stays untouched."
  []
  (let [rotate-copy (n/rotate :copy-root 45)
        rotate-main (n/rotate :main-root 45)
        edits       (tm/one-of
                     [(n/change-attr :main-instance :fills red)
                      (n/change-height :main-instance 80)])]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [(tm/optional rotate-copy)
                                 (tm/optional rotate-main)
                                 edits])
     :asserter  (fn [situation]
                  (let [chosen            (tm/get-choice situation edits)
                        copy-root         (setup/copy-root situation)
                        copy-child        (setup/copy-instance situation)
                        expected-rotation (cond
                                            (tm/applied? situation rotate-copy) 45
                                            (tm/applied? situation rotate-main) 45
                                            :else                               0)]
                    ;; the chosen main edit arrived at the copy child, in every variant
                    (t/is (some? chosen))
                    (t/is (n/has-property-of chosen copy-child))
                    ;; the copy shows the expected rotation (own override > followed main > none)
                    (t/is (mth/close? (or (:rotation copy-root) 0) expected-rotation))
                    (t/is (mth/close? (or (:rotation copy-child) 0) expected-rotation))
                    ;; only a rotated copy's ROOT is an override; the child merely follows
                    (t/is (= (if (tm/applied? situation rotate-copy) #{:geometry-group} nil)
                             (:touched copy-root)))
                    (t/is (nil? (:touched copy-child)))))}))

(defn touched-copy-child-survives-main-rotation
  "A copy whose ROOT is rotated (a geometry override) AND whose CHILD has its
   own geometry override (resized) must keep the child exactly in place when
   the MAIN is later rotated: the child's placement is its own, shielded by its
   touched :geometry-group. We capture the child's position relative to the
   copy root before the main rotation and assert it is unchanged after."
  []
  (let [before  (atom nil)
        rel-pos (fn [situation]
                  (let [child (setup/copy-instance situation)
                        root  (setup/copy-root situation)]
                    (-> (gpt/subtract (grc/rect->center (:selrect child))
                                      (grc/rect->center (:selrect root)))
                        (gpt/transform (:transform-inverse root (gmt/matrix))))))
        capture (tm/test-that (fn [s] (reset! before (rel-pos s)) (t/is (some? @before))))]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [(n/rotate :copy-root 30)
                                 (n/change-height :copy-instance 60)
                                 capture
                                 (n/rotate :main-root 40)])
     :asserter  (fn [situation]
                  (let [copy-child (setup/copy-instance situation)
                        copy-root  (setup/copy-root situation)
                        after      (rel-pos situation)]
                    ;; both overrides are recorded as geometry touches
                    (t/is (contains? (:touched copy-root) :geometry-group))
                    (t/is (contains? (:touched copy-child) :geometry-group))
                    ;; the child keeps its own overridden height
                    (t/is (mth/close? (:height copy-child) 60))
                    ;; and its position relative to the copy root is unchanged by the main rotation
                    (t/is (mth/close? (:x @before) (:x after) 0.5) (str "rel-x " (:x @before) " -> " (:x after)))
                    (t/is (mth/close? (:y @before) (:y after) 0.5) (str "rel-y " (:y @before) " -> " (:y after)))))}))

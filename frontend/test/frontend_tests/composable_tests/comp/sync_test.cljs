;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.composable-tests.comp.sync-test
  "Component-behaviour cases authored on the composable test model, run against
   the REAL app (frontend interpreter). In-file propagation is AUTOMATIC: a case
   contains ONLY the user edit(s) (no propagate op); the app's component-change
   watcher syncs copies on its own, and that is what we assert.

   The cases (see `mem:frontend/composable-component-tests` for details):
     B — an override on a copy child survives a later main change (touched gate).
     C — a sweep (one-of) over several attribute changes, each auto-propagating.
     D — a shape added to the main is structurally auto-propagated (ref-integrity).
     E — a middle shape removed from the main; survivors keep order.
     F — reordering a shape in the main; order auto-propagates, identity preserved.
     H — locality: a library main's change reaches the consuming file's copy on
         the explicit library sync (cross-file propagation).
     I — undo reverses an edit AND its auto-propagation.
     K — the synchronisation sweep: depth x edit-targets, with override-precedence
         and reset checkpoints.
     L — the swap sweep: swaps at any subset of nesting levels.
     M — the variant-switch sweep: case L with variant switches.

   Async: each deftest uses `t/async`; `ftm/check` drives the store and calls
   `done` when finished."
  (:require
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.shape-tree :as ctst]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.comp.nodes :as n]
   [frontend-tests.composable-tests.comp.setups :as setup]
   [frontend-tests.composable-tests.core :as tm]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(def ^:private red "#ff0000")
(def ^:private green "#00ff00")

;; Disable thumbnail rendering for the duration of each (async) test: the
;; propagation watcher schedules thumbnail renders that reach `window`, absent in
;; the headless runner. `:each` `:after` runs only after the test's `done` fires
;; (same guarantee the wasm-mock fixtures rely on), so the no-op covers the whole
;; async lifetime and is scoped to THIS namespace. See ftm/install-thumbnail-noop!.
(t/use-fixtures :each
  {:before ftm/install-thumbnail-noop!
   :after  ftm/restore-thumbnail!})

;; (Cases A, G, J retired — subsumed by case K's depth-swept propagation scenario.)

(t/deftest case-b-copy-override-survives-later-main-change
  (t/async
    done
    (let [override (n/change-attr :copy-child :fills green)]  ; touch the copy first
      (ftm/check
       done
       {:setup          setup/simple-component-with-labeled-copy
        ;; override the copy, then change the main; the watcher auto-syncs after
        ;; each edit. The override must survive (touched-flag gate).
        :operation (tm/in-sequence
                    [override
                     (n/change-attr :main-child :fills red)])}
       (fn [situation]
         (let [copy-child (setup/copy-instance situation)]
           (t/is (n/has-attr? override copy-child))
           (t/is (contains? (:touched copy-child) :fill-group))
           (t/is (some? (:shape-ref copy-child)))))))))

(t/deftest case-c-attribute-sweep-auto-propagates-to-clean-copy
  (t/async
    done
    (let [sweep (tm/one-of
                 [(n/change-attr :main-child :fills red)
                  (n/change-attr :main-child :opacity 0.5)])]
      (ftm/check
       done
       {:setup          setup/simple-component-with-copy
        :operation (tm/in-sequence [sweep])}
       (fn [situation]
         (let [chosen (tm/get-choice situation sweep)]
           (t/is (some? chosen))
           (t/is (n/has-attr? chosen (setup/copy-instance situation)))))))))

(t/deftest case-d-add-shape-to-main-auto-propagates-to-clean-copy
  (t/async
    done
    (let [add (n/add-child :main-root :main-child-2)]
      (ftm/check
       done
       {:setup          setup/simple-component-with-labeled-copy
        :operation (tm/in-sequence [add])}
       (fn [situation]
         (let [copy-root (setup/copy-root situation)
               main-new  (n/added-shape add situation)
               copy-new  (n/materialized-instance-child add situation copy-root)]
           (t/is (some? copy-new))
           (t/is (ctk/is-main-of? main-new copy-new))
           (t/is (ctst/parent-of? copy-root copy-new))
           (t/is (nil? (:touched copy-new)))))))))

(t/deftest case-e-remove-shape-from-main-auto-propagates-to-clean-copy
  (t/async
    done
    (let [removal (n/remove-child :main-child2)]
      (ftm/check
       done
       {:setup          setup/component-with-many-children
        :operation (tm/in-sequence [removal])}
       (fn [situation]
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
           (t/is (nil? (:touched copy-root)))))))))

(t/deftest case-f-move-shape-in-main-auto-propagates-order-to-clean-copy
  (t/async
    done
    (let [move (n/move-child :main-child1 :main-root 2)]
      (ftm/check
       done
       {:setup          setup/component-with-many-children
        :operation (tm/in-sequence [move])}
       (fn [situation]
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
           (t/is (nil? (:touched c3)))))))))

(t/deftest case-i-undo-reverts-edit-and-its-auto-propagation
  (t/async
    done
    ;; UNDO axis (case I), built on case A: change the main (which auto-propagates
    ;; to the clean copy), then UNDO. A single undo reverses the whole logical
    ;; action — the edit AND its propagation — so the copy returns to baseline and
    ;; is left untouched. Undo is just another op (`n/undo`); the engine owns
    ;; reversal (frontend realisation dispatches the real `dwu/undo`).
    (let [original "#abcdef"                    ; the labeled setup's starting fill
          baseline (n/change-attr :main-child :fills original)  ; expected-VALUE descriptor
          change   (n/change-attr :main-child :fills red)]
      (ftm/check
       done
       {:setup          setup/simple-component-with-labeled-copy
        :operation (tm/in-sequence [change (n/undo)])}
       (fn [situation]
         (let [copy (setup/copy-instance situation)
               main (setup/main-instance situation)]
           ;; the edit was reversed on the main …
           (t/is (n/has-attr? baseline main))
           ;; … and on the copy (the propagation was reversed too) …
           (t/is (n/has-attr? baseline copy))
           ;; … leaving the copy clean.
           (t/is (nil? (:touched copy)))))))))

(t/deftest case-h-library-change-propagates-across-file-boundary-on-sync
  (t/async
    done
    ;; LOCALITY axis: the main lives in a linked LIBRARY, the copy in the consuming
    ;; (current) file. The library main has diverged (setup applied `red` to it,
    ;; leaving the copy stale). Unlike the in-file cases, the watcher does NOT cross the
    ;; file boundary; the real app propagates via the library-UPDATE action, so the
    ;; transformation is `sync-from-library` (dispatches the real `sync-file`).
    ;; `expected` is only an expected-VALUE descriptor (its target is irrelevant;
    ;; `has-attr?` uses just attr+value), so the asserter reads exactly like case A.
    (let [expected (n/change-attr :main-child :fills red)]
      (ftm/check
       done
       {:setup          #(setup/cross-file-component-with-copy red)
        :operation (tm/in-sequence [(n/sync-from-library)])}
       (fn [situation]
         (t/is (n/has-attr? expected (setup/copy-instance situation))))))))

(def ^:private blue "#0000ff")

(t/deftest case-k-synchronisation-scenarios
  (t/async
    done
    ;; CONSOLIDATED SCENARIO SWEEP — one composition standing in for many cases.
    ;; Built from the sync-scenario operations on an empty situation. It sweeps:
    ;;   - DEPTH 0/1/2 via two independent `(optional (make-nested-component ...))`
    ;;   - which EDITS were made via three independent `(optional change-*)`
    ;; and asserts, at INLINE checkpoints, the override-precedence and reset rules.
    ;; The change targets are the tracked ROLES (:remote/:main/:copy-child-rect), so
    ;; the same composition holds at any depth. Propagation is AUTOMATIC (no
    ;; propagate op). Subsumes the flat/nested propagation cases (A/G/J).
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
      (ftm/check
       done
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
                          :else true)))])}))))

(defn- level-color
  "The fill colour of the rect currently at lineage `name`'s nesting level `i`."
  [s name i]
  (-> (tm/shape-by-id s (n/level-rect s name i)) :fills first :fill-color))

(def ^:private base-color "#aaaaaa")
(def ^:private swap-colors ["#ff0000" "#00ff00" "#0000ff"])   ; level 0/1/2 targets

(t/deftest case-l-swap-scenarios
  (t/async
    done
    ;; SWAP SWEEP — build a 3-level nesting, then OPTIONALLY swap the nested
    ;; component at each level for a differently-coloured one, and assert the colour
    ;; that surfaces at every level. A swap at level i propagates (automatically, via
    ;; the watcher) to level i and every OUTER (higher-index) level, until a swap at
    ;; a higher level overrides it. So the colour at level i is the swap at the
    ;; HIGHEST index j <= i that was applied, else the base colour. (Generalises the
    ;; "single swap in copy" diagram across which levels are swapped.)
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
      (ftm/check
       done
       {:setup     setup/empty-situation
        :operation (tm/in-sequence
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
                                 (str "level " i)))))]))}))))

(t/deftest case-m-variant-switch-scenarios
  (t/async
    done
    ;; VARIANT-SWITCH SWEEP — the variant-switch flavour of case L. Build a variant
    ;; SET of peer members and nest the base member at EVERY level (so each level has
    ;; a variant head, just as case L's swap target exists at every level). Then
    ;; OPTIONALLY switch the variant head at each level to a differently-coloured
    ;; sibling and assert the colour that surfaces at every level. A variant switch
    ;; routes through the SAME component-swap as case L (keep-touched? true), so the
    ;; watcher auto-propagates it identically: the colour at level i is the switch at
    ;; the HIGHEST index j <= i that was applied, else the base member's colour. Same
    ;; asserter as L — the test of "a variant switch propagates like a swap".
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
            ;; same precedence as case L: the colour at level i is the switch at the
            ;; HIGHEST index j <= i that was applied (a switch propagates outward),
            ;; else base.
            (or (some (fn [j] (when (tm/applied? s (nth switches j)) (nth swap-colors j)))
                      (range i -1 -1))
                base-color))]
      (ftm/check
       done
       {:setup     setup/empty-situation
        :operation (tm/in-sequence
                    (concat
                     ;; the nesting lineage, and the variant set (members = [value color])
                     [(n/create-component m base-color)
                      (n/make-variant-container vset (mapv vector vals colors))]
                     ;; introduce the variant instance ONCE (innermost), then wrap it
                     ;; with plain nesting so each outer level CONTAINS the one below
                     ;; (progressive nesting, like case L's make-nested-component x3). nested-head
                     ;; at every level is then the variant (the deepest instance), so a
                     ;; switch at level i targets it and propagates OUTWARD via the
                     ;; watcher — exactly like case L's swap.
                     [(n/make-nested-component-with-variant m vset "v0")
                      (n/make-nested-component m)
                      (n/make-nested-component m)]
                     ;; optionally switch each level's variant head to its target sibling
                     (map (fn [sw] (tm/optional sw)) switches)
                     [(tm/test-that
                       (fn [s]
                         (doseq [i (range 3)]
                           (t/is (= (expected-at s i) (level-color s m i))
                                 (str "level " i)))))]))}))))

(t/deftest case-n-geometry-sync-with-rotated-instances
  ;; Regression sweep for #10109, with #13267's semantics as its complement: an
  ;; instance root's transformation is inherited, overridable content —
  ;; asymmetric to position, which is free per-instance placement.
  ;;
  ;; On the simple component-with-copy, sweep three axes: optionally rotate the
  ;; COPY as a whole (an override of its root's placement — must not block
  ;; propagation), optionally rotate the MAIN as a whole (inherited content —
  ;; an untouched copy must follow it), and apply ONE of a property edit
  ;; (fills) or a geometry edit (height) to the main child. In EVERY variant
  ;; the chosen edit must arrive at the copy child; the copy's rotation is its
  ;; own 45° if the copy was rotated (override wins), else the main's 45° if
  ;; the main was rotated (clean copy follows), else 0; and only a rotated
  ;; copy's ROOT is touched (:geometry-group) — its child merely follows and
  ;; stays untouched.
  (t/async
    done
    (let [rotate-copy (n/rotate :copy-root 45)
          rotate-main (n/rotate :main-root 45)
          edits       (tm/one-of
                       [(n/change-attr :main-instance :fills red)
                        (n/change-height :main-instance 80)])]
      (ftm/check
       done
       {:setup     setup/simple-component-with-labeled-copy
        :operation (tm/in-sequence [(tm/optional rotate-copy)
                                    (tm/optional rotate-main)
                                    edits])}
       (fn [situation]
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
           (t/is (nil? (:touched copy-child)))))))))



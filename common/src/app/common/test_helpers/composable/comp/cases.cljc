;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.cases
  "Component cases of the composable test model, run by the frontend
   interpreter (`frontend-tests.composable-tests.comp.sync-test`) and by the
   pure runner (`common-tests.logic.composable-sync-test`).

   A case is a zero-arg fn returning `{:setup :operation :asserter}`; the
   asserter gets the resulting situation of each variant. A case holds only
   user actions: the runners sync the copies, and the cases assert on that.

   The cases (`mem:frontend/composable-component-tests`):
     B — an override on a copy child survives a later main change (touched gate).
     C — a sweep (one-of) over several attribute changes, each propagating.
     D — a shape added to the main is structurally propagated (ref-integrity).
     E — a middle shape removed from the main; survivors keep order.
     F — reordering a shape in the main; order propagates, identity preserved.
     H — locality: a library main's change reaches the consuming file's copy on
         the explicit library sync (cross-file propagation).
     I — undo reverses an edit AND its propagation.
     P — redo applies an undone edit and its propagation again.
     K — edits at depth 0..2: the copy's own, else its main's, else the
         remote's; a reset keeps the main's.
     L — the swap sweep: swaps at any subset of nesting levels.
     M — the variant-switch sweep: case L with variant switches.
     O — a variant switch keeps a copy override only where the mains agree.
     Q — a detached copy's nested copies stay linked, one level up.
     R — update main pushes a copy override; the other copies follow.
     S — copies of a deleted component keep their data; restore syncs again.
     T — a duplicated copy keeps the source's overrides and stays linked.
     U — a copy moved into a main reaches that main's copies.
     V — a new edit after an undo empties the redo stack.
     W — one undo node undoes several steps over nested cascades.
     X — an undo committed before the edit's sync leaves copies unchanged
         (frontend only).
     AJ — one undo after a nested sync reverts only the reflow (F43, frontend
         only).
     Y — a text override merges with a main text change per subgroup.
     Z — a move of a main child reaches the copies (frontend only).
     AA..AE — swap outcomes: a copy's swap survives main changes; reset of a
         nested swap; switching twice; update main with a swapped child;
         detach or duplicate of a swapped copy.
     AF — a grid cell move in the main reaches a swapped copy child (F44).
     AG — a main reorder keeps the copy's nested copies aligned.
     AH, AI — a copy deleting a nested copy keeps the others aligned (AI also
         reflows, frontend only).
     N — main edits reach rotated copies (frontend only).
     Rotation — a touched copy child survives a main rotation (frontend only too)."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
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
   [app.common.types.shape.layout :as ctl]
   [clojure.test :as t]))

(def ^:private red "#ff0000")
(def ^:private green "#00ff00")
(def ^:private blue "#0000ff")
(def ^:private base-color "#aaaaaa")

(defn- fill-color
  [shape]
  (-> shape :fills first :fill-color))

(defn- depth-sweep
  "One variant per nesting depth 0, 1 and 2 of lineage `name`."
  [name]
  (tm/one-of [(tm/skip)
              (n/make-nested-component name)
              (n/make-nested-component name 2)]))

(defn copy-override-survives-later-main-change
  "Case B. A copy overrides its child's fill, then the main changes it. The
   override survives, touched."
  []
  (let [override (n/change-property :copy-child :fills green)]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence
                 [override
                  (n/change-property :main-child :fills red)])
     :asserter  (fn [situation]
                  (let [copy-child (setup/copy-instance situation)]
                    (t/is (n/has-property-of override copy-child))
                    (t/is (contains? (:touched copy-child) :fill-group))
                    (t/is (some? (:shape-ref copy-child)))))}))

(defn attribute-sweep-propagates-to-clean-copy
  "Case C. A main change of fill or opacity reaches a clean copy."
  []
  (let [sweep (tm/one-of
               [(n/change-property :main-child :fills red)
                (n/change-property :main-child :opacity 0.5)])]
    {:setup     setup/simple-component-with-copy
     :operation (tm/in-sequence [sweep])
     :asserter  (fn [situation]
                  (let [chosen (tm/get-choice situation sweep)]
                    (t/is (some? chosen))
                    (t/is (n/has-property-of chosen (setup/copy-instance situation)))))}))

(defn add-shape-to-main-propagates-to-clean-copy
  "Case D. A shape added to the main appears in the copy, linked to it and
   untouched."
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
  "Case E. The middle of three main children is deleted; the copy loses it
   and keeps the others in order, linked and untouched."
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
  "Case F. The main moves its first child to the end; the copy follows, its
   children linked and untouched."
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
   single undo reverses the edit and its propagation, so main and copy are
   back to the first fill and the copy is untouched."
  []
  {:setup     setup/simple-component-with-labeled-copy
   :operation (tm/in-sequence [(n/change-property :main-child :fills red) (n/undo)])
   :asserter  (fn [situation]
                (let [copy (setup/copy-instance situation)]
                  (t/is (= setup/labeled-fill (fill-color (setup/main-instance situation))))
                  (t/is (= setup/labeled-fill (fill-color copy)))
                  (t/is (nil? (:touched copy)))))})

(defn redo-reapplies-edit-and-its-propagation
  "Case P. Change the main (which propagates to the clean copy), undo, then
   redo. The redo applies the edit and its propagation again, so the copy
   shows the main's new value and is left untouched."
  []
  (let [change (n/change-property :main-child :fills red)]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [change (n/undo) (n/redo)])
     :asserter  (fn [situation]
                  (let [copy (setup/copy-instance situation)
                        main (setup/main-instance situation)]
                    (t/is (n/has-property-of change main))
                    (t/is (n/has-property-of change copy))
                    (t/is (nil? (:touched copy)))))}))

(defn library-change-propagates-across-file-boundary-on-sync
  "Case H. The main lives in a linked library, the copy in the consuming
   (current) file. The library main has diverged (the setup filled it `red`,
   leaving the copy stale). In-file sync does not cross the file boundary;
   the app propagates through the library update action, so the operation is
   `sync-from-library`."
  []
  {:setup     #(setup/cross-file-component-with-copy red)
   :operation (tm/in-sequence [(n/sync-from-library)])
   :asserter  (fn [situation]
                (t/is (= red (fill-color (setup/copy-instance situation)))))})

(defn synchronisation-scenarios
  "Case K. At nesting depth 0, 1 or 2, optionally the remote main, the main
   and the copy change their rect. The copy shows its own change, else the
   main's, else the remote's, else the first fill. Then the copy overrides
   its rect and resets: it shows the main's change, else the remote's, else
   the first fill."
  []
  (let [m             "main"
        change-remote (n/change-property (n/remote-rect-of m) :fills red)
        change-main   (n/change-property (n/main-rect-of m) :fills green)
        change-copy   (n/change-property (n/copy-rect-of m) :fills blue)
        force-copy    (n/change-property (n/copy-rect-of m) :fills blue)
        copy-color    (fn [s] (fill-color (tm/shape-by-id s (n/lineage-copy-rect s m))))
        main-color    (fn [s]
                        (cond
                          (tm/applied? s change-main)   green
                          (tm/applied? s change-remote) red
                          :else                         base-color))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (depth-sweep m)
                  (n/instantiate-copy m)
                  (tm/optional change-remote)
                  (tm/optional change-main)
                  (tm/optional change-copy)
                  (tm/test-that
                   (fn [s]
                     (t/is (= (if (tm/applied? s change-copy) blue (main-color s))
                              (copy-color s)))))
                  force-copy
                  (tm/test-that (fn [s] (t/is (= blue (copy-color s)))))
                  (n/reset-copy-instance m)
                  (tm/test-that (fn [s] (t/is (= (main-color s) (copy-color s)))))])}))

(defn- level-color
  "The fill of the rect at lineage `name`'s nesting level `i`."
  [s name i]
  (fill-color (tm/shape-by-id s (n/level-rect s name i))))

(def ^:private swap-colors ["#ff0000" "#00ff00" "#0000ff"])   ; level 0/1/2 targets

(defn- expected-level-color
  "The color at nesting level `i` after the optional per-level `steps` (swaps
   or switches; step j gives `swap-colors` j): a step at level j reaches
   every level from j outwards, so it is the color of the applied step at
   the highest j <= i, else the base color."
  [s steps i]
  (or (some #(when (tm/applied? s (nth steps %)) (nth swap-colors %))
            (range i -1 -1))
      base-color))

(defn swap-scenarios
  "Case L. A three-level nesting optionally swaps the nested component at
   each level for one of its own colour. Each level shows the colour of
   `expected-level-color`."
  []
  (let [m       "main"
        targets ["s0" "s1" "s2"]
        ;; swap i swaps level i for lineage i, of colour i
        swaps   (mapv (fn [i] (n/swap-component m i (nth targets i))) (range 3))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 (concat
                  [(n/create-component m base-color :size 100)]
                  (map-indexed (fn [i c] (n/create-component (nth targets i) c :size 100))
                               swap-colors)
                  [(n/make-nested-component m) (n/make-nested-component m) (n/make-nested-component m)]
                  (map (fn [sw] (tm/optional sw)) swaps)
                  [(tm/test-that
                    (fn [s]
                      (doseq [i (range 3)]
                        (t/is (= (expected-level-color s swaps i) (level-color s m i))
                              (str "level " i)))))]))}))

(defn variant-switch-scenarios
  "Case M. Case L with variant switches: a variant member nested and wrapped
   twice, so every level holds its image, and an optional switch at each
   level to a member of its own colour."
  []
  (let [m       "main"        ; the nesting lineage (holds the nesting-data)
        vset    "vset"        ; the variant set
        values  ["v0" "v1" "v2" "v3"]
        colors  (into [base-color] swap-colors) ; base + sibling colours
        ;; switch i switches level i to member i+1, of colour i
        switches (mapv (fn [i] (n/switch-variant (n/nested-head-of m i) (nth values (inc i))))
                       (range 3))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 (concat
                  [(n/create-component m base-color :size 100)
                   (n/make-variant-container vset (mapv vector values colors) :size 100)]
                  ;; each outer level holds the one below, so a switch
                  ;; reaches the outer levels
                  [(n/make-nested-component-with-variant m vset "v0")
                   (n/make-nested-component m)
                   (n/make-nested-component m)]
                  (map (fn [sw] (tm/optional sw)) switches)
                  [(tm/test-that
                    (fn [s]
                      (doseq [i (range 3)]
                        (t/is (= (expected-level-color s switches i) (level-color s m i))
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
                        (t/is (= red (fill-color rect)))
                        (t/is (not (contains? (:touched rect) :fill-group)))))))}))

(defn detach-keeps-nested-copies-linked
  "Case Q. A copy at nesting depth 0, 1 or 2 is detached, then the remote
   (deepest) main changes. Optionally the copy's near main overrides the rect
   before the detach. At depth 0 the rect is detached: it keeps the value it
   had and loses its links. Deeper, the rect lies in a first-level nested
   copy, which stays a copy one level up and inherits the near main's touched
   groups (`mem:common/component-sync-contract`): with the near main's
   override it keeps that value and is touched; otherwise it follows the
   remote main."
  []
  (let [m             "main"
        change-main   (n/change-property (n/main-rect-of m) :fills green)
        change-remote (n/change-property (n/remote-rect-of m) :fills blue)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m red :size 100)
                  (depth-sweep m)
                  (n/instantiate-copy m)
                  (tm/optional change-main)
                  (n/detach (n/copy-head-of m))
                  change-remote])
     :asserter  (fn [s]
                  (let [rect    (tm/shape-by-id s (n/lineage-copy-rect s m))
                        color   (fill-color rect)
                        nested? (pos? (n/nesting-depth s m))
                        main?   (tm/applied? s change-main)]
                    (if nested?
                      (do
                        (t/is (some? (:shape-ref rect)))
                        (t/is (= (if main? green blue) color))
                        (t/is (= main? (contains? (:touched rect) :fill-group))))
                      (do
                        (t/is (nil? (:shape-ref rect)))
                        (t/is (nil? (:touched rect)))
                        (t/is (= (if main? green red) color))))))}))

(defn update-main-reaches-other-copies
  "Case R. A component at nesting depth 0, 1 or 2 has two copies. The first
   copy overrides its rect and pushes the override to the main (update
   main); optionally the second copy overrides its rect first. The main rect
   takes the pushed value, touched when it lies in a nested copy (it then
   differs from its own near main). The first copy keeps the value with its
   touched group cleared. The second copy keeps its own override, or follows
   the main when it had none."
  []
  (let [m              "main"
        other-override (n/change-property (n/nth-copy-rect-of m 1) :fills blue)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m red)
                  (depth-sweep m)
                  (n/instantiate-copy m)
                  (n/instantiate-copy m)
                  (tm/optional other-override)
                  (n/change-property (n/nth-copy-rect-of m 0) :fills green)
                  (n/update-main (n/nth-copy-head-of m 0))])
     :asserter  (fn [s]
                  (let [rect    (fn [id] (tm/shape-by-id s id))
                        fill?   (fn [shape] (contains? (:touched shape) :fill-group))
                        nested? (pos? (n/nesting-depth s m))
                        other?  (tm/applied? s other-override)
                        main    (rect (n/lineage-rect s m))
                        copy-0  (rect (n/lineage-nth-copy-rect s m 0))
                        copy-1  (rect (n/lineage-nth-copy-rect s m 1))]
                    (t/is (= green (fill-color main)))
                    (t/is (= nested? (fill? main)))
                    (t/is (= green (fill-color copy-0)))
                    (t/is (not (fill? copy-0)))
                    (t/is (= (if other? blue green) (fill-color copy-1)))
                    (t/is (= other? (fill? copy-1)))))}))

(defn deleted-component-copies-sync-again-after-restore
  "Case S. A component has a copy on its main's page and another on a second
   page; optionally the first copy overrides its child. The main changes, the
   component is deleted, then restored, and the main changes again. While it
   is deleted, both copies keep their links and the values the first change
   gave them. After the restore, the second change reaches both copies, except
   where the first copy overrode its child."
  []
  (let [override      (n/change-property :copy-instance :fills green)
        first-change  (n/change-property :main-instance :fills red)
        deletion      (n/delete-component :main-root)
        second-change (n/change-property :main-instance :fills blue)]
    {:setup     setup/simple-component-with-copies-on-two-pages
     :operation (tm/in-sequence
                 [(tm/optional override)
                  first-change
                  deletion
                  (tm/test-that
                   (fn [s]
                     (let [copy  (setup/copy-instance s)
                           other (setup/other-page-copy-instance s)]
                       (t/is (some? (:shape-ref copy)))
                       (t/is (some? (:shape-ref other)))
                       (t/is (= (if (tm/applied? s override) green red) (fill-color copy)))
                       (t/is (= red (fill-color other))))))
                  (n/restore-component deletion)
                  second-change])
     :asserter  (fn [s]
                  (let [copy  (setup/copy-instance s)
                        other (setup/other-page-copy-instance s)]
                    (t/is (= blue (fill-color (setup/main-instance s))))
                    (t/is (= (if (tm/applied? s override) green blue) (fill-color copy)))
                    (t/is (= blue (fill-color other)))
                    (t/is (nil? (:touched other)))))}))

(defn duplicated-copy-keeps-overrides-and-link
  "Case T. A copy at nesting depth 0, 1 or 2 is duplicated, optionally after
   overriding its rect, and then the main changes. The duplicate keeps the
   source's values and touched groups (`mem:common/component-sync-contract`)
   and stays linked: the main change reaches it unless the rect was
   overridden, exactly as it reaches the source copy."
  []
  (let [m           "main"
        override    (n/change-property (n/copy-rect-of m) :fills green)
        duplication (n/duplicate (n/copy-head-of m))
        change-main (n/change-property (n/main-rect-of m) :fills blue)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m red)
                  (depth-sweep m)
                  (n/instantiate-copy m)
                  (tm/optional override)
                  duplication
                  change-main])
     :asserter  (fn [s]
                  (let [head      ((n/duplicate-head-of duplication) s)
                        dup-rect  (tm/shape-by-id s (n/corresponding-shape-id s head (n/lineage-rect s m)))
                        copy-rect (tm/shape-by-id s (n/lineage-copy-rect s m))
                        expected  (if (tm/applied? s override) green blue)]
                    (t/is (some? (:shape-ref dup-rect)))
                    (t/is (= expected (fill-color dup-rect)))
                    (t/is (= (:touched copy-rect) (:touched dup-rect)))
                    (t/is (= expected (fill-color copy-rect)))))}))

(defn nested-copy-reaches-copies-of-the-receiving-main
  "Case U. A copy of an inner component is moved into the main of an outer
   component that already has a copy (nest copy, a user action). The outer
   copy gets a nested copy of the inner component. Optionally that nested
   copy overrides its rect; then the inner main changes. The moved copy
   follows the inner main, and so does the outer copy's nested copy, unless
   it overrode the rect."
  []
  (let [outer         "outer"
        inner         "inner"
        ;; the image, in the outer copy, of the inner copy moved into the main
        nested-rect   (fn [s]
                        (n/corresponding-shape-id s
                                                  ((n/copy-head-of outer) s)
                                                  (n/lineage-copy-rect s inner)))
        override      (n/change-property nested-rect :fills green)
        change-inner  (n/change-property (n/main-rect-of inner) :fills blue)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component outer red :size 100)
                  (n/create-component inner base-color :size 40)
                  (n/instantiate-copy outer)
                  (n/instantiate-copy inner)
                  (n/nest-copy (n/copy-head-of inner) (n/main-head-of outer))
                  (tm/optional override)
                  change-inner])
     :asserter  (fn [s]
                  (let [moved  (tm/shape-by-id s (n/lineage-copy-rect s inner))
                        nested (tm/shape-by-id s (nested-rect s))]
                    (t/is (= blue (fill-color moved)))
                    (t/is (nil? (:touched moved)))
                    (if (tm/applied? s override)
                      (do
                        (t/is (= green (fill-color nested)))
                        (t/is (contains? (:touched nested) :fill-group)))
                      (do
                        (t/is (= blue (fill-color nested)))
                        (t/is (nil? (:touched nested)))))))}))

(defn new-edit-after-undo-clears-redo
  "Case V. An edit to the main is undone, a second edit is made and undone
   too, and then redo runs twice. The second edit emptied the redo stack, so
   the first redo applies the second edit with its propagation and the second
   redo has nothing left to apply: main and copy show the second edit, and
   the copy is untouched."
  []
  (let [first-edit  (n/change-property :main-instance :fills red)
        second-edit (n/change-property :main-instance :fills green)]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [first-edit (n/undo) second-edit (n/undo) (n/redo) (n/redo)])
     :asserter  (fn [s]
                  (let [copy (setup/copy-instance s)]
                    (t/is (n/has-property-of second-edit (setup/main-instance s)))
                    (t/is (n/has-property-of second-edit copy))
                    (t/is (nil? (:touched copy)))))}))

(defn undo-several-steps-over-nested-cascades
  "Case W. At nesting depth 0, 1 or 2, the remote main, the main and the copy
   each change their rect (the remote change cascades through every level),
   then a single undo node undoes the three steps. Every rect is back to its
   first colour and no rect is touched. The components have a real size:
   with the minimal one, the frontend reflows after the remote change land in
   undo entries of their own (F43), so three undos stop short."
  []
  (let [m "main"]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m red :size 100)
                  (depth-sweep m)
                  (n/instantiate-copy m)
                  (n/change-property (n/remote-rect-of m) :fills blue)
                  (n/change-property (n/main-rect-of m) :fills green)
                  (n/change-property (n/copy-rect-of m) :fills base-color)
                  (n/undo 3)])
     :asserter  (fn [s]
                  (doseq [id [((n/remote-rect-of m) s)
                              ((n/main-rect-of m) s)
                              (n/lineage-copy-rect s m)]]
                    (let [rect (tm/shape-by-id s id)]
                      (t/is (= red (fill-color rect)))
                      (t/is (empty? (:touched rect))))))}))

(defn undo-before-sync-leaves-copies-unchanged
  "Case X (frontend only). An edit to the main is undone in the same step, so
   the undo commits while the edit's sync is still pending. The file is back
   to its state before the edit: the main and its copy show the first value
   and the copy is untouched."
  []
  {:setup     setup/simple-component-with-labeled-copy
   :operation (tm/in-sequence [(n/undo-before-sync :main-instance :fills red)])
   :asserter  (fn [s]
                (let [copy (setup/copy-instance s)]
                  (t/is (= setup/labeled-fill (fill-color (setup/main-instance s))))
                  (t/is (= setup/labeled-fill (fill-color copy)))
                  (t/is (nil? (:touched copy)))))})

(defn reflow-after-nested-sync-is-its-own-undo-step
  "Case AJ (frontend only). A component nested two levels deep, with the
   minimal frame size, has a copy; its remote main changes the rect, then one
   undo runs. The transform math rewrites the minimal nested frames with float
   noise, so the sync of the change reflows them, and the reflow commits land
   in undo entries of their own (F43). One undo should revert the change and
   its sync; it reverts only the last reflow entry, so the remote rect keeps
   the change. How many entries the reflow makes depends on when its 100 ms
   buffer flushes, so the case checks the value one undo leaves, never the
   undo stack."
  []
  (let [m      "main"
        remote (n/remote-rect-of m)]
    {:setup      setup/empty-situation
     ;; the round trip's signature depends on when the reflow buffer flushes
     :undo-check {:off "the case undoes its own step (F43)"}
     :operation  (tm/in-sequence
                  [(n/create-component m red)
                   (n/make-nested-component m)
                   (n/make-nested-component m)
                   (n/instantiate-copy m)
                   (n/change-property remote :fills blue)
                   (n/undo)])
     :asserter   (fn [s]
                   (tm/known-failure "F43" red blue
                                     (fill-color (tm/shape-by-id s (remote s)))))}))

(defn text-override-merges-with-main-text-change
  "Case Y. A main text changes its characters or its font size; before that,
   optionally, its copy changes one of them. Sync merges per text subgroup: the
   copy keeps what it edited and takes the rest from the main. So the copy
   shows its own characters if it edited them, else the main's, and the same
   for the font size. An edited copy has `:content-group` and the subgroup of
   its edit touched; an unedited one has nothing touched. The text starts with
   the name generated from its content, so an edit of its characters renames
   it, an automatic rename that must not touch `:name-group` (decision (d))."
  []
  (let [copy-edit (tm/one-of [(n/edit-text :copy-instance :text "copy text")
                              (n/edit-text :copy-instance :font-size "32")])
        main-edit (tm/one-of [(n/edit-text :main-instance :text "main text")
                              (n/edit-text :main-instance :font-size "24")])
        subgroup  {:text :text-content-text :font-size :text-content-attribute}]
    {:setup     setup/simple-text-component-with-copy
     :operation (tm/in-sequence [(tm/optional copy-edit) main-edit])
     :asserter  (fn [s]
                  (let [copy   (setup/copy-instance s)
                        main   (setup/main-instance s)
                        edited (tm/get-choice s copy-edit)]
                    (doseq [edit [:text :font-size]]
                      (t/is (= (if (= edit (:edit edited))
                                 (:value edited)
                                 (n/text-span-value main edit))
                               (n/text-span-value copy edit))
                            (str (name edit))))
                    (cond
                      (nil? edited)
                      (t/is (empty? (:touched copy)))

                      ;; the automatic rename touches the name
                      (= :text (:edit edited))
                      (tm/known-failure "F27"
                                        #{:content-group :text-content-text}
                                        #{:content-group :text-content-text :name-group}
                                        (:touched copy))

                      :else
                      (t/is (= #{:content-group (subgroup (:edit edited))}
                               (:touched copy))))))}))

;; ---------------------------------------------------------------------------
;; Swap outcomes
;;
;; What a swap must give, asserted on values and structure, not on how the
;; swap is stored. Each case builds a component "main" whose main nests a
;; copy once, and a copy of it, whose nested head the case swaps. Real sizes
;; keep the frontend reflows out of the undo entries (F43).
;; ---------------------------------------------------------------------------

(defn- head-component
  "The component id of the copy head bound to target fn `head`."
  [s head]
  (:component-id (tm/shape-by-id s (head s))))

(defn- head-rect-color
  "The fill of the rect inside the copy head bound to target fn `head`."
  [s head]
  (let [objects (:objects (thf/current-page (tm/file s)))]
    (->> (cfh/get-children-ids objects (head s))
         (map #(get objects %))
         (d/seek #(= :rect (:type %)))
         (fill-color))))

(defn copy-swap-survives-main-changes
  "Case AA. A copy swaps its nested copy for another component. Optionally the
   main then swaps its own nested copy for a third component, and optionally
   the nested component's main changes. The copy's swap survives both: its
   nested head stays an instance of the component it swapped to and shows its
   rect."
  []
  (let [m         "main"
        nested    (n/nested-head-in m)
        main-swap (n/swap-component m 0 "s2")
        main-edit (n/change-property (n/remote-rect-of m) :fills blue)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/create-component "s1" red :size 100)
                  (n/create-component "s2" green :size 100)
                  (n/make-nested-component m)
                  (n/instantiate-copy m)
                  (n/swap-shape nested "s1")
                  (tm/optional main-swap)
                  (tm/optional main-edit)])
     :asserter  (fn [s]
                  (t/is (= (n/lineage-component-id s "s1") (head-component s nested)))
                  (t/is (= red (head-rect-color s nested))))}))

(defn reset-of-nested-swap-keeps-top-copy-overrides
  "Case AB. A copy overrides its own root's opacity and swaps its nested copy;
   then the nested copy is reset. The reset undoes the swap (the nested head
   is an instance of the original component again) and changes nothing
   outside the nested copy: the root keeps its opacity override and its
   touched group (`mem:common/component-sync-contract`)."
  []
  (let [m      "main"
        nested (n/nested-head-in m)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/create-component "s1" red :size 100)
                  (n/make-nested-component m)
                  (n/instantiate-copy m)
                  (n/change-property (n/copy-head-of m) :opacity 0.5)
                  (n/swap-shape nested "s1")
                  (n/reset nested)])
     :asserter  (fn [s]
                  (let [root     (tm/shape-by-id s ((n/copy-head-of m) s))
                        ;; the component the main's own nested copy instantiates
                        original (:component-id (tm/shape-by-id s (:nested-head (n/lineage-nesting s m 0))))]
                    (t/is (= original (head-component s nested)))
                    (t/is (= base-color (head-rect-color s nested)))
                    ;; the reset climbs to the top copy and clears its overrides
                    (tm/known-failure "F12" 0.5 nil (:opacity root))
                    (tm/known-failure "F12" #{:layer-effects-group} nil (:touched root))))}))

(defn second-variant-switch-keeps-root-overrides
  "Case AC. A copy nests a variant; the copy overrides the opacity of the
   variant's root and switches it to a second member, then to a third. All
   members agree on that opacity, so each switch carries the override
   (decision (a)): the root ends as an instance of the third member with the
   opacity override, touched."
  []
  (let [m      "main"
        nested (n/nested-head-in m)
        root   (fn [s] (tm/shape-by-id s (nested s)))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-variant-container "vset" [["a" base-color] ["b" red] ["c" green]]
                                            :size 100)
                  (n/make-nested-component-with-variant m "vset" "a")
                  (n/instantiate-copy m)
                  (n/change-property nested :opacity 0.5)
                  (n/switch-variant nested "b")
                  (tm/test-that (fn [s] (t/is (= 0.5 (:opacity (root s))) "after the first switch")))
                  (n/switch-variant nested "c")])
     :asserter  (fn [s]
                  (t/is (= (n/variant-member-component-id s "vset" "c") (head-component s nested)))
                  (t/is (= green (head-rect-color s nested)))
                  ;; the second switch of a head with a swap slot loses its overrides
                  (tm/known-failure "F29" 0.5 nil (:opacity (root s)))
                  (tm/known-failure "F29" true false
                                    (contains? (:touched (root s)) :layer-effects-group)))}))

(defn update-main-pushes-a-swapped-child
  "Case AD. Of two copies, the first swaps its nested copy and pushes that to
   the main (update main). Per the swap model of decision (h), update main
   pushes the copy's component: the main's nested copy becomes an instance of
   it, and so does the second copy's."
  []
  (let [m             "main"
        main-nested   (n/nested-head-under (n/main-head-of m))
        first-nested  (n/nested-head-under (n/nth-copy-head-of m 0))
        second-nested (n/nested-head-under (n/nth-copy-head-of m 1))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/create-component "s1" red :size 100)
                  (n/make-nested-component m)
                  (n/instantiate-copy m)
                  (n/instantiate-copy m)
                  (n/swap-shape first-nested "s1")
                  (n/update-main (n/nth-copy-head-of m 0))])
     :asserter  (fn [s]
                  (let [s1 (n/lineage-component-id s "s1")]
                    (t/is (= s1 (head-component s main-nested)))
                    (t/is (= s1 (head-component s second-nested)))
                    (t/is (= red (head-rect-color s second-nested)))))}))

(defn detached-or-duplicated-swapped-copy-follows-its-component
  "Case AE. A copy swaps its nested copy; then the copy is detached, or
   duplicated; then the main of the component it swapped to changes. Detached,
   the nested copy becomes a top-level copy of that component; duplicated, the
   duplicate's nested copy is one too. Either way it follows the change."
  []
  (let [m           "main"
        nested      (n/nested-head-in m)
        duplication (n/duplicate (n/copy-head-of m))
        dup-nested  (n/nested-head-under (n/duplicate-head-of duplication))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/create-component "s1" red :size 100)
                  (n/make-nested-component m)
                  (n/instantiate-copy m)
                  (n/swap-shape nested "s1")
                  (tm/one-of [(n/detach (n/copy-head-of m)) duplication])
                  (n/change-property (n/main-rect-of "s1") :fills blue)])
     :asserter  (fn [s]
                  (let [s1 (n/lineage-component-id s "s1")]
                    (t/is (= s1 (head-component s nested)))
                    (t/is (= blue (head-rect-color s nested)))
                    (when (tm/applied? s duplication)
                      (t/is (= s1 (head-component s dup-nested)))
                      (t/is (= blue (head-rect-color s dup-nested))))))}))

(defn grid-cell-move-reaches-a-swapped-copy-child
  "Case AF. A component's main is a grid board holding two nested copies;
   optionally its copy swaps the first of them. Then the main moves that child
   to the free third column. The copy's child at that position, swapped or
   not, moves to the third column too."
  []
  (let [swap   (n/swap-shape :copy-child :other)
        column (fn [s board child]
                 (:column (ctl/get-cell-by-shape-id (tm/role-shape s board)
                                                    (:id (tm/role-shape s child)))))]
    {:setup     setup/grid-component-with-copy
     :operation (tm/in-sequence [(tm/optional swap)
                                 (n/move-to-cell :main-root :main-child 1 3)])
     :asserter  (fn [s]
                  (t/is (= 3 (column s :main-root :main-child)))
                  (if (tm/applied? s swap)
                    ;; the grid sync pairs a slotted child only by its raw slot
                    (tm/known-failure "F44" 3 1 (column s :copy-root :copy-child))
                    (t/is (= 3 (column s :copy-root :copy-child)))))}))

(defn main-reorder-keeps-copy-sub-heads-aligned
  "Case AG (the plugin's MainReorderKeepsCopySlots). A main holds three
   nested copies side by side; it moves the first to the end. The copy follows:
   each of its children references the main's child at the same position."
  []
  (let [move (n/move-child :sub1 :row-root 3)]
    {:setup      setup/component-with-sibling-instances
     ;; the undo of the sync's move leaves the copy children reordered
     :undo-check {:known-failures [{:bug   "F41"
                                    :fails {:undo         #{[:object :row-copy :shapes]}
                                            :variant-undo #{[:object :row-copy :shapes]}}
                                    :op    move}]}
     :operation  (tm/in-sequence [move])
     :asserter   (fn [s]
                   (t/is (= [] (setup/misaligned-sub-heads s))))}))

(defn copy-sub-head-delete-keeps-others-aligned
  "Case AH (the plugin's CopySubheadDeletePreservesSlots, without its
   reflow). A grid main holds three nested copies; its copy optionally deletes
   its first or its last one. The copy's remaining children still reference
   the main's children at the same positions."
  []
  {:setup     #(setup/component-with-sibling-instances :grid)
   :operation (tm/in-sequence [(tm/optional (tm/one-of [(n/remove-child :copy-sub1)
                                                        (n/remove-child :copy-sub3)]))])
   :asserter  (fn [s]
                (t/is (= [] (setup/misaligned-sub-heads s))))})

(defn copy-sub-head-delete-and-reflow-keeps-others-aligned
  "Case AI (frontend only; the plugin's CopySubheadDeletePreservesSlots
   with its reflow). As case AH, then the copy root optionally changes height,
   which reflows its grid. The copy's remaining children still reference the
   main's children at the same positions."
  []
  {:setup     #(setup/component-with-sibling-instances :grid)
   :operation (tm/in-sequence [(tm/optional (tm/one-of [(n/remove-child :copy-sub1)
                                                        (n/remove-child :copy-sub3)]))
                               (tm/optional (n/change-height :copy-root 500))])
   :asserter  (fn [s]
                (t/is (= [] (setup/misaligned-sub-heads s))))})

(defn geometry-sync-with-rotated-instances
  "Case N (frontend only). Optionally the copy rotates, optionally the main
   rotates, then the main child changes its fill or its height. The change
   reaches the copy child. A copy root's rotation is inherited content that a
   copy may override: the copy shows its own rotation, else the main's, else
   none. Only a rotated copy root is touched (:geometry-group); its child
   follows untouched."
  []
  (let [rotate-copy (n/rotate :copy-root 45)
        rotate-main (n/rotate :main-root 45)
        edits       (tm/one-of
                     [(n/change-property :main-instance :fills red)
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
                    (t/is (some? chosen))
                    (t/is (n/has-property-of chosen copy-child))
                    (t/is (mth/close? (or (:rotation copy-root) 0) expected-rotation))
                    (t/is (mth/close? (or (:rotation copy-child) 0) expected-rotation))
                    (t/is (= (if (tm/applied? situation rotate-copy) #{:geometry-group} nil)
                             (:touched copy-root)))
                    (t/is (nil? (:touched copy-child)))))}))

(defn main-child-move-reaches-copies
  "Case Z (frontend only). A child of the
   main moves; optionally the copy moved its own child before. The copy child
   keeps its offset from the copy root equal to the main child's from the main
   root, so it follows the move, unless it moved itself: then it keeps its own
   offset and its touched `:geometry-group`. The copy root stays untouched."
  []
  (let [copy-move (n/move-shape :copy-instance 7 3)
        main-move (n/move-shape :main-instance 10 5)
        offset    (fn [child root]
                    [(- (-> child :selrect :x) (-> root :selrect :x))
                     (- (-> child :selrect :y) (-> root :selrect :y))])]
    {:setup     setup/simple-component-with-labeled-copy
     :operation (tm/in-sequence [(tm/optional copy-move) main-move])
     :asserter  (fn [s]
                  (let [main-offset (offset (setup/main-instance s) (setup/main-root s))
                        copy-offset (offset (setup/copy-instance s) (setup/copy-root s))
                        moved?      (tm/applied? s copy-move)]
                    (if moved?
                      (do
                        (t/is (= (mapv - main-offset [10 5]) (mapv - copy-offset [7 3])))
                        (t/is (contains? (:touched (setup/copy-instance s)) :geometry-group)))
                      (do
                        (t/is (= main-offset copy-offset))
                        (t/is (empty? (:touched (setup/copy-instance s))))))
                    (t/is (empty? (:touched (setup/copy-root s))))))}))

(defn touched-copy-child-survives-main-rotation
  "Frontend only. A copy rotates its root and resizes its child, then the
   main rotates. The child's touched :geometry-group shields it: its height
   and its position relative to the copy root do not change."
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
                    (t/is (contains? (:touched copy-root) :geometry-group))
                    (t/is (contains? (:touched copy-child) :geometry-group))
                    (t/is (mth/close? (:height copy-child) 60))
                    (t/is (mth/close? (:x @before) (:x after) 0.5) (str "rel-x " (:x @before) " -> " (:x after)))
                    (t/is (mth/close? (:y @before) (:y after) 0.5) (str "rel-y " (:y @before) " -> " (:y after)))))}))

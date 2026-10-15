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
     AK — update main pushes a copy's grid cells to a grid main.
     AL — update main renames the component after a copy root rename.
     AM — update main pushes a swap made inside a group.
     AN — a flex child's margin override through sync, reset and update main.
     AO — a copy dropped into a grid cell of a main reaches the main's copies.
     AP — a library main's middle child removal reaches a copy in another file.
     AQ — a main change reaches a copy nested in a sub-board of the outer main.
     AR — variant property edits keep the copies of a member as they are; a
         switch then finds its member by the edited properties.
     AS — an added variant joins the set and is a switch target for copies.
     AT..AX — variant management: transform a component into a variant (AT),
         combine components (AU), rename a variant (AV, F6), the shortcuts
         that add members (AX), all frontend only, and rename a member in the
         layers panel, remove empty properties and set a member's error
         (AW). Copies of the members keep their link and overrides.
     AY — a border radius token on a main reaches untouched copies, and its
         removal too; a copy's own token survives.
     AZ — a nested copy pasted out of its main keeps the overrides the main
         gave it (F17).
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
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [clojure.walk :as walk]))

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
                  (let [chosen (tm/get-choice situation sweep)
                        copy   (setup/copy-instance situation)]
                    (t/is (some? chosen))
                    (t/is (n/has-property-of chosen copy))
                    (t/is (nil? (:touched copy)))))}))

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
  "Case F. The main moves its first child one place down; the copy follows,
   its children in the main's order, linked and untouched."
  []
  (let [move (n/move-child :main-child1 :main-root 2)]
    {:setup      setup/component-with-many-children
     :operation  (tm/in-sequence [move])
     :asserter   (fn [situation]
                   (let [copy-root (setup/copy-root situation)
                         order     (vec (:shapes copy-root))
                         c1        (setup/copy-child situation 1)
                         c2        (setup/copy-child situation 2)
                         c3        (setup/copy-child situation 3)]
                     (t/is (= (:shapes (setup/main-root situation))
                              (mapv #(:shape-ref (tm/shape-by-id situation %)) order)))
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
   :operation (tm/in-sequence
               [(n/change-property :main-child :fills red)
                (tm/test-that
                 (fn [s] (t/is (= red (fill-color (setup/copy-instance s))) "synced before the undo")))
                (n/undo)])
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
                (let [copy (setup/copy-instance situation)]
                  (t/is (= red (fill-color copy)))
                  (t/is (nil? (:touched copy)))))})

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
                  (tm/test-that
                   (fn [s]
                     (t/is (= (main-color s) (copy-color s)))
                     (t/is (empty? (:touched (tm/shape-by-id s (n/lineage-copy-rect s m)))))))])}))

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
        agree      (n/make-variant-container "vset" [["a" base-color] ["b" base-color]] :size 100)
        differ     (n/make-variant-container "vset" [["a" base-color] ["b" red]] :size 100)
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
                 [(n/create-component m base-color :size 100)
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
                 [(n/create-component m red :size 100)
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
                    (t/is (= (when (tm/applied? s override) #{:fill-group}) (:touched copy)))
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
                 [(n/create-component m red :size 100)
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
   noise, so the sync of the change reflows them. The reflow joins the undo
   group of the change, so one undo reverts the change, its sync and the
   reflow: the remote rect is back to red. How many entries the reflow makes
   depends on when its 100 ms buffer flushes, so the case checks the value
   one undo leaves, never the undo stack."
  []
  (let [m      "main"
        remote (n/remote-rect-of m)]
    {:setup      setup/empty-situation
     :undo-check {:off "the case undoes its own step"}
     :operation  (tm/in-sequence
                  [(n/create-component m red)
                   (n/make-nested-component m)
                   (n/make-nested-component m)
                   (n/instantiate-copy m)
                   (n/change-property remote :fills blue)
                   (n/undo)])
     :asserter   (fn [s]
                   (t/is (= red (fill-color (tm/shape-by-id s (remote s))))))}))

(defn text-override-merges-with-main-text-change
  "Case Y. A main text changes its characters or its font size; before that,
   optionally, its copy changes one of them. Sync merges per text override
   group: the copy keeps what it edited and takes the rest from the main. So
   the copy shows its own characters if it edited them, else the main's, and
   the same for the font size. An edited copy has `:content-group` and the
   group of its edit touched; an unedited one has nothing touched. The text starts with
   the name generated from its content, so an edit of its characters renames
   it, an automatic rename that must not touch `:name-group` (decision (d))."
  []
  (let [copy-edit (tm/one-of [(n/edit-text :copy-instance :text "copy text")
                              (n/edit-text :copy-instance :font-size "32")])
        main-edit (tm/one-of [(n/edit-text :main-instance :text "main text")
                              (n/edit-text :main-instance :font-size "24")])
        subgroup  {:text :text-content-text :font-size :text-content-size}]
    {:setup     setup/simple-text-component-with-copy
     :operation (tm/in-sequence [(tm/optional copy-edit) main-edit])
     :asserter  (fn [s]
                  (let [copy   (setup/copy-instance s)
                        main   (setup/main-instance s)
                        edited (tm/get-choice s copy-edit)]
                    (t/is (n/has-property-of (tm/get-choice s main-edit) main))
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

(defn- text-restyle
  "The restyle of a text in the notation that the `:fills` or `:font-family`
   text edit `node` makes."
  [node]
  (let [{:keys [edit value]} node]
    (case edit
      :fills       #(thtx/restyle % :color (some (fn [[k v]] (when (= v (:fill-color (first value))) k))
                                                 thtx/colors))
      :font-family #(thtx/restyle % :family value))))

(defn- without-variants
  "`text` in the notation with no `:font-variant-id`."
  [text]
  (walk/postwalk #(cond-> % (map? %) (dissoc :font-variant-id)) text))

(defn- normalize
  [text]
  (without-variants (thtx/normalize text)))

(defn text-style-override-against-main-style-change
  "Case BA. A main text in two styles (\"Price: \" and a bold \"$10\") and
   its copy. The copy changes the fill or the font family of all of its text,
   then the main changes one of them and syncs, then optionally the copy is
   reset or updates the main. Each style is an override of its own (decision
   (k)): after the sync the copy keeps its style and takes the main's other
   one. A reset makes the copy equal to the main and clears its overrides.
   An update main gives the main the copy's style, keeps the main's other
   one, and clears the copy's overrides."
  []
  (let [copy-edit (tm/one-of [(n/edit-text :copy-instance :fills [{:fill-color "#FF0000" :fill-opacity 1}])
                              (n/edit-text :copy-instance :font-family "Roboto")])
        main-edit (tm/one-of [(n/edit-text :main-instance :fills [{:fill-color "#0000FF" :fill-opacity 1}])
                              (n/edit-text :main-instance :font-family "Inter")])
        reset     (n/reset :copy-root)
        push      (n/update-main :copy-root)]
    {:setup     setup/two-style-text-component-with-copy
     :operation (tm/in-sequence [copy-edit main-edit (tm/optional (tm/one-of [reset push]))])
     :asserter  (fn [s]
                  (let [copy-node (tm/get-choice s copy-edit)
                        main-node (tm/get-choice s main-edit)
                        main-text ((text-restyle main-node) setup/two-style-text)
                        merged    ((text-restyle copy-node) main-text)
                        copy      (setup/copy-instance s)
                        main      (setup/main-instance s)
                        ;; the variant id is left out: with the family from
                        ;; one side and the weight from the other, the
                        ;; frontend picks the family's closest variant, and
                        ;; the pure runner has no fonts to pick from
                        text-of   #(without-variants (n/text-span-value % :content))]
                    (cond
                      (tm/applied? s reset)
                      (do
                        (t/is (= (normalize main-text) (text-of copy)))
                        (t/is (= (normalize main-text) (text-of main)))
                        (t/is (empty? (:touched copy))))

                      (tm/applied? s push)
                      (do
                        (t/is (= (normalize merged) (text-of main)))
                        (t/is (= (text-of main) (text-of copy)))
                        (t/is (empty? (:touched copy))))

                      :else
                      (do
                        (t/is (= (normalize main-text) (text-of main)))
                        (t/is (= (normalize merged) (text-of copy)))))))}))

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
                    ;; the reset leaves the top copy's overrides alone
                    (t/is (= 0.5 (:opacity root)))
                    (t/is (= #{:layer-effects-group} (:touched root)))))}))

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
                  (t/is (= 0.5 (:opacity (root s))))
                  (t/is (contains? (:touched (root s)) :layer-effects-group)))}))

(defn update-main-pushes-a-swapped-child
  "Case AD. Of two copies, the first swaps its nested copy and pushes that to
   the main (update main). Per the swap model of decision (h), update main
   pushes the copy's component: the main's nested copy becomes an instance of
   it, and so do the nested copies of both copies; the first one references
   the main's new nested copy and has nothing touched (F16)."
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
                  (let [s1    (n/lineage-component-id s "s1")
                        first (tm/shape-by-id s (first-nested s))]
                    (t/is (= s1 (head-component s main-nested)))
                    (t/is (= s1 (head-component s first-nested)))
                    (t/is (= red (head-rect-color s first-nested)))
                    (t/is (= (main-nested s) (:shape-ref first)))
                    (t/is (empty? (:touched first)))
                    (t/is (= s1 (head-component s second-nested)))
                    (t/is (= red (head-rect-color s second-nested)))))}))

(defn detached-or-duplicated-swapped-copy-follows-its-component
  "Case AE. A copy swaps its nested copy; then the copy is detached, or
   duplicated; then the main of the component it swapped to changes. Detached,
   the root loses its link and the nested copy becomes a top-level copy of
   that component, no longer swapped; duplicated, the duplicate's nested copy
   is one too. Either way it follows the change."
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
                    (when-not (tm/applied? s duplication)
                      ;; the detached root lost its link; its nested copy is a
                      ;; top-level copy now, no longer swapped
                      (let [head (tm/shape-by-id s (nested s))]
                        (t/is (nil? (:component-id (tm/shape-by-id s ((n/copy-head-of m) s)))))
                        (t/is (:component-root head))
                        (t/is (not (ctk/swapped? head)))))
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
                  (t/is (= 3 (column s :copy-root :copy-child))))}))

(defn main-reorder-keeps-copy-sub-heads-aligned
  "Case AG (the plugin's MainReorderKeepsCopySlots). A main holds three
   nested copies side by side; it moves the first to the end. The copy follows:
   each of its children references the main's child at the same position."
  []
  (let [move (n/move-child :sub1 :row-root 3)]
    {:setup      setup/component-with-sibling-instances
     :operation  (tm/in-sequence [move])
     :asserter   (fn [s]
                   (t/is (= [] (setup/misaligned-sub-heads s))))}))

(defn- deleted-copy-child-is-hidden
  "Assert that the copy child the one-of `removal` deleted, if it ran, is
   still there and hidden: a copy keeps the main's structure."
  [s removal]
  (when-let [chosen (tm/get-choice s removal)]
    (t/is (:hidden (tm/shape-by-id s (tm/resolve-shape-id s (:target chosen)))))))

(defn copy-sub-head-delete-keeps-others-aligned
  "Case AH (the plugin's CopySubheadDeletePreservesSlots, without its
   reflow). A grid main holds three nested copies; its copy optionally deletes
   its first or its last one. A copy keeps the main's structure, so the
   deleted child stays, hidden, and every child still references the main's
   child at the same position."
  []
  (let [removal (tm/one-of [(n/remove-child :copy-sub1) (n/remove-child :copy-sub3)])]
    {:setup     #(setup/component-with-sibling-instances :grid)
     :operation (tm/in-sequence [(tm/optional removal)])
     :asserter  (fn [s]
                  (t/is (= [] (setup/misaligned-sub-heads s)))
                  (deleted-copy-child-is-hidden s removal))}))

(defn copy-sub-head-delete-and-reflow-keeps-others-aligned
  "Case AI (frontend only; the plugin's CopySubheadDeletePreservesSlots
   with its reflow). As case AH, then the copy root optionally changes height,
   which reflows its grid. The copy's remaining children still reference the
   main's children at the same positions."
  []
  (let [removal (tm/one-of [(n/remove-child :copy-sub1) (n/remove-child :copy-sub3)])]
    {:setup     #(setup/component-with-sibling-instances :grid)
     :operation (tm/in-sequence [(tm/optional removal)
                                 (tm/optional (n/change-height :copy-root 500))])
     :asserter  (fn [s]
                  (t/is (= [] (setup/misaligned-sub-heads s)))
                  (deleted-copy-child-is-hidden s removal))}))

(defn- grid-placement
  "The cells of grid board `board`, as `{column [child-shape ...]}`."
  [s board]
  (let [objects (:objects (thf/current-page (tm/file s)))]
    (into {}
          (map (fn [cell] [(:column cell) (mapv #(get objects %) (:shapes cell))]))
          (vals (:layout-grid-cells board)))))

(defn update-main-pushes-grid-cells
  "Case AK. A component's main is a grid board holding two nested copies;
   optionally its copy swaps the first of them. The copy moves that child to
   the free third column, then pushes its overrides to the main (update
   main). The main's child at that position moves to the third column: an
   instance of the component the copy swapped to, if it swapped, else the
   original. Every cell of the main holds only children of the main, and each
   child is in a cell. The copy is left untouched."
  []
  (let [swap (n/swap-shape :copy-child :other)]
    {:setup     setup/grid-component-with-copy
     :operation (tm/in-sequence [(tm/optional swap)
                                 (n/move-to-cell :copy-root :copy-child 1 3)
                                 (n/update-main :copy-root)])
     :asserter  (fn [s]
                  (let [main       (tm/role-shape s :main-root)
                        copy-child (tm/role-shape s :copy-child)
                        placement  (grid-placement s main)
                        in-cells   (into #{} (comp (mapcat val) (map :id)) placement)
                        cells      {:stray    (into #{} (remove (set (:shapes main))) in-cells)
                                    :uncelled (count (remove in-cells (:shapes main)))}
                        ;; the main's third column takes the copy child's component
                        in-col-3   (mapv :component-id (get placement 3))
                        expected   [(:component-id copy-child)]]
                    (t/is (= {:stray #{} :uncelled 0} cells))
                    (t/is (= expected in-col-3))
                    (t/is (= [(:shape-ref copy-child)] (mapv :id (get placement 3))))
                    (t/is (empty? (get placement 1)))
                    (t/is (empty? (:touched (tm/role-shape s :copy-root))))))}))

(defn update-main-renames-the-component
  "Case AL. A copy renames its root and optionally its child, then pushes its
   overrides to the main (update main). The component and its main root take
   the root's new name, the main child takes the child's new name when it was
   renamed, and the copy is left untouched."
  []
  (let [rename-root  (n/change-property :copy-root :name "Renamed")
        rename-child (n/change-property :copy-instance :name "Renamed child")]
    {:setup     setup/simple-component-with-copy
     :operation (tm/in-sequence [rename-root
                                 (tm/optional rename-child)
                                 (n/update-main :copy-root)])
     :asserter  (fn [s]
                  (let [main-root (tm/role-shape s :main-root)
                        component (ctkl/get-component (:data (tm/file s))
                                                      (:component-id main-root))]
                    (t/is (= "Renamed" (:name component)))
                    (t/is (n/has-property-of rename-root main-root))
                    (when (tm/applied? s rename-child)
                      (t/is (n/has-property-of rename-child (tm/role-shape s :main-instance))))
                    (t/is (empty? (:touched (tm/role-shape s :copy-root))))
                    (t/is (empty? (:touched (tm/role-shape s :copy-instance))))))}))

(defn update-main-pushes-a-swap-inside-a-group
  "Case AM. A component's main holds a nested copy inside a group; its copy
   swaps that nested copy for another component, then pushes its overrides to
   the main (update main). The main's group holds an instance of the
   component swapped to in place of the old one, the copy's swapped child
   references it, and the copy is left untouched."
  []
  {:setup     setup/grouped-component-with-copy
   :operation (tm/in-sequence [(n/swap-shape :copy-child :other)
                               (n/update-main :copy-root)])
   :asserter  (fn [s]
                (let [objects    (:objects (thf/current-page (tm/file s)))
                      copy-child (tm/role-shape s :copy-child)
                      group      (get objects (:parent-id (get objects (:shape-ref copy-child))))
                      in-group   (mapv #(get objects %) (:shapes group))]
                  (t/is (= (:id (tm/role-shape s :main-root)) (:parent-id group)))
                  (t/is (= [(:component-id copy-child)] (mapv :component-id in-group)))
                  (t/is (= [(:shape-ref copy-child)] (mapv :id in-group)))
                  (t/is (empty? (:touched (tm/role-shape s :copy-root))))
                  (t/is (empty? (:touched copy-child)))))})

(defn flex-child-override-follows-sync-reset-and-update-main
  "Case AN. A copy of a flex main overrides its child's margin. Then the main
   changes that margin, the copy resets, or the copy runs update main. After
   the main's change the copy keeps its own margin, touched; after the reset
   it shows the main's, untouched; after update main the main shows the
   copy's and the copy is untouched."
  []
  (let [override    (n/change-property :copy-instance :margin 8)
        main-change (n/change-property :main-instance :margin 20)
        reset       (n/reset :copy-root)
        update-main (n/update-main :copy-root)
        follow-up   (tm/one-of [main-change reset update-main])]
    {:setup      setup/flex-component-with-copy
     :operation  (tm/in-sequence [override follow-up])
     :asserter   (fn [s]
                   (let [copy    (tm/role-shape s :copy-instance)
                         main    (tm/role-shape s :main-instance)
                         margin  (fn [shape] (-> shape :layout-item-margin :m1))
                         chosen  (tm/get-choice s follow-up)
                         touched (contains? (:touched copy) :layout-item-margin)]
                     (condp = chosen
                       main-change (do (t/is (n/has-property-of override copy))
                                       (t/is (n/has-property-of main-change main))
                                       (t/is touched))
                       reset       (do (t/is (= (margin main) (margin copy)))
                                       (t/is (not (n/has-property-of override copy)))
                                       (t/is (not touched)))
                       update-main (do (t/is (n/has-property-of override main))
                                       (t/is (n/has-property-of override copy))
                                       (t/is (not touched))))))}))

(defn copy-dropped-into-a-grid-cell-reaches-copies
  "Case AO. A grid main holds two nested copies in its first two columns, the
   third free; a loose copy of the same component is dropped into the third
   cell of the main. The main holds it in that cell, and the main's copy gets
   a child that references it, in its own third cell. Every child of both is
   in a cell, and the copy is left untouched."
  []
  (let [drop (n/nest-copy :loose :main-root :cell [1 3])]
    {:setup      setup/grid-component-with-copy-and-loose-item
     :operation  (tm/in-sequence [drop])
     :asserter   (fn [s]
                   (let [main      (tm/role-shape s :main-root)
                         copy      (tm/role-shape s :copy-root)
                         loose-id  (:id (tm/role-shape s :loose))
                         placement (fn [board]
                                     (let [placed (grid-placement s board)]
                                       {:col-3    (mapv :id (get placed 3))
                                        :uncelled (count (remove (into #{} (comp (mapcat val) (map :id)) placed)
                                                                 (:shapes board)))}))
                         in-copy   (d/seek #(= loose-id (:shape-ref %))
                                           (map #(tm/shape-by-id s %) (:shapes copy)))]
                     (t/is (= {:col-3 [loose-id] :uncelled 0} (placement main)))
                     (t/is (some? in-copy))
                     (t/is (= {:col-3 [(:id in-copy)] :uncelled 0} (placement copy)))
                     (t/is (empty? (:touched copy)))
                     (t/is (empty? (:touched in-copy)))))}))

(defn library-main-child-removal-reaches-copy
  "Case AP. A library main with three children deletes its middle one; a copy
   in the consuming file then takes the library update. The copy loses its
   middle child and keeps the others in order, linked and untouched."
  []
  {:setup     setup/cross-file-component-with-many-children
   :operation (tm/in-sequence [(n/sync-from-library)])
   :asserter  (fn [s]
                (let [c1 (tm/role-shape s :copy-child-1)
                      c3 (tm/role-shape s :copy-child-3)]
                  (t/is (= [(:id c1) (:id c3)] (:shapes (tm/role-shape s :copy-root))))
                  (t/is (some? (:shape-ref c1)))
                  (t/is (some? (:shape-ref c3)))
                  (t/is (empty? (:touched c1)))
                  (t/is (empty? (:touched c3)))))})

(defn main-change-reaches-a-copy-nested-in-a-sub-board
  "Case AQ. An outer main holds a copy of an inner component inside a
   sub-board, not directly under its root; the outer main has a copy, which
   optionally overrides the inner rect's fill. The inner main changes that
   fill. The outer main's nested copy shows the change, and so does the outer
   copy unless it overrode it: then it keeps its value, touched."
  []
  (let [override (n/change-property (fn [s]
                                      (n/corresponding-shape-id s
                                                                (:id (tm/role-shape s :copy-root))
                                                                (:id (tm/role-shape s :main-instance))))
                                    :fills green)
        change   (n/change-property :main-instance :fills red)]
    {:setup     setup/component-nested-in-a-sub-board
     :operation (tm/in-sequence [(tm/optional override) change])
     :asserter  (fn [s]
                  (let [image (fn [role]
                                (tm/shape-by-id s (n/corresponding-shape-id
                                                   s
                                                   (:id (tm/role-shape s role))
                                                   (:id (tm/role-shape s :main-instance)))))
                        in-main (image :main-root)
                        in-copy (image :copy-root)]
                    (t/is (n/has-property-of change in-main))
                    (if (tm/applied? s override)
                      (do
                        (t/is (n/has-property-of override in-copy))
                        (t/is (contains? (:touched in-copy) :fill-group)))
                      (do
                        (t/is (n/has-property-of change in-copy))
                        (t/is (empty? (:touched in-copy)))))))}))

;; ---------------------------------------------------------------------------
;; Variant management
;;
;; The design tab's edits of a variant set, with copies of its members
;; present. A variant's properties live in its components and in its mains'
;; `:variant-name`; copies hold neither, so an edit must leave them as they
;; are, while a later switch finds its member by the edited properties.
;; ---------------------------------------------------------------------------

(defn- head-rect
  "The rect inside the copy head bound to target fn `head`."
  [s head]
  (let [objects (:objects (thf/current-page (tm/file s)))]
    (->> (cfh/get-children-ids objects (head s))
         (map #(get objects %))
         (d/seek #(= :rect (:type %))))))

(defn variant-property-edits-keep-copies
  "Case AR. A copy nests member a of a variant set (a, b; their rects differ)
   and overrides the fill of the variant's rect. The set gets a second
   property, then one edit: rename the first property, set member a's value,
   move the new property first, or remove it. The members' properties and
   their mains' variant names follow the edit; the copy stays an instance of
   member a with its override, touched only there. Then the copy switches to
   member b by the first property, at its position after the edit: it becomes
   an instance of b and, the mains differing on the fill, takes b's fill
   untouched (decision a)."
  []
  (let [m         "main"
        vset      "vset"
        nested    (n/nested-head-in m)
        rename    (n/rename-variant-property vset 0 "Color")
        set-value (n/set-variant-property-value vset "a" 0 "a2")
        reorder   (n/reorder-variant-properties vset 1 0)
        remove    (n/remove-variant-property vset 1)
        ;; member a's properties and variant name after each edit
        expected  (fn [s]
                    (cond
                      (tm/applied? s rename)    [[["Color" "a"] ["Size" "S"]] "a, S"]
                      (tm/applied? s set-value) [[["Property 1" "a2"] ["Size" "S"]] "a2, S"]
                      (tm/applied? s reorder)   [[["Size" "S"] ["Property 1" "a"]] "S, a"]
                      (tm/applied? s remove)    [[["Property 1" "a"]] "a"]))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-variant-container vset [["a" base-color] ["b" red]] :size 100)
                  (n/make-nested-component-with-variant m vset "a")
                  (n/instantiate-copy m)
                  (n/change-property #(:id (head-rect % nested)) :fills green)
                  (n/add-variant-property vset :name "Size" :value "S")
                  (tm/one-of [rename set-value reorder remove])
                  (tm/test-that
                   (fn [s]
                     (let [[props variant-name] (expected s)
                           rect                 (head-rect s nested)]
                       (t/is (= props (n/member-properties s vset "a")))
                       (t/is (= variant-name (:variant-name (n/member-root s vset "a"))))
                       (t/is (= (n/variant-member-component-id s vset "a") (head-component s nested)))
                       (t/is (= green (fill-color rect)))
                       (t/is (= #{:fill-group} (:touched rect)))
                       (t/is (empty? (:touched (tm/shape-by-id s (nested s))))))))
                  (n/switch-variant nested "b" :pos #(if (tm/applied? % reorder) 1 0))])
     :asserter  (fn [s]
                  (let [rect (head-rect s nested)]
                    (t/is (= (n/variant-member-component-id s vset "b") (head-component s nested)))
                    (t/is (= red (fill-color rect)))
                    (t/is (not (contains? (:touched rect) :fill-group)))))}))

(defn added-variant-is-a-switch-target
  "Case AS. A copy nests member a of a variant set (a, b; their rects differ)
   and overrides the fill of the variant's rect. A member is added, a
   duplicate of a or of b: it joins the set with the next value, its main in
   the set's container, and the copy stays as it was. Then the copy switches
   to the new member and becomes an instance of it. A duplicate of a agrees
   with a on the fill, so the override is carried, touched; a duplicate of b
   does not, so the rect takes b's fill untouched (decision a)."
  []
  (let [m      "main"
        vset   "vset"
        nested (n/nested-head-in m)
        from-a (n/add-variant vset "a")
        from-b (n/add-variant vset "b")
        added  (fn [s] (n/added-variant-component-id s (if (tm/applied? s from-a) from-a from-b)))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-variant-container vset [["a" base-color] ["b" red]] :size 100)
                  (n/make-nested-component-with-variant m vset "a")
                  (n/instantiate-copy m)
                  (n/change-property #(:id (head-rect % nested)) :fills green)
                  (tm/one-of [from-a from-b])
                  (tm/test-that
                   (fn [s]
                     (let [component (ctkl/get-component (:data (tm/file s)) (added s))
                           main      (tm/shape-by-id s (:main-instance-id component))
                           rect      (head-rect s nested)]
                       (t/is (= [["Property 1" "Value 3"]] (n/component-properties s (added s))))
                       (t/is (= (n/variant-set-id s vset) (:variant-id component)))
                       (t/is (= (n/variant-set-id s vset) (:parent-id main)))
                       (t/is (= (n/variant-member-component-id s vset "a") (head-component s nested)))
                       (t/is (= green (fill-color rect)))
                       (t/is (= #{:fill-group} (:touched rect))))))
                  (n/switch-variant nested "Value 3")])
     :asserter  (fn [s]
                  (let [rect (head-rect s nested)]
                    (t/is (= (added s) (head-component s nested)))
                    (if (tm/applied? s from-a)
                      (do
                        (t/is (= green (fill-color rect)))
                        (t/is (contains? (:touched rect) :fill-group)))
                      (do
                        (t/is (= red (fill-color rect)))
                        (t/is (not (contains? (:touched rect) :fill-group)))))))}))

(defn- variant-copy-checks
  "Assert that the copy head bound to target fn `head` is still an instance of
   `component-id`, its rect keeping the fill `color` override, touched only
   there."
  [s head component-id color]
  (let [rect (head-rect s head)]
    (t/is (= component-id (head-component s head)))
    (t/is (= color (fill-color rect)))
    (t/is (= #{:fill-group} (:touched rect)))
    (t/is (empty? (:touched (tm/shape-by-id s (head s)))))))

(defn transformed-component-keeps-its-copies
  "Case AT (frontend only). A component has a copy that overrides its rect's
   fill; optionally its main gets a path name. The main becomes a variant,
   from the design tab or with the create-component shortcut on the
   selected main. The component becomes the first member of a new set with
   one property, valued after the path's last part if it has one, a second
   member (a duplicate) joins it, and the copy stays an instance of the
   component with its override. A switch of the copy to the second member
   keeps the override, touched, the mains agreeing on the fill (decision
   a)."
  []
  (let [m         "main"
        head      (n/copy-head-of m)
        main      (n/main-head-of m)
        comp      #(n/lineage-component-id % m)
        path-name (n/change-property main :name "Button/Primary")
        first-val #(if (tm/applied? % path-name) "Primary" "Value 1")
        transform (n/transform-in-variant main)
        shortcut  (n/variant-shortcut :create-component [main])]
    {:setup      setup/empty-situation
     :operation  (tm/in-sequence
                  [(n/create-component m base-color :size 100)
                   (n/instantiate-copy m)
                   (n/change-property (n/copy-rect-of m) :fills green)
                   (tm/optional path-name)
                   (tm/one-of [transform shortcut])
                   (tm/test-that
                    (fn [s]
                      (let [component  (n/component-of s main)
                            variant-id (:variant-id component)
                            members    (n/variant-component-ids s variant-id)]
                        (t/is (some? variant-id))
                        (t/is (:is-variant-container (tm/shape-by-id s variant-id)))
                        (t/is (= variant-id (:parent-id (tm/shape-by-id s (main s)))))
                        (when (tm/applied? s path-name)
                          (t/is (= "Button" (:name component)))
                          (t/is (= "Button" (:name (tm/shape-by-id s variant-id)))))
                        (t/is (= 2 (count members)))
                        (t/is (= #{[["Property 1" (first-val s)]] [["Property 1" "Value 2"]]}
                                 (set (map #(n/component-properties s %) members))))
                        (t/is (= [["Property 1" (first-val s)]] (n/component-properties s (comp s))))
                        (variant-copy-checks s head (comp s) green))))
                   (n/switch-variant head "Value 2")])
     :asserter  (fn [s]
                  (let [rect   (head-rect s head)
                        second (->> (n/variant-component-ids s (:variant-id (n/component-of s main)))
                                    (remove #{(comp s)})
                                    first)]
                    (t/is (= second (head-component s head)))
                    (t/is (= green (fill-color rect)))
                    (t/is (contains? (:touched rect) :fill-group))))}))

(defn combined-components-keep-their-copies
  "Case AU (frontend only). Two components, each with a copy; the first copy
   overrides its rect's fill. Their mains are combined as variants, by ids or
   as the selection. Both components become members of one new set, each
   with its own value, and the copies stay instances of their components,
   the override kept. The first copy then switches to the second member and
   takes its fill untouched, the mains differing (decision a)."
  []
  (let [m1   "first"
        m2   "second"
        head (n/copy-head-of m1)
        mains [(n/main-head-of m1) (n/main-head-of m2)]
        value (fn [s] (-> (n/component-properties s (n/lineage-component-id s m2)) first second))]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m1 base-color :size 100)
                  (n/create-component m2 red :size 100)
                  (n/instantiate-copy m1)
                  (n/instantiate-copy m2)
                  (n/change-property (n/copy-rect-of m1) :fills green)
                  (tm/one-of [(n/combine-as-variants mains)
                              (n/combine-as-variants mains :selected? true)])
                  (tm/test-that
                   (fn [s]
                     (let [c1 (n/lineage-component-id s m1)
                           c2 (n/lineage-component-id s m2)
                           variant-id (:variant-id (n/component-of s (first mains)))]
                       (t/is (some? variant-id))
                       (t/is (= variant-id (:variant-id (n/component-of s (second mains)))))
                       (t/is (= #{c1 c2} (set (n/variant-component-ids s variant-id))))
                       (t/is (not= (n/component-properties s c1) (n/component-properties s c2)))
                       (variant-copy-checks s head c1 green)
                       (t/is (= c2 (head-component s (n/copy-head-of m2))))
                       (t/is (= red (head-rect-color s (n/copy-head-of m2)))))))
                  (n/switch-variant head value)])
     :asserter  (fn [s]
                  (let [rect (head-rect s head)]
                    (t/is (= (n/lineage-component-id s m2) (head-component s head)))
                    (t/is (= red (fill-color rect)))
                    (t/is (not (contains? (:touched rect) :fill-group)))))}))

(defn variant-rename-reaches-copy-names
  "Case AV (frontend only). A copy nests member a of a variant set and
   optionally renames its nested copy. The set is renamed from the design
   tab. The container and every member's main take the new name, and so does
   the nested copy in the outer main; the copy's nested copy takes it too
   unless renamed, then it keeps its name, touched (decision d)."
  []
  (let [m           "main"
        vset        "vset"
        nested      (n/nested-head-in m)
        in-main     (n/nested-head-under (n/main-head-of m))
        rename-copy (n/change-property nested :name "Mine")
        rename      (n/rename-variant #(:id (n/member-root % vset "a")) "Renamed")]
    {:setup      setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-variant-container vset [["a" base-color] ["b" red]] :size 100)
                  (n/make-nested-component-with-variant m vset "a")
                  (n/instantiate-copy m)
                  (tm/optional rename-copy)
                  rename])
     :asserter   (fn [s]
                   (let [copy (tm/shape-by-id s (nested s))]
                     (t/is (= "Renamed" (:name (tm/shape-by-id s (n/variant-set-id s vset)))))
                     (doseq [v ["a" "b"]]
                       (t/is (= "Renamed" (:name (n/member-root s vset v))) v))
                     (t/is (= "Renamed" (:name (tm/shape-by-id s (in-main s)))))
                     (if (tm/applied? s rename-copy)
                       (do
                         (t/is (= "Mine" (:name copy)))
                         (t/is (contains? (:touched copy) :name-group)))
                       (do
                         (t/is (= "Renamed" (:name copy)))
                         (t/is (not (contains? (:touched copy) :name-group)))))))}))

(defn variant-layer-rename-and-cleanup
  "Case AW. A copy nests member a of a variant set (a, b; their rects
   differ) and overrides the fill of the variant's rect. Member a is renamed
   in the layers panel: its value changes and a property Size is added,
   empty on b. The set gets a property with no values and the empty
   properties are removed: only that one goes, Size holding a value. A
   second layers-panel rename drops Size from a, leaving it empty on every
   member, and the rename removes it. Then member b gets an error and loses
   it. The copy stays as it was through every step, and a switch then finds
   member b."
  []
  (let [m      "main"
        vset   "vset"
        nested (n/nested-head-in m)
        a-id   #(n/variant-member-component-id % vset "a")
        b-root #(n/member-root % vset "b")
        props  (fn [s]
                 [(n/member-properties s vset "a") (n/member-properties s vset "b")])
        resized [[["Property 1" "a2"] ["Size" "S"]] [["Property 1" "b"] ["Size" ""]]]]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-variant-container vset [["a" base-color] ["b" red]] :size 100)
                  (n/make-nested-component-with-variant m vset "a")
                  (n/instantiate-copy m)
                  (n/change-property #(:id (head-rect % nested)) :fills green)
                  (n/rename-variant-in-layers vset "a" [["Property 1" "a2"] ["Size" "S"]])
                  (tm/test-that
                   (fn [s]
                     (t/is (= resized (props s)))
                     (t/is (= "a2, S" (:variant-name (n/member-root s vset "a"))))
                     (variant-copy-checks s nested (a-id s) green)))
                  (n/add-variant-property vset :name "Empty")
                  (n/remove-empty-properties vset)
                  (tm/test-that
                   (fn [s]
                     (t/is (= resized (props s)))
                     (variant-copy-checks s nested (a-id s) green)))
                  (n/rename-variant-in-layers vset "a" [["Property 1" "a2"]])
                  (tm/test-that
                   (fn [s]
                     (t/is (= [[["Property 1" "a2"]] [["Property 1" "b"]]] (props s)))
                     (t/is (= "a2" (:variant-name (n/member-root s vset "a"))))
                     (variant-copy-checks s nested (a-id s) green)))
                  (n/set-variant-error vset "b" "Duplicated")
                  (tm/test-that (fn [s] (t/is (= "Duplicated" (:variant-error (b-root s))))))
                  (n/set-variant-error vset "b" nil)
                  (tm/test-that
                   (fn [s]
                     (t/is (nil? (:variant-error (b-root s))))
                     (variant-copy-checks s nested (a-id s) green)))
                  (n/switch-variant nested "b")])
     :asserter  (fn [s]
                  (let [rect (head-rect s nested)]
                    (t/is (= (n/variant-member-component-id s vset "b") (head-component s nested)))
                    (t/is (= red (fill-color rect)))
                    (t/is (not (contains? (:touched rect) :fill-group)))))}))

(defn variant-shortcuts-add-members
  "Case AX (frontend only). A copy nests member a of a variant set (a, b) and
   overrides the fill of the variant's rect. With member a, or members a and
   b, selected, the create-component or the duplicate shortcut adds one
   member per selected one, each a duplicate with a value of its own. The
   copy stays as it was."
  []
  (let [m      "main"
        vset   "vset"
        nested (n/nested-head-in m)
        a      #(:id (n/member-root % vset "a"))
        b      #(:id (n/member-root % vset "b"))
        sweep  (tm/one-of [(n/variant-shortcut :create-component [a])
                           (n/variant-shortcut :duplicate [a])
                           (n/variant-shortcut :create-component [a b])
                           (n/variant-shortcut :duplicate [a b])])]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-variant-container vset [["a" base-color] ["b" red]] :size 100)
                  (n/make-nested-component-with-variant m vset "a")
                  (n/instantiate-copy m)
                  (n/change-property #(:id (head-rect % nested)) :fills green)
                  sweep])
     :asserter  (fn [s]
                  (let [members (n/variant-component-ids s (n/variant-set-id s vset))
                        targets (get-in (tm/get-choice s sweep) [:params :targets])
                        color   (fn [component-id]
                                  (let [component (ctkl/get-component (:data (tm/file s)) component-id)]
                                    (fill-color (head-rect s (constantly (:main-instance-id component))))))]
                    (t/is (= (+ 2 (count targets)) (count members)))
                    ;; a duplicate keeps its source's rect fill
                    (t/is (= (frequencies (concat [base-color red] (map {a base-color b red} targets)))
                             (frequencies (map color members))))
                    (t/is (apply distinct? (map #(n/component-properties s %) members)))
                    (variant-copy-checks s nested (n/variant-member-component-id s vset "a") green)))}))

(defn- subtree-rect
  "The rect in the subtree of the shape bound to target fn `head`."
  [s head]
  (let [objects (:objects (thf/current-page (tm/file s)))]
    (->> (cfh/get-children-ids objects (head s))
         (map #(get objects %))
         (d/seek #(= :rect (:type %))))))

(defn copy-moved-out-of-its-main-keeps-inherited-overrides
  "Case AZ. A main nests a copy of another component and overrides the fill
   of its rect; optionally a second main nests a copy of that main, whose
   inner copy then inherits the override without holding it. The innermost
   copy of the latest main is copied and pasted at the page root: the new
   copy is a copy of the inner component and shows the overridden fill.
   Then the inner main changes that fill. The new copy keeps the fill the
   main gave it, as its own override, touched: the copy merges the touched
   groups of the ref chain it skips, the way a detach keeps the near main's
   overrides (F17 does not show through paste)."
  []
  (let [m      "main"
        inner  (fn [s] (:parent-id (tm/shape-by-id s ((n/main-rect-of m) s))))
        paste  (n/paste-to-root inner)
        head   (n/duplicate-head-of paste)]
    {:setup     setup/empty-situation
     :operation (tm/in-sequence
                 [(n/create-component m base-color :size 100)
                  (n/make-nested-component m)
                  (n/change-property (n/main-rect-of m) :fills green)
                  (tm/optional (n/make-nested-component m))
                  paste
                  (tm/test-that
                   (fn [s]
                     (let [rect (subtree-rect s head)]
                       (t/is (= uuid/zero (:parent-id (tm/shape-by-id s (head s)))))
                       (t/is (= ((n/remote-rect-of m) s) (:shape-ref rect)))
                       (t/is (= green (fill-color rect))))))
                  (n/change-property (n/remote-rect-of m) :fills red)])
     :asserter  (fn [s]
                  (let [rect (subtree-rect s head)]
                    (t/is (= green (fill-color rect)))
                    (t/is (contains? (:touched rect) :fill-group))))}))

;; ---------------------------------------------------------------------------
;; Tokens
;; ---------------------------------------------------------------------------

(defn token-on-a-main-reaches-copies
  "Case AY. The copy of a component optionally applies a border radius token
   to its rect, then the main applies another one to its own. The main's
   token and radius reach the copy unless it applied its own: then it keeps
   its token and radius, touched. Then the main optionally removes its
   token: a copy that followed loses it and keeps the radius; one with its
   own token keeps it."
  []
  (let [m       "main"
        own     (n/apply-token (n/copy-rect-of m) "radius.s" 10)
        applied (n/apply-token (n/main-rect-of m) "radius.l" 30)
        removal (n/unapply-token (n/main-rect-of m) "radius.l")
        rect    (fn [s target] (tm/shape-by-id s (target s)))]
    {:setup      setup/empty-situation
     :operation  (tm/in-sequence
                  [(n/create-component m base-color :size 100)
                   (n/add-tokens [["radius.s" 10] ["radius.l" 30]])
                   (n/instantiate-copy m)
                   (tm/optional own)
                   applied
                   (tm/test-that
                    (fn [s]
                      (let [copy (rect s (n/copy-rect-of m))]
                        (t/is (n/has-token? (rect s (n/main-rect-of m)) "radius.l" 30))
                        (if (tm/applied? s own)
                          (do
                            (t/is (n/has-token? copy "radius.s" 10))
                            (t/is (contains? (:touched copy) :radius-group)))
                          (do
                            (t/is (n/has-token? copy "radius.l" 30))
                            (t/is (empty? (:touched copy))))))))
                   (tm/optional removal)])
     :asserter  (fn [s]
                  (when (tm/applied? s removal)
                    (let [main (rect s (n/main-rect-of m))
                          copy (rect s (n/copy-rect-of m))]
                      (t/is (empty? (:applied-tokens main)))
                      (t/is (= 30 (:r1 main)))
                      (if (tm/applied? s own)
                        (do
                          (t/is (n/has-token? copy "radius.s" 10))
                          (t/is (contains? (:touched copy) :radius-group)))
                        (do
                          (t/is (empty? (:applied-tokens copy)))
                          (t/is (= 30 (:r1 copy)))
                          (t/is (empty? (:touched copy))))))))}))

(defn geometry-sync-with-rotated-instances
  "Case N (frontend only). Optionally the copy rotates, optionally the main
   rotates, then the main child changes its fill or its height. The change
   reaches the copy child. A copy root's rotation is inherited content that a
   copy may override: the copy shows its own rotation, else the main's, else
   none. Only a rotated copy root is touched (:geometry-group); its child
   follows untouched."
  []
  (let [rotate-copy (n/rotate :copy-root 45)
        rotate-main (n/rotate :main-root 30)
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
                                            (tm/applied? situation rotate-main) 30
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
                        (t/is (every? true? (map mth/close?
                                                 (mapv - main-offset [10 5])
                                                 (mapv - copy-offset [7 3]))))
                        (t/is (contains? (:touched (setup/copy-instance s)) :geometry-group)))
                      (do
                        (t/is (every? true? (map mth/close? main-offset copy-offset)))
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
                    (t/is (mth/close? (:rotation copy-root) 30))
                    (t/is (contains? (:touched copy-root) :geometry-group))
                    (t/is (contains? (:touched copy-child) :geometry-group))
                    (t/is (mth/close? (:height copy-child) 60))
                    (t/is (mth/close? (:x @before) (:x after) 0.5) (str "rel-x " (:x @before) " -> " (:x after)))
                    (t/is (mth/close? (:y @before) (:y after) 0.5) (str "rel-y " (:y @before) " -> " (:y after)))))}))

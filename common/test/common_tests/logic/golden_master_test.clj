;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.golden-master-test
  "The golden master of the component code on the JVM: the exact files that
   the shared composable cases (through the pure runner) and the benchmark
   worlds produce (see `app.common.test-helpers.golden-master`). A refactor
   must keep them; a change that alters them is a behavior change, made on
   purpose and recorded again.

   A case variant records the state after each user, undo and redo step,
   then its files at the end with the signatures of its undo/redo round trip
   failures; a world records its file after its timed work.

   `golden_master.edn` next to this file keeps the digests; the full lines
   go to `tmp/golden-master/` at the repository root. Record from `common/`
   with
   `PENPOT_GOLDEN_MASTER=record clojure -M:dev:test --focus common-tests.logic.golden-master-test`,
   only on purpose and saying why in the commit."
  (:require
   [app.common.test-helpers.composable.comp.cases :as cases]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.golden-master :as thgm]
   [app.common.test-helpers.ids-map :as thi]
   [bench.component-sync :as bench]
   [clojure.test :as t]))

(def ^:private digests-path "test/common_tests/logic/golden_master.edn")

(def ^:private lines-dir "../tmp/golden-master")

(defn- recording?
  []
  (= "record" (System/getenv "PENPOT_GOLDEN_MASTER")))

(def ^:private case-fns
  "The shared cases the pure runner runs (see
   `common-tests.logic.composable-sync-test`), by key."
  (array-map
   :b  cases/copy-override-survives-later-main-change
   :c  cases/attribute-sweep-propagates-to-clean-copy
   :d  cases/add-shape-to-main-propagates-to-clean-copy
   :e  cases/remove-shape-from-main-propagates-to-clean-copy
   :f  cases/move-shape-in-main-propagates-order-to-clean-copy
   :h  cases/library-change-propagates-across-file-boundary-on-sync
   :i  cases/undo-reverts-edit-and-its-propagation
   :p  cases/redo-reapplies-edit-and-its-propagation
   :k  cases/synchronisation-scenarios
   :l  cases/swap-scenarios
   :m  cases/variant-switch-scenarios
   :o  cases/variant-switch-keeps-override-only-where-mains-agree
   :q  cases/detach-keeps-nested-copies-linked
   :r  cases/update-main-reaches-other-copies
   :s  cases/deleted-component-copies-sync-again-after-restore
   :t  cases/duplicated-copy-keeps-overrides-and-link
   :u  cases/nested-copy-reaches-copies-of-the-receiving-main
   :v  cases/new-edit-after-undo-clears-redo
   :w  cases/undo-several-steps-over-nested-cascades
   :y  cases/text-override-merges-with-main-text-change
   :ba cases/text-style-override-against-main-style-change
   :aa cases/copy-swap-survives-main-changes
   :ab cases/reset-of-nested-swap-keeps-top-copy-overrides
   :ac cases/second-variant-switch-keeps-root-overrides
   :ad cases/update-main-pushes-a-swapped-child
   :ae cases/detached-or-duplicated-swapped-copy-follows-its-component
   :af cases/grid-cell-move-reaches-a-swapped-copy-child
   :ag cases/main-reorder-keeps-copy-sub-heads-aligned
   :ah cases/copy-sub-head-delete-keeps-others-aligned
   :ak cases/update-main-pushes-grid-cells
   :al cases/update-main-renames-the-component
   :am cases/update-main-pushes-a-swap-inside-a-group
   :an cases/flex-child-override-follows-sync-reset-and-update-main
   :ao cases/copy-dropped-into-a-grid-cell-reaches-copies
   :ap cases/library-main-child-removal-reaches-copy
   :aq cases/main-change-reaches-a-copy-nested-in-a-sub-board
   :ar cases/variant-property-edits-keep-copies
   :as cases/added-variant-is-a-switch-target
   :aw cases/variant-layer-rename-and-cleanup
   :ay cases/token-on-a-main-reaches-copies
   :az cases/copy-moved-out-of-its-main-keeps-inherited-overrides))

(defn- situation-files
  [situation]
  (cons (tm/file situation) (vals (tm/aux-files situation))))

(defn- run-case
  "The states of each variant of `case-map`: one per user, undo and redo
   step, then the final one, with the round trip's failures."
  [case-map]
  (let [steps    (atom [])
        variants (atom [])
        observe  (fn [situation]
                   (swap! steps conj (thgm/state-lines (situation-files situation) nil)))]
    (r/run-each case-map {:observe observe}
                (fn [situation]
                  (let [files    (situation-files situation)
                        failures (into #{} (map (juxt :phase :signature)) (uc/failures situation))
                        final    (thgm/state-lines files {:undo-failures failures})]
                    (swap! variants conj {:transcript (thgm/transcript situation (thgm/id-names files))
                                          :states     (conj @steps final)})
                    (reset! steps []))))
    @variants))

(defn- run-world
  "The state of benchmark world `build` after its timed work."
  [build]
  (thi/reset-idmap!)
  (let [{:keys [key run libraries]} (build)
        [file passes]               (run)]
    [key [{:transcript (name key)
           :states     [(thgm/state-lines (cons file libraries) {:passes passes})]}]]))

(defn- groups
  "Every case and world as `[key variants]`, run one at a time."
  []
  (concat
   (for [[key case-fn] case-fns]
     (do (thi/reset-idmap!)
         [key (run-case (case-fn))]))
   (map run-world (bench/world-builders))))

(t/deftest golden-master
  (if (recording?)
    (do
      (thgm/write-digests! digests-path
                           (into {} (map (fn [[group variants]]
                                           [group (thgm/record-group! lines-dir group variants)]))
                                 (groups)))
      (t/is true))
    (let [recorded (thgm/read-digests digests-path)
          groups   (vec (groups))]
      (t/is (= (set (keys recorded)) (set (map first groups)))
            "the cases and worlds differ from the recorded ones")
      (doseq [[group variants] groups]
        (thgm/compare-group! lines-dir group variants (get recorded group))))))

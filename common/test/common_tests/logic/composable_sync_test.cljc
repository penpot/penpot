;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-sync-test
  "Runs the shared composable component cases through the pure runner, on the
   JVM and in JS. Only the frontend interpreter runs the geometry cases (N, Z,
   AI and the rotation case), whose modifiers come from frontend events, case X,
   which needs a pending frontend sync, case AJ, which needs the frontend
   reflow, and cases AT..AX, whose variant actions are frontend events."
  (:require
   [app.common.test-helpers.composable.comp.cases :as cases]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.ids-map :as thi]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- check
  "Run every variant of `case-map`, apply its asserter to each result before
   the next variant runs and assert that its undo/redo round trip passed. A
   variant whose inline tests and asserter make no assertion fails, and so
   does a known undo failure that applies to no variant."
  [{:keys [asserter] :as case-map}]
  (let [applied (atom #{})]
    (tm/counting-assertions
     (fn [made]
       (r/run-each case-map
                   (fn [situation]
                     (t/testing (str "operations:\n  " (tm/describe-applied situation))
                       (when asserter
                         (asserter situation))
                       (let [n @made]
                         (t/is (pos? n) "the variant made no assertion"))
                       (uc/check! situation case-map)
                       (swap! applied into (uc/applying-marks situation case-map))
                       (reset! made 0))))))
    (uc/check-marks-applied! case-map :pure @applied)))

(t/deftest case-b-copy-override-survives-later-main-change
  (check (cases/copy-override-survives-later-main-change)))

(t/deftest case-c-attribute-sweep-propagates-to-clean-copy
  (check (cases/attribute-sweep-propagates-to-clean-copy)))

(t/deftest case-d-add-shape-to-main-propagates-to-clean-copy
  (check (cases/add-shape-to-main-propagates-to-clean-copy)))

(t/deftest case-e-remove-shape-from-main-propagates-to-clean-copy
  (check (cases/remove-shape-from-main-propagates-to-clean-copy)))

(t/deftest case-f-move-shape-in-main-propagates-order-to-clean-copy
  (check (cases/move-shape-in-main-propagates-order-to-clean-copy)))

(t/deftest case-h-library-change-propagates-across-file-boundary-on-sync
  (check (cases/library-change-propagates-across-file-boundary-on-sync)))

(t/deftest case-i-undo-reverts-edit-and-its-propagation
  (check (cases/undo-reverts-edit-and-its-propagation)))

(t/deftest case-p-redo-reapplies-edit-and-its-propagation
  (check (cases/redo-reapplies-edit-and-its-propagation)))

(t/deftest case-k-synchronisation-scenarios
  (check (cases/synchronisation-scenarios)))

(t/deftest case-l-swap-scenarios
  (check (cases/swap-scenarios)))

(t/deftest case-m-variant-switch-scenarios
  (check (cases/variant-switch-scenarios)))

(t/deftest case-o-variant-switch-keeps-override-only-where-mains-agree
  (check (cases/variant-switch-keeps-override-only-where-mains-agree)))

(t/deftest case-q-detach-keeps-nested-copies-linked
  (check (cases/detach-keeps-nested-copies-linked)))

(t/deftest case-r-update-main-reaches-other-copies
  (check (cases/update-main-reaches-other-copies)))

(t/deftest case-s-deleted-component-copies-sync-again-after-restore
  (check (cases/deleted-component-copies-sync-again-after-restore)))

(t/deftest case-t-duplicated-copy-keeps-overrides-and-link
  (check (cases/duplicated-copy-keeps-overrides-and-link)))

(t/deftest case-u-nested-copy-reaches-copies-of-the-receiving-main
  (check (cases/nested-copy-reaches-copies-of-the-receiving-main)))

(t/deftest case-v-new-edit-after-undo-clears-redo
  (check (cases/new-edit-after-undo-clears-redo)))

(t/deftest case-w-undo-several-steps-over-nested-cascades
  (check (cases/undo-several-steps-over-nested-cascades)))

(t/deftest case-y-text-override-merges-with-main-text-change
  (check (cases/text-override-merges-with-main-text-change)))

(t/deftest case-ba-text-style-override-against-main-style-change
  (check (cases/text-style-override-against-main-style-change)))

(t/deftest case-aa-copy-swap-survives-main-changes
  (check (cases/copy-swap-survives-main-changes)))

(t/deftest case-ab-reset-of-nested-swap-keeps-top-copy-overrides
  (check (cases/reset-of-nested-swap-keeps-top-copy-overrides)))

(t/deftest case-ac-second-variant-switch-keeps-root-overrides
  (check (cases/second-variant-switch-keeps-root-overrides)))

(t/deftest case-ad-update-main-pushes-a-swapped-child
  (check (cases/update-main-pushes-a-swapped-child)))

(t/deftest case-ae-detached-or-duplicated-swapped-copy-follows-its-component
  (check (cases/detached-or-duplicated-swapped-copy-follows-its-component)))

(t/deftest case-af-grid-cell-move-reaches-a-swapped-copy-child
  (check (cases/grid-cell-move-reaches-a-swapped-copy-child)))

(t/deftest case-ag-main-reorder-keeps-copy-sub-heads-aligned
  (check (cases/main-reorder-keeps-copy-sub-heads-aligned)))

(t/deftest case-ah-copy-sub-head-delete-keeps-others-aligned
  (check (cases/copy-sub-head-delete-keeps-others-aligned)))

(t/deftest case-ak-update-main-pushes-grid-cells
  (check (cases/update-main-pushes-grid-cells)))

(t/deftest case-al-update-main-renames-the-component
  (check (cases/update-main-renames-the-component)))

(t/deftest case-am-update-main-pushes-a-swap-inside-a-group
  (check (cases/update-main-pushes-a-swap-inside-a-group)))

(t/deftest case-an-flex-child-override-follows-sync-reset-and-update-main
  (check (cases/flex-child-override-follows-sync-reset-and-update-main)))

(t/deftest case-ao-copy-dropped-into-a-grid-cell-reaches-copies
  (check (cases/copy-dropped-into-a-grid-cell-reaches-copies)))

(t/deftest case-ap-library-main-child-removal-reaches-copy
  (check (cases/library-main-child-removal-reaches-copy)))

(t/deftest case-aq-main-change-reaches-a-copy-nested-in-a-sub-board
  (check (cases/main-change-reaches-a-copy-nested-in-a-sub-board)))

(t/deftest case-ar-variant-property-edits-keep-copies
  (check (cases/variant-property-edits-keep-copies)))

(t/deftest case-as-added-variant-is-a-switch-target
  (check (cases/added-variant-is-a-switch-target)))

(t/deftest case-aw-variant-layer-rename-and-cleanup
  (check (cases/variant-layer-rename-and-cleanup)))

(t/deftest case-ay-token-on-a-main-reaches-copies
  (check (cases/token-on-a-main-reaches-copies)))

(t/deftest case-az-copy-moved-out-of-its-main-keeps-inherited-overrides
  (check (cases/copy-moved-out-of-its-main-keeps-inherited-overrides)))

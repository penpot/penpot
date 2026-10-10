;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.comp.sync-test
  "Runs the shared composable component cases
   (`app.common.test-helpers.composable.comp.cases`) through the frontend
   interpreter, where the app's component watcher syncs the copies."
  (:require
   [app.common.test-helpers.composable.comp.cases :as cases]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(t/use-fixtures :each ftm/fixture)

(t/deftest case-b-copy-override-survives-later-main-change
  (t/async done (ftm/check done (cases/copy-override-survives-later-main-change))))

(t/deftest case-c-attribute-sweep-auto-propagates-to-clean-copy
  (t/async done (ftm/check done (cases/attribute-sweep-propagates-to-clean-copy))))

(t/deftest case-d-add-shape-to-main-auto-propagates-to-clean-copy
  (t/async done (ftm/check done (cases/add-shape-to-main-propagates-to-clean-copy))))

(t/deftest case-e-remove-shape-from-main-auto-propagates-to-clean-copy
  (t/async done (ftm/check done (cases/remove-shape-from-main-propagates-to-clean-copy))))

(t/deftest case-f-move-shape-in-main-auto-propagates-order-to-clean-copy
  (t/async done (ftm/check done (cases/move-shape-in-main-propagates-order-to-clean-copy))))

(t/deftest case-i-undo-reverts-edit-and-its-auto-propagation
  (t/async done (ftm/check done (cases/undo-reverts-edit-and-its-propagation))))

(t/deftest case-p-redo-reapplies-edit-and-its-auto-propagation
  (t/async done (ftm/check done (cases/redo-reapplies-edit-and-its-propagation))))

(t/deftest case-h-library-change-propagates-across-file-boundary-on-sync
  (t/async done (ftm/check done (cases/library-change-propagates-across-file-boundary-on-sync))))

(t/deftest case-k-synchronisation-scenarios
  (t/async done (ftm/check done (cases/synchronisation-scenarios))))

(t/deftest case-l-swap-scenarios
  (t/async done (ftm/check done (cases/swap-scenarios))))

(t/deftest case-m-variant-switch-scenarios
  (t/async done (ftm/check done (cases/variant-switch-scenarios))))

(t/deftest case-o-variant-switch-keeps-override-only-where-mains-agree
  (t/async done (ftm/check done (cases/variant-switch-keeps-override-only-where-mains-agree))))

(t/deftest case-q-detach-keeps-nested-copies-linked
  (t/async done (ftm/check done (cases/detach-keeps-nested-copies-linked))))

(t/deftest case-r-update-main-reaches-other-copies
  (t/async done (ftm/check done (cases/update-main-reaches-other-copies))))

(t/deftest case-s-deleted-component-copies-sync-again-after-restore
  (t/async done (ftm/check done (cases/deleted-component-copies-sync-again-after-restore))))

(t/deftest case-t-duplicated-copy-keeps-overrides-and-link
  (t/async done (ftm/check done (cases/duplicated-copy-keeps-overrides-and-link))))

(t/deftest case-u-nested-copy-reaches-copies-of-the-receiving-main
  (t/async done (ftm/check done (cases/nested-copy-reaches-copies-of-the-receiving-main))))

(t/deftest case-v-new-edit-after-undo-clears-redo
  (t/async done (ftm/check done (cases/new-edit-after-undo-clears-redo))))

(t/deftest case-w-undo-several-steps-over-nested-cascades
  (t/async done (ftm/check done (cases/undo-several-steps-over-nested-cascades))))

(t/deftest case-x-undo-before-sync-leaves-copies-unchanged
  (t/async done (ftm/check done (cases/undo-before-sync-leaves-copies-unchanged))))

(t/deftest case-aj-reflow-after-nested-sync-is-its-own-undo-step
  (t/async done (ftm/check done (cases/reflow-after-nested-sync-is-its-own-undo-step))))

(t/deftest case-y-text-override-merges-with-main-text-change
  (t/async done (ftm/check done (cases/text-override-merges-with-main-text-change))))

(t/deftest case-n-geometry-sync-with-rotated-instances
  (t/async done (ftm/check done (cases/geometry-sync-with-rotated-instances))))

(t/deftest case-touched-copy-child-survives-main-rotation
  (t/async done (ftm/check done (cases/touched-copy-child-survives-main-rotation))))

(t/deftest case-z-main-child-move-reaches-copies
  (t/async done (ftm/check done (cases/main-child-move-reaches-copies))))

(t/deftest case-aa-copy-swap-survives-main-changes
  (t/async done (ftm/check done (cases/copy-swap-survives-main-changes))))

(t/deftest case-ab-reset-of-nested-swap-keeps-top-copy-overrides
  (t/async done (ftm/check done (cases/reset-of-nested-swap-keeps-top-copy-overrides))))

(t/deftest case-ac-second-variant-switch-keeps-root-overrides
  (t/async done (ftm/check done (cases/second-variant-switch-keeps-root-overrides))))

(t/deftest case-ad-update-main-pushes-a-swapped-child
  (t/async done (ftm/check done (cases/update-main-pushes-a-swapped-child))))

(t/deftest case-ae-detached-or-duplicated-swapped-copy-follows-its-component
  (t/async done (ftm/check done (cases/detached-or-duplicated-swapped-copy-follows-its-component))))

(t/deftest case-af-grid-cell-move-reaches-a-swapped-copy-child
  (t/async done (ftm/check done (cases/grid-cell-move-reaches-a-swapped-copy-child))))

(t/deftest case-ag-main-reorder-keeps-copy-sub-heads-aligned
  (t/async done (ftm/check done (cases/main-reorder-keeps-copy-sub-heads-aligned))))

(t/deftest case-ah-copy-sub-head-delete-keeps-others-aligned
  (t/async done (ftm/check done (cases/copy-sub-head-delete-keeps-others-aligned))))

(t/deftest case-ai-copy-sub-head-delete-and-reflow-keeps-others-aligned
  (t/async done (ftm/check done (cases/copy-sub-head-delete-and-reflow-keeps-others-aligned))))

(t/deftest case-ak-update-main-pushes-grid-cells
  (t/async done (ftm/check done (cases/update-main-pushes-grid-cells))))

(t/deftest case-al-update-main-renames-the-component
  (t/async done (ftm/check done (cases/update-main-renames-the-component))))

(t/deftest case-am-update-main-pushes-a-swap-inside-a-group
  (t/async done (ftm/check done (cases/update-main-pushes-a-swap-inside-a-group))))

(t/deftest case-an-flex-child-override-follows-sync-reset-and-update-main
  (t/async done (ftm/check done (cases/flex-child-override-follows-sync-reset-and-update-main))))

(t/deftest case-ao-copy-dropped-into-a-grid-cell-reaches-copies
  (t/async done (ftm/check done (cases/copy-dropped-into-a-grid-cell-reaches-copies))))

(t/deftest case-ap-library-main-child-removal-reaches-copy
  (t/async done (ftm/check done (cases/library-main-child-removal-reaches-copy))))

(t/deftest case-aq-main-change-reaches-a-copy-nested-in-a-sub-board
  (t/async done (ftm/check done (cases/main-change-reaches-a-copy-nested-in-a-sub-board))))

(t/deftest case-ar-variant-property-edits-keep-copies
  (t/async done (ftm/check done (cases/variant-property-edits-keep-copies))))

(t/deftest case-as-added-variant-is-a-switch-target
  (t/async done (ftm/check done (cases/added-variant-is-a-switch-target))))

(t/deftest case-at-transformed-component-keeps-its-copies
  (t/async done (ftm/check done (cases/transformed-component-keeps-its-copies))))

(t/deftest case-au-combined-components-keep-their-copies
  (t/async done (ftm/check done (cases/combined-components-keep-their-copies))))

(t/deftest case-av-variant-rename-reaches-copy-names
  (t/async done (ftm/check done (cases/variant-rename-reaches-copy-names))))

(t/deftest case-aw-variant-layer-rename-and-cleanup
  (t/async done (ftm/check done (cases/variant-layer-rename-and-cleanup))))

(t/deftest case-ax-variant-shortcuts-add-members
  (t/async done (ftm/check done (cases/variant-shortcuts-add-members))))

(t/deftest case-ay-token-on-a-main-reaches-copies
  (t/async done (ftm/check done (cases/token-on-a-main-reaches-copies))))

(t/deftest case-az-copy-moved-out-of-its-main-keeps-inherited-overrides
  (t/async done (ftm/check done (cases/copy-moved-out-of-its-main-keeps-inherited-overrides))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.composable-tests.comp.sync-test
  "Runs the shared composable component cases
   (`app.common.test-helpers.composable.comp.cases`) against the REAL app
   (frontend interpreter). In-file propagation is AUTOMATIC: a case contains
   ONLY the user edit(s) (no propagate op); the app's component-change watcher
   syncs copies on its own, and that is what the cases assert.

   Async: each deftest uses `t/async`; `ftm/check` drives the store and calls
   `done` when finished."
  (:require
   [app.common.test-helpers.composable.comp.cases :as cases]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

;; Disable thumbnail rendering for the duration of each (async) test: the
;; propagation watcher schedules thumbnail renders that reach `window`, absent in
;; the headless runner. `:each` `:after` runs only after the test's `done` fires
;; (same guarantee the wasm-mock fixtures rely on), so the no-op covers the whole
;; async lifetime and is scoped to THIS namespace. See ftm/install-thumbnail-noop!.
(t/use-fixtures :each
  {:before ftm/install-thumbnail-noop!
   :after  ftm/restore-thumbnail!})

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

(t/deftest case-n-geometry-sync-with-rotated-instances
  (t/async done (ftm/check done (cases/geometry-sync-with-rotated-instances))))

(t/deftest case-touched-copy-child-survives-main-rotation
  (t/async done (ftm/check done (cases/touched-copy-child-survives-main-rotation))))

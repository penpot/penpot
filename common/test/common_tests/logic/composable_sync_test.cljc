;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-sync-test
  "Runs the shared composable component cases through the pure runner, on the
   JVM and in JS. Case N and the rotation case need the frontend placement
   step, so only the frontend interpreter runs them."
  (:require
   [app.common.test-helpers.composable.comp.cases :as cases]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.undo-check :as uc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.ids-map :as thi]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- check
  "Run every variant of `case-map`, apply its asserter to each result and
   assert that its undo/redo round trip passed."
  [{:keys [asserter] :as case-map}]
  (doseq [situation (r/run-all case-map)]
    (t/testing (str "operations:\n  " (tm/describe-applied situation))
      (when asserter
        (asserter situation))
      (uc/check! situation case-map))))

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

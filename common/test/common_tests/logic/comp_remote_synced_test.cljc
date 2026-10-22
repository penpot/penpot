;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-remote-synced-test
  (:require
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.ids-map :as thi]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest updating-main-keeps-overrides-authored-since-the-previous-update
  (let [middle-copy (fn [s]
                      (:parent-id (tm/shape-by-id s ((n/nested-head-of "a" 1) s))))
        nested-main-rect (fn [s] (n/level-rect s "a" 0))
        situation
        (r/run-variant
         {:setup setup/empty-situation
          :operation
          (tm/in-sequence
           [(n/create-component "a" "#abcdef" :size 100)
            (n/make-nested-component "a" 2)
            (n/change-property middle-copy :fills "#ff0000")
            (n/update-main middle-copy)
            ;; Push a nested override, then update again for another edit.
            (n/change-property (n/main-rect-of "a") :name "Authored name")
            (n/update-main middle-copy)
            (n/change-property middle-copy :opacity 0.4)
            (n/update-main middle-copy)])})
        main (tm/shape-by-id situation (nested-main-rect situation))]
    (t/is (= "Authored name" (:name main)))
    (t/is (contains? (:touched main) :name-group))))

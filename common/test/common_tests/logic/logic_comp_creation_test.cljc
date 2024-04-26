;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.logic-comp-creation-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.libraries-helpers :as cflh]
   [clojure.test :as t]
   [common-tests.helpers.files :as thf]
   [common-tests.helpers.ids-map :as thi]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-add-component-from-single-shape
  (let [; Setup
        file   (-> (thf/sample-file :file1)
                   (thf/add-sample-shape :shape1 :type :frame))

        page   (thf/current-page file)
        shape1 (thf/get-shape file :shape1)

        ; Action
        [_ component-id changes]
        (cflh/generate-add-component (pcb/empty-changes)
                                     [shape1]
                                     (:objects page)
                                     (:id page)
                                     (:id file)
                                     true
                                     nil
                                     nil)

        file' (thf/apply-changes file changes)

        ; Get
        component (thf/get-component-by-id file' component-id)
        root      (thf/get-shape-by-id file' (:main-instance-id component))]

    ; Check
    (t/is (some? component))
    (t/is (some? root))
    (t/is (= (:component-id root) (:id component)))))

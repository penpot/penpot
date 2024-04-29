;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.components-touched-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.libraries-helpers :as cflh]
   [clojure.test :as t]
   [common-tests.helpers.compositions :as tho]
   [common-tests.helpers.files :as thf]
   [common-tests.helpers.ids-map :as thi]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-touched-when-changing-attribute
  (let [;; Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component1
                                                     :main-root
                                                     :main-child
                                                     :copy-root
                                                     :main-child-params {:fills (thf/sample-fills-color
                                                                                 :fill-color "#abcdef")}))
        page (thf/current-page file)
        copy-root (thf/get-shape file :copy-root)

        ;; Action
        changes (cflh/generate-update-shapes (pcb/empty-changes nil (:id page))
                                             (:shapes copy-root)
                                             #(assoc % :fills (thf/sample-fills-color
                                                               :fill-color "#fabada"))
                                             (:objects page)
                                             {})

        file' (thf/apply-changes file changes)

        ;; Get
        copy-root' (thf/get-shape file' :copy-root)
        copy-child' (thf/get-shape-by-id file' (first (:shapes copy-root')))
        fills' (:fills copy-child')
        fill' (first fills')]

    ;; Check
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:fill-group}))))

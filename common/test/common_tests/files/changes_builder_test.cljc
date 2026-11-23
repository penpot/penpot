;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.changes-builder-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-change-parent-undo-uses-parent-in-objects
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :parent1)
                    (tho/add-frame :parent2)
                    (tho/add-frame :parent3)
                    (tho/add-rect :rect0 :parent-label :parent1)
                    (tho/add-rect :rect1 :parent-label :parent1)
                    (tho/add-rect :rect2 :parent-label :parent1))
        page    (thf/current-page file)
        ;; The map of the shape as it is before both moves
        rect1   (ths/get-shape file :rect1)

        ;; ==== Action
        changes (-> (pcb/empty-changes nil (:id page))
                    (pcb/with-objects (:objects page))
                    (pcb/change-parent (thi/id :parent2) [rect1])
                    (pcb/change-parent (thi/id :parent3) [rect1]))
        file'   (thf/apply-changes file changes)
        file''  (thf/apply-undo-changes file' changes)]

    (t/is (= (thi/id :parent3) (:parent-id (ths/get-shape file' :rect1))))
    ;; The undo of the second move, run first, takes the shape back to
    ;; :parent2, where the first move left it, not to the parent in the map
    (t/is (= (thi/id :parent2) (:parent-id (first (:undo-changes changes)))))
    ;; The whole undo puts the shape back in its place in :parent1
    (t/is (= (thi/id :parent1) (:parent-id (ths/get-shape file'' :rect1))))
    (t/is (= (:shapes (ths/get-shape file :parent1))
             (:shapes (ths/get-shape file'' :parent1))))))

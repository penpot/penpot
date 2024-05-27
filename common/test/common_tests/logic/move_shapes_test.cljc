;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.move-shapes-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-relocate-shape
  (let [;; ==== Setup
        file                   (-> (thf/sample-file :file1)
                                   (tho/add-frame :frame-to-move)
                                   (tho/add-frame :frame-parent))

        page                   (thf/current-page file)
        frame-to-move          (ths/get-shape file :frame-to-move)
        frame-parent           (ths/get-shape file :frame-parent)

        ;; ==== Action

        changes                (cls/generate-relocate (pcb/empty-changes nil)
                                                      (:objects page)
                                                      (:id frame-parent)       ;; parent-id
                                                      (:id page)               ;; page-id
                                                      0                        ;; to-index
                                                      #{(:id frame-to-move)})  ;; ids

        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        frame-to-move'         (ths/get-shape file' :frame-to-move)
        frame-parent'          (ths/get-shape file' :frame-parent)]

    ;; ==== Check
    ;; frame-to-move has moved
    (t/is (= (:parent-id frame-to-move) uuid/zero))
    (t/is (= (:parent-id frame-to-move') (:id frame-parent')))))


(t/deftest test-relocate-shape-out-of-group
  (let [;; ==== Setup
        file                   (-> (thf/sample-file :file1)
                                   (tho/add-frame :frame-1)
                                   (tho/add-group :group-1 :parent-label :frame-1)
                                   (ths/add-sample-shape :circle-1 :parent-label :group-1))

        page                   (thf/current-page file)
        circle                 (ths/get-shape file :circle-1)
        group                  (ths/get-shape file :group-1)

        ;; ==== Action

        changes                (cls/generate-relocate (pcb/empty-changes nil)
                                                      (:objects page)
                                                      uuid/zero             ;; parent-id
                                                      (:id page)            ;; page-id
                                                      0                     ;; to-index
                                                      #{(:id circle)})      ;; ids


        file'                  (thf/apply-changes file changes)

        ;; ==== Get
        circle'                (ths/get-shape file' :circle-1)
        group'                 (ths/get-shape file' :group-1)]

    ;; ==== Check

    ;; the circle has moved, and the group is deleted
    (t/is (= (:parent-id circle) (:id group)))
    (t/is (= (:parent-id circle') uuid/zero))
    (t/is group)
    (t/is (nil? group'))))
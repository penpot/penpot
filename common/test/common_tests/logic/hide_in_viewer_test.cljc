;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.hide-in-viewer-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.shape.interactions :as ctsi]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)


(t/deftest test-remove-show-in-view-mode-delete-interactions
  (let [;; ==== Setup

        file              (-> (thf/sample-file :file1)
                              (tho/add-frame :frame-dest)
                              (tho/add-frame :frame-origin)
                              (ths/add-interaction :frame-origin :frame-dest))

        frame-origin      (ths/get-shape file :frame-origin)

        page              (thf/current-page file)


        ;; ==== Action
        changes           (-> (pcb/empty-changes nil (:id page))
                              (pcb/with-objects (:objects page))
                              (pcb/update-shapes [(:id frame-origin)] #(cls/change-show-in-viewer % true)))
        file'             (thf/apply-changes file changes)

        ;; ==== Get
        frame-origin'     (ths/get-shape file' :frame-origin)]

    ;; ==== Check
    (t/is (some? (:interactions frame-origin)))
    (t/is (nil? (:interactions frame-origin')))))



(t/deftest test-add-new-interaction-updates-show-in-view-mode
  (let [;; ==== Setup

        file              (-> (thf/sample-file :file1)
                              (tho/add-frame :frame-dest :hide-in-viewer true)
                              (tho/add-frame :frame-origin :hide-in-viewer true))
        frame-dest        (ths/get-shape file :frame-dest)
        frame-origin      (ths/get-shape file :frame-origin)

        page              (thf/current-page file)

        ;; ==== Action
        new-interaction   (-> ctsi/default-interaction
                              (ctsi/set-destination (:id frame-dest))
                              (assoc :position-relative-to (:id frame-dest)))

        changes           (-> (pcb/empty-changes nil (:id page))
                              (pcb/with-objects (:objects page))
                              (pcb/update-shapes [(:id frame-origin)] #(cls/add-new-interaction % new-interaction)))
        file'             (thf/apply-changes file changes)

        ;; ==== Get
        frame-origin'     (ths/get-shape file' :frame-origin)]

    ;; ==== Check
    (t/is (true? (:hide-in-viewer frame-origin)))
    (t/is (nil? (:hide-in-viewer frame-origin')))))

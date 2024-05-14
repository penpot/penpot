;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.swap-and-reset-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.file :as  ctf]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; Related .penpot file: common/test/cases/swap-and-reset.penpot
(t/deftest test-simple-swap
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component-1
                                                     :component-1-main-root
                                                     :component-1-main-child
                                                     :component-1-copy-root)
                 (tho/add-simple-component :component-2
                                           :component-2-root
                                           :component-2-child))

        component-1-copy-root (ths/get-shape file :component-1-copy-root)
        component-2           (thc/get-component file :component-2)
        page                  (thf/current-page file)

        ;; ==== Action
        [new-shape _all-parents changes]
        (cll/generate-component-swap (pcb/empty-changes)
                                     (:objects page)
                                     component-1-copy-root
                                     (:data file)
                                     page
                                     {(:id  file) file}
                                     (:id component-2)
                                     0
                                     nil
                                     {})

        file' (thf/apply-changes file changes)

        ;; ==== Get 
        swapped (ths/get-shape-by-id file' (:id new-shape))]

    ;; ==== Check
    (t/is (not= (:component-id component-1-copy-root) (:component-id swapped)))
    (t/is (= (:id component-2) (:component-id swapped)))
    (t/is (= (:id file) (:component-file swapped)))))

(t/deftest test-swap-nested
  (let [;; ==== Setup
        file
        (-> (thf/sample-file :file1)
            (tho/add-simple-component :component-1 :component-1-main-root :component-1-main-child)
            (tho/add-frame :component-container)
            (thc/instantiate-component :component-1 :component-1-copy-root :parent-label :component-container)
            (thc/make-component :component-container-main :component-container)
            (thc/instantiate-component :component-container-main :component-container-instance)
            (tho/add-simple-component :component-2 :component-2-main-root :component-2-main-child))

        page        (thf/current-page file)
        component-2 (thc/get-component file :component-2)

        copy
        (->>
         (ths/get-shape file :component-container-instance)
         :shapes
         first
         (ths/get-shape-by-id file))

        libraries {(:id  file) file}

        ;; ==== Action
        [new-shape _all-parents changes]
        (cll/generate-component-swap (pcb/empty-changes)
                                     (:objects page)
                                     copy
                                     (:data file)
                                     page
                                     libraries
                                     (:id component-2)
                                     0
                                     nil
                                     {})

        file'      (thf/apply-changes file changes)
        libraries' {(:id  file') file'}
        page'      (thf/current-page file')

        ;; ==== Get
        swapped               (ths/get-shape-by-id file' (:id new-shape))
        component-1-copy-root (ths/get-shape file' :component-1-copy-root)
        slot                  (-> (ctf/find-swap-slot swapped
                                                      page'
                                                      file'
                                                      libraries')
                                  (ctk/build-swap-slot-group))]

    ;; ==== Check
    (t/is (not= (:component-id copy) (:component-id swapped)))
    (t/is (= (:id component-2) (:component-id swapped)))
    (t/is (= (:id file) (:component-file swapped)))
    (t/is (contains? (:touched swapped) slot))
    (t/is (= (ctk/get-swap-slot swapped) (:id component-1-copy-root)))))

(t/deftest test-swap-and-reset-override
  (let [;; ==== Setup
        file
        (-> (thf/sample-file :file1)
            (tho/add-simple-component :component-1 :component-1-main-root :component-1-main-child)
            (tho/add-frame :component-container)
            (thc/instantiate-component :component-1 :component-1-copy-root :parent-label :component-container)
            (thc/make-component :component-container-main :component-container)
            (thc/instantiate-component :component-container-main :component-container-instance)
            (tho/add-simple-component :component-2 :component-2-main-root :component-2-main-child))

        page        (thf/current-page file)
        component-1 (thc/get-component file :component-1)
        component-2 (thc/get-component file :component-2)

        copy
        (->>
         (ths/get-shape file :component-container-instance)
         :shapes
         first
         (ths/get-shape-by-id file))

        ;; ==== Action
        [new-shape _all-parents changes-swap]
        (cll/generate-component-swap (pcb/empty-changes)
                                     (:objects page)
                                     copy
                                     (:data file)
                                     page
                                     {(:id  file) file}
                                     (:id component-2)
                                     0
                                     nil
                                     {})

        file-swap (thf/apply-changes file changes-swap)
        page-swap (thf/current-page file-swap)

        changes
        (cll/generate-reset-component (pcb/empty-changes)
                                      file-swap
                                      {(:id  file-swap) file-swap}
                                      page-swap
                                      (:id new-shape)
                                      true)

        file' (thf/apply-changes file changes)

        ;; ==== Get
        reset
        (->>
         (ths/get-shape file' :component-container-instance)
         :shapes
         first
         (ths/get-shape-by-id file'))]

    ;; ==== Check
    (t/is (= (:id component-1) (:component-id reset)))
    (t/is (nil? (ctk/get-swap-slot reset)))))

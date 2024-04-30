;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.swap-and-reset-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.libraries-helpers :as cflh]
   [app.common.pprint :as pp]
   [app.common.types.component :as ctk]
   [app.common.types.file :as  ctf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]
   [common-tests.helpers.compositions :as thc]
   [common-tests.helpers.files :as thf]
   [common-tests.helpers.ids-map :as thi]))

(t/use-fixtures :each thi/test-fixture)

;; Related .penpot file: common/test/cases/swap-and-reset.penpot
(t/deftest test-simple-swap
  (let [;; Setup
        file
        (-> (thf/sample-file :file1)
            (thc/add-simple-component-with-copy :component-1 :component-1-main-root :component-1-main-child :component-1-copy-root)
            (thc/add-simple-component :component-2 :component-2-root :component-2-child))

        component-1-copy-root (thf/get-shape file :component-1-copy-root)
        component-1           (thf/get-component file :component-1)
        component-2           (thf/get-component file :component-2)
        page                  (thf/current-page file)

        ;; Action
        [new-shape all-parents changes]
        (cflh/generate-component-swap (pcb/empty-changes)
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

        ;; Get 
        swapped (thf/get-shape-by-id file' (:id new-shape))]

    ;; Check
    (t/is (not= (:component-id component-1-copy-root) (:component-id swapped)))
    (t/is (= (:id component-2) (:component-id swapped)))
    (t/is (= (:id file) (:component-file swapped)))))

(t/deftest test-swap-nested
  (let [;; Setup
        file
        (-> (thf/sample-file :file1)
            (thc/add-simple-component :component-1 :component-1-main-root :component-1-main-child)
            (thc/add-frame :component-container)
            (thf/instantiate-component :component-1 :component-1-copy-root :parent-label :component-container)
            (thf/make-component :component-container-main :component-container)
            (thf/instantiate-component :component-container-main :component-container-instance)
            (thc/add-simple-component :component-2 :component-2-main-root :component-2-main-child))

        page        (thf/current-page file)
        component-1 (thf/get-component file :component-1)
        component-2 (thf/get-component file :component-2)
        component-3 (thf/get-component file :component-3)

        copy
        (->>
         (thf/get-shape file :component-container-instance)
         :shapes
         first
         (thf/get-shape-by-id file))

        libraries {(:id  file) file}

        ;; Action
        [new-shape all-parents changes]
        (cflh/generate-component-swap (pcb/empty-changes)
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

        ;; Get
        swapped               (thf/get-shape-by-id file' (:id new-shape))
        component-1-copy-root (thf/get-shape file' :component-1-copy-root)
        slot                  (-> (ctf/find-swap-slot swapped
                                                      page'
                                                      file'
                                                      libraries')
                                  (ctk/build-swap-slot-group))]

    ;; Check
    (t/is (not= (:component-id copy) (:component-id swapped)))
    (t/is (= (:id component-2) (:component-id swapped)))
    (t/is (= (:id file) (:component-file swapped)))
    (t/is (contains? (:touched swapped) slot))
    (t/is (= (ctk/get-swap-slot swapped) (:id component-1-copy-root)))))

(t/deftest test-swap-and-reset-override
  (let [;; Setup
        file
        (-> (thf/sample-file :file1)
            (thc/add-simple-component :component-1 :component-1-main-root :component-1-main-child)
            (thc/add-frame :component-container)
            (thf/instantiate-component :component-1 :component-1-copy-root :parent-label :component-container)
            (thf/make-component :component-container-main :component-container)
            (thf/instantiate-component :component-container-main :component-container-instance)
            (thc/add-simple-component :component-2 :component-2-main-root :component-2-main-child))

        page        (thf/current-page file)
        component-1 (thf/get-component file :component-1)
        component-2 (thf/get-component file :component-2)

        copy
        (->>
         (thf/get-shape file :component-container-instance)
         :shapes
         first
         (thf/get-shape-by-id file))

        ;; Action
        [new-shape all-parents changes-swap]
        (cflh/generate-component-swap (pcb/empty-changes)
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
        (cflh/generate-reset-component (pcb/empty-changes)
                                       file-swap
                                       {(:id  file-swap) file-swap}
                                       page-swap
                                       (:id new-shape)
                                       true)

        file' (thf/apply-changes file changes)
        page' (thf/current-page file')
        ;; Get
        reset
        (->>
         (thf/get-shape file' :component-container-instance)
         :shapes
         first
         (thf/get-shape-by-id file'))

        component-1-copy-root (thf/get-shape file' :component-1-copy-root)]

    ;; Check
    (t/is (= (:id component-1) (:component-id reset)))
    (t/is (nil? (ctk/get-swap-slot reset)))))

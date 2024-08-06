;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.comp-reset-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-reset-after-changing-attribute
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1
                                                       :main-root
                                                       :main-child
                                                       :copy-root
                                                       :main-child-params {:fills (ths/sample-fills-color
                                                                                   :fill-color "#abcdef")}
                                                       :copy-root-params {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        copy-root  (ths/get-shape file :copy-root)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy-child)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy-root)
                                                true)

        file'     (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#abcdef"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-reset-from-library
  (let [;; ==== Setup
        library    (-> (thf/sample-file :library :is-shared true)
                       (tho/add-simple-component :component1 :main-root :main-child
                                                 :child-params {:fills (ths/sample-fills-color
                                                                        :fill-color "#abcdef")}))

        file       (-> (thf/sample-file :file)
                       (thc/instantiate-component :component1 :copy-root
                                                  :library library
                                                  :children-labels [:copy-child]))

        page       (thf/current-page file)
        copy-root  (ths/get-shape file :copy-root)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy-child)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf
                                                 (:id library) library}
                                                page-mdf
                                                (:id copy-root)
                                                true)

        file'     (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#abcdef"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-reset-after-adding-shape
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component1
                                                     :main-root
                                                     :main-child
                                                     :copy-root
                                                     :copy-root-params {:children-labels [:copy-child]})
                 (ths/add-sample-shape :free-shape))

        page      (thf/current-page file)
        copy-root (ths/get-shape file :copy-root)

        ;; ==== Action

        ;; IMPORTANT: as modifying copies structure is now forbidden, this action
        ;; will not have any effect, and so the parent shape won't also be touched.
        changes (cls/generate-relocate (pcb/empty-changes)
                                       (:objects page)
                                       (thi/id :copy-root)      ; parent-id
                                       (:id page)               ; page-id
                                       0                        ; to-index
                                       #{(thi/id :free-shape)}) ; ids


        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy-root)
                                                true)

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-reset-after-deleting-shape
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :copy-root-params {:children-labels [:copy-child]}))

        page       (thf/current-page file)
        copy-root  (ths/get-shape file :copy-root)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action

        ;; IMPORTANT: as modifying copies structure is now forbidden, this action will not
        ;; delete the child shape, but hide it (thus setting the visibility group).
        [_all-parents changes]
        (cls/generate-delete-shapes (pcb/empty-changes)
                                    file
                                    page
                                    (:objects page)
                                    #{(:id copy-child)}
                                    {:components-v2 true})

        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy-root)
                                                true)

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-reset-after-moving-shape
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-component-with-many-children-and-copy :component1
                                                                :main-root
                                                                [:main-child1 :main-child2 :main-child3]
                                                                :copy-root
                                                                :copy-root-params {:children-labels [:copy-child]})
                 (ths/add-sample-shape :free-shape))

        page (thf/current-page file)
        copy-root (ths/get-shape file :copy-root)
        copy-child1 (ths/get-shape file :copy-child)

        ;; ==== Action

        ;; IMPORTANT: as modifying copies structure is now forbidden, this action
        ;; will not have any effect, and so the parent shape won't also be touched.
        changes (cls/generate-relocate (pcb/empty-changes)
                                       (:objects page)
                                       (thi/id :copy-root)      ; parent-id
                                       (:id page)               ; page-id
                                       2                        ; to-index
                                       #{(:id copy-child1)})     ; ids


        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy-root)
                                                true)

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-reset-after-changing-upper
  (let [;; ==== Setup
        file   (-> (thf/sample-file :file1)
                   (tho/add-nested-component-with-copy :component1
                                                       :main1-root
                                                       :main1-child
                                                       :component2
                                                       :main2-root
                                                       :main2-nested-head
                                                       :copy2-root
                                                       :main2-root-params {:fills (ths/sample-fills-color
                                                                                   :fill-color "#abcdef")}))
        page       (thf/current-page file)
        copy2-root (ths/get-shape file :copy2-root)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy2-root)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy2-root)
                                                true)

        file'     (thf/apply-changes file changes)

        ;; ==== Get
        copy2-root' (ths/get-shape file' :copy2-root)
        fills'      (:fills copy2-root')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#abcdef"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))))

(t/deftest test-reset-after-changing-lower
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-nested-component-with-copy :component1
                                                        :main1-root
                                                        :main1-child
                                                        :component2
                                                        :main2-root
                                                        :main2-nested-head
                                                        :copy2-root
                                                        :copy2-root-params {:children-labels [:copy2-child]}))
        page        (thf/current-page file)
        copy2-root  (ths/get-shape file :copy2-root)
        copy2-child (ths/get-shape file :copy2-child)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy2-child)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy2-root)
                                                true)

        file'     (thf/apply-changes file changes)

        ;; ==== Get
        copy2-root'  (ths/get-shape file' :copy2-root)
        copy2-child' (ths/get-shape file' :copy2-child)
        fills'       (:fills copy2-child')
        fill'        (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (some? copy2-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#FFFFFF"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))
    (t/is (= (:touched copy2-child') nil))))
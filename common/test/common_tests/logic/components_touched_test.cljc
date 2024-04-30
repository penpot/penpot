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
        file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component-with-copy :component1
                                                      :main-root
                                                      :main-child
                                                      :copy-root
                                                      :main-child-params {:fills (thf/sample-fills-color
                                                                                  :fill-color "#abcdef")}))
        page      (thf/current-page file)
        copy-root (thf/get-shape file :copy-root)

        ;; Action
        update-fn (fn [shape]
                    (assoc shape :fills (thf/sample-fills-color :fill-color "#fabada")))

        changes   (cflh/generate-update-shapes (pcb/empty-changes nil (:id page))
                                               (:shapes copy-root)
                                               update-fn
                                               (:objects page)
                                               {})

        file'     (thf/apply-changes file changes)

        ;; Get
        copy-root'  (thf/get-shape file' :copy-root)
        copy-child' (thf/get-shape-by-id file' (first (:shapes copy-root')))
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; Check
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:fill-group}))))

(t/deftest test-not-touched-when-adding-shape
  (let [;; Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-simple-component-with-copy :component1
                                                     :main-root
                                                     :main-child
                                                     :copy-root)
                 (thf/add-sample-shape :free-shape))

        page      (thf/current-page file)
        copy-root (thf/get-shape file :copy-root)

        ;; Action
        ;; IMPORTANT: as modifying copies structure is now forbidden, this action
        ;; will not have any effect, and so the parent shape won't also be touched.
        changes (cflh/generate-relocate-shapes (pcb/empty-changes)
                                               (:objects page)
                                               #{(:parent-id copy-root)}   ; parents
                                               (thi/id :copy-root)      ; parent-id
                                               (:id page)               ; page-id
                                               0                        ; to-index
                                               #{(thi/id :free-shape)}) ; ids

        file'   (thf/apply-changes file changes)

        ;; Get
        copy-root'  (thf/get-shape file' :copy-root)
        copy-child' (thf/get-shape-by-id file' (first (:shapes copy-root')))]

    ;; Check
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-touched-when-deleting-shape
  (let [;; Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root))

        page       (thf/current-page file)
        copy-root  (thf/get-shape file :copy-root)

        ;; Action
        ;; IMPORTANT: as modifying copies structure is now forbidden, this action will not
        ;; delete the child shape, but hide it (thus setting the visibility group).
        [_all-parents changes]
        (cflh/generate-delete-shapes (pcb/empty-changes)
                                     file
                                     page
                                     (:objects page)
                                     (set (:shapes copy-root))
                                     {:components-v2 true})

        file'   (thf/apply-changes file changes)

        ;; Get
        copy-root'  (thf/get-shape file' :copy-root)
        copy-child' (thf/get-shape-by-id file' (first (:shapes copy-root')))]

    ;; Check
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:visibility-group}))))

(t/deftest test-not-touched-when-moving-shape
  (let [;; Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-component-with-many-children-and-copy :component1
                                                                :main-root
                                                                [:main-child1 :main-child2 :main-child3]
                                                                :copy-root)
                 (thf/add-sample-shape :free-shape))

        page (thf/current-page file)
        copy-root (thf/get-shape file :copy-root)
        copy-child1 (thf/get-shape-by-id file (first (:shapes copy-root)))

        ;; Action
        ;; IMPORTANT: as modifying copies structure is now forbidden, this action
        ;; will not have any effect, and so the parent shape won't also be touched.
        changes (cflh/generate-relocate-shapes (pcb/empty-changes)
                                               (:objects page)
                                               #{(:parent-id copy-child1)}   ; parents
                                               (thi/id :copy-root)      ; parent-id
                                               (:id page)               ; page-id
                                               2                        ; to-index
                                               #{(:id copy-child1)}) ; ids

        file'   (thf/apply-changes file changes)

        ;; Get
        copy-root'  (thf/get-shape file' :copy-root)
        copy-child' (thf/get-shape-by-id file' (first (:shapes copy-root')))]

    ;; Check
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.comp-sync-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.component :as ctk]
   [app.common.types.shape-tree :as ctst]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-sync-when-changing-attribute
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
        main-child (ths/get-shape file :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                                 (:objects page)
                                                 {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-sync-when-changing-attribute-from-library
  (let [;; ==== Setup
        library   (-> (thf/sample-file :file1)
                      (tho/add-simple-component :component1
                                                :main-root
                                                :main-child
                                                :copy-root
                                                :main-child-params {:fills (ths/sample-fills-color
                                                                            :fill-color "#abcdef")}))

        file       (-> (thf/sample-file :file)
                       (thc/instantiate-component :component1 :copy-root
                                                  :library library
                                                  :children-labels [:copy-child]))

        page       (thf/current-page library)
        main-child (ths/get-shape library :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                                 (:objects page)
                                                 {})

        updated-library (thf/apply-changes library changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id file)
                                                     (thi/id :component1)
                                                     (:id updated-library)
                                                     {(:id updated-library) updated-library
                                                      (:id file) file}
                                                     (:id file))

        file'     (thf/apply-changes file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-sync-when-changing-attribute-preserve-touched
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
        main-child (ths/get-shape file :main-child)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes1     (-> (pcb/empty-changes nil (:id page))
                         (cls/generate-update-shapes
                          #{(:id copy-child)}
                          (fn [shape]
                            (assoc shape :fills (ths/sample-fills-color :fill-color "#aaaaaa")))
                          (:objects page)
                          {})
                         (cls/generate-update-shapes
                          #{(:id main-child)}
                          (fn [shape]
                            (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                          (:objects page)
                          {}))

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)
        fills'      (:fills copy-child')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#aaaaaa"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:fill-group}))))

(t/deftest test-sync-when-adding-shape
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component-with-copy :component1
                                                      :main-root
                                                      :main-child
                                                      :copy-root
                                                      :copy-root-params {:children-labels [:copy-child]})
                  (ths/add-sample-shape :free-shape))

        page      (thf/current-page file)

        ;; ==== Action
        changes1 (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (thi/id :main-root)       ; parent-id
                                        0                         ; to-index
                                        #{(thi/id :free-shape)})   ; ids

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        main-free-shape'   (ths/get-shape file' :free-shape)
        copy-root'         (ths/get-shape file' :copy-root)
        copy-new-child-id' (d/seek #(not= % (thi/id :copy-child)) (:shapes copy-root'))
        copy-new-child'    (ths/get-shape-by-id file' copy-new-child-id')]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-new-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-new-child') nil))
    (t/is (ctst/parent-of? copy-root' copy-new-child'))
    (t/is (ctk/is-main-of? main-free-shape' copy-new-child'))))

(t/deftest test-sync-when-deleting-shape
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :copy-root-params {:children-labels [:copy-child]}))

        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action

        ;; IMPORTANT: as modifying copies structure is now forbidden, this action will not
        ;; delete the child shape, but hide it (thus setting the visibility group).
        [_all-parents changes1]
        (cls/generate-delete-shapes (pcb/empty-changes)
                                    file
                                    page
                                    (:objects page)
                                    #{(:id main-child)}
                                    {:components-v2 true})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'     (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (nil? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (empty? (:shapes copy-root')))))

(t/deftest test-sync-when-moving-shape
  (let [;; ==== Setup
        file (-> (thf/sample-file :file1)
                 (tho/add-component-with-many-children-and-copy :component1
                                                                :main-root
                                                                [:main-child1 :main-child2 :main-child3]
                                                                :copy-root
                                                                :copy-root-params {:children-labels [:copy-child1
                                                                                                     :copy-child2
                                                                                                     :copy-child3]})
                 (ths/add-sample-shape :free-shape))

        page (thf/current-page file)
        main-child1 (ths/get-shape file :main-child1)

        ;; ==== Action
        changes1     (cls/generate-relocate (-> (pcb/empty-changes nil)
                                                (pcb/with-page-id (:id page))
                                                (pcb/with-objects (:objects page)))
                                            (thi/id :main-root)         ; parent-id
                                            2                           ; to-index
                                            #{(:id main-child1)})       ; ids


        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'        (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy-root'   (ths/get-shape file' :copy-root)
        copy-child1' (ths/get-shape file' :copy-child1)
        copy-child2' (ths/get-shape file' :copy-child2)
        copy-child3' (ths/get-shape file' :copy-child3)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child1'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child1') nil))
    (t/is (= (:touched copy-child2') nil))
    (t/is (= (:touched copy-child3') nil))
    (t/is (= (second (:shapes copy-root')) (:id copy-child1')))
    (t/is (= (first (:shapes copy-root')) (:id copy-child2')))
    (t/is (= (nth (:shapes copy-root') 2) (:id copy-child3')))))

(t/deftest test-sync-when-changing-upper
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
        main2-root (ths/get-shape file :main2-root)

        ;; ==== Action
        changes1  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id main2-root)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component2)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'        (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy2-root' (ths/get-shape file' :copy2-root)
        fills'      (:fills copy2-root')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))))

(t/deftest test-sync-when-changing-lower-near
  (let [;; ==== Setup
        file          (-> (thf/sample-file :file1)
                          (tho/add-nested-component-with-copy :component1
                                                              :main1-root
                                                              :main1-child
                                                              :component2
                                                              :main2-root
                                                              :main2-nested-head
                                                              :copy2-root
                                                              :copy2-root-params {:children-labels [:copy2-child]}))
        page              (thf/current-page file)
        main2-nested-head (ths/get-shape file :main2-nested-head)

        ;; ==== Action
        changes1  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id main2-nested-head)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component2)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        file'        (thf/apply-changes updated-file changes2)

        ;; ==== Get
        copy2-root'  (ths/get-shape file' :copy2-root)
        copy2-child' (ths/get-shape file' :copy2-child)
        fills'       (:fills copy2-child')
        fill'        (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (some? copy2-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))
    (t/is (= (:touched copy2-child') nil))))

(t/deftest test-sync-when-changing-lower-remote
  (let [;; ==== Setup
        file          (-> (thf/sample-file :file1)
                          (tho/add-nested-component-with-copy :component1
                                                              :main1-root
                                                              :main1-child
                                                              :component2
                                                              :main2-root
                                                              :main2-nested-head
                                                              :copy2-root
                                                              :copy2-root-params {:children-labels [:copy2-child]}))
        page              (thf/current-page file)
        main1-root (ths/get-shape file :main1-root)

        ;; ==== Action
        changes1  (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id main1-root)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

        updated-file (thf/apply-changes file changes1)

        changes2     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id updated-file)
                                                     (thi/id :component1)
                                                     (:id updated-file)
                                                     {(:id updated-file) updated-file}
                                                     (:id updated-file))

        synced-file (thf/apply-changes updated-file changes2)

        changes3     (cll/generate-sync-file-changes (pcb/empty-changes)
                                                     nil
                                                     :components
                                                     (:id synced-file)
                                                     (thi/id :component2)
                                                     (:id synced-file)
                                                     {(:id synced-file) synced-file}
                                                     (:id synced-file))

        file'        (thf/apply-changes synced-file changes3)

        ;; ==== Get
        copy2-root'  (ths/get-shape file' :copy2-root)
        copy2-child' (ths/get-shape file' :copy2-child)
        fills'       (:fills copy2-child')
        fill'        (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (some? copy2-child'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))
    (t/is (= (:touched copy2-child') nil))))

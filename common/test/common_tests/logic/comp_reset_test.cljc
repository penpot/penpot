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
   [app.common.types.container :as ctn]
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
                                                (:id copy-root))

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
                                                (:id copy-root))

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
        changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                           (pcb/with-page-id (:id page))
                                           (pcb/with-objects (:objects page)))
                                       (thi/id :copy-root)      ; parent-id
                                       0                        ; to-index
                                       #{(thi/id :free-shape)}) ; ids


        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy-root))

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
                                                (:id copy-root))

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
        changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                           (pcb/with-page-id (:id page))
                                           (pcb/with-objects (:objects page)))
                                       (thi/id :copy-root)      ; parent-id
                                       2                        ; to-index
                                       #{(:id copy-child1)})     ; ids


        file-mdf  (thf/apply-changes file changes)
        page-mdf  (thf/current-page file-mdf)

        changes   (cll/generate-reset-component (pcb/empty-changes)
                                                file-mdf
                                                {(:id file-mdf) file-mdf}
                                                page-mdf
                                                (:id copy-root))

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
                                                (:id copy2-root))

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
                                                (:id copy2-root))

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

(t/deftest test-reset-with-propagation-updates-copies
  ;; When a nested copy inside a main component has an override and we
  ;; reset it passing a propagate-fn, the reset must be propagated to
  ;; all copies of that component so they reflect the canonical color.
  (let [;; ==== Setup
        file
        (-> (thf/sample-file :file1)
            ;; component1: main1-root / main1-child (fill "#aabbcc")
            ;; component2: main2-root contains nested-head (instance of component1)
            ;; copy2-root: copy of component2
            (tho/add-nested-component-with-copy
             :component1 :main1-root :main1-child
             :component2 :main2-root :nested-head
             :copy2-root
             :main1-child-params {:fills (ths/sample-fills-color :fill-color "#aabbcc")}
             :copy2-root-params {:children-labels [:copy2-nested-head]}))

        propagate-fn (fn [f]
                       (-> f
                           (tho/propagate-component-changes :component1)
                           (tho/propagate-component-changes :component2)))

        ;; ==== Action – override the nested-head color, then reset it with propagation
        file'
        (-> file
            (tho/update-bottom-color :nested-head "#fabada" :propagate-fn propagate-fn)
            (tho/reset-overrides (ths/get-shape file :nested-head) :propagate-fn propagate-fn))

        ;; ==== Get
        copy2-bottom-color (tho/bottom-fill-color file' :copy2-root)]

    ;; ==== Check
    ;; After reset + propagation the copy should mirror the canonical color
    (t/is (= copy2-bottom-color "#aabbcc"))))

(t/deftest test-reset-nested-component-from-different-library-preserves-children
  ;; Regression test for the cross-library find-main-container bug:
  ;; avatar-img lives in library1, avatar (which nests avatar-img) lives in library2,
  ;; and card (which nests avatar) lives in the current file.
  ;; Resetting overrides on avatar used to delete avatar-img because find-main-container
  ;; only searched within the single library argument (library2) and could not find
  ;; the avatar-img component which belongs to library1.
  (let [;; ==== Setup
        ;; library1: avatar-img-component (simple component with a child rect)
        library1
        (-> (thf/sample-file :library1 :is-shared true)
            (tho/add-simple-component :avatar-img-component
                                      :avatar-img-main
                                      :avatar-img-child
                                      :child-params {:fills (ths/sample-fills-color :fill-color "#abcdef")}))

        ;; library2: avatar-component (nests avatar-img from library1)
        library2
        (-> (thf/sample-file :library2 :is-shared true)
            (tho/add-frame :avatar-main)
            (thc/instantiate-component :avatar-img-component
                                       :avatar-img-nested-head
                                       :library library1
                                       :parent-label :avatar-main
                                       :children-labels [:avatar-img-nested-child])
            (thc/make-component :avatar-component :avatar-main))

        ;; file: card-component (nests avatar from library2) + a copy of card
        file
        (-> (thf/sample-file :file)
            (tho/add-frame :card-main)
            (thc/instantiate-component :avatar-component
                                       :avatar-in-card-main
                                       :library library2
                                       :parent-label :card-main
                                       :children-labels [:avatar-img-in-card-main])
            (thc/make-component :card-component :card-main)
            (thc/instantiate-component :card-component
                                       :card-copy
                                       :children-labels [:avatar-in-card-copy]))

        page              (thf/current-page file)
        avatar-in-card-copy (ths/get-shape file :avatar-in-card-copy)

        ;; ==== Action: override a fill on the avatar copy, then reset
        changes
        (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                    #{(:id avatar-in-card-copy)}
                                    (fn [shape]
                                      (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                    (:objects page)
                                    {})

        file-mdf       (thf/apply-changes file changes)
        container-mdf  (ctn/get-container (:data file-mdf) :page (:id page))

        changes
        (cll/generate-reset-component (pcb/empty-changes)
                                      file-mdf
                                      {(:id file-mdf) file-mdf
                                       (:id library1) library1
                                       (:id library2) library2}
                                      container-mdf
                                      (:id avatar-in-card-copy))

        file' (thf/apply-changes file-mdf changes :validate? false)

        ;; ==== Get
        avatar-in-card-copy'  (ths/get-shape file' :avatar-in-card-copy)
        ;; avatar-img nested head must still exist inside avatar-in-card-copy after reset
        avatar-img-copy-id    (first (:shapes avatar-in-card-copy'))
        avatar-img-copy'      (when avatar-img-copy-id
                                (ths/get-shape-by-id file' avatar-img-copy-id))]

    ;; ==== Check
    (t/is (some? avatar-in-card-copy') "avatar copy must still exist after reset")
    (t/is (some? avatar-img-copy-id)   "avatar-img copy must not be deleted from avatar's children")
    (t/is (some? avatar-img-copy')     "avatar-img copy shape must be present in objects")))

(t/deftest test-reset-without-propagation-does-not-update-copies
  ;; This is the regression test for the misplaced-parenthesis bug: when
  ;; propagate-fn is NOT passed to reset-overrides the copies of the component
  ;; must still hold the overridden value because the component sync never ran.
  (let [;; ==== Setup
        file
        (-> (thf/sample-file :file1)
            (tho/add-nested-component-with-copy
             :component1 :main1-root :main1-child
             :component2 :main2-root :nested-head
             :copy2-root
             :main1-child-params {:fills (ths/sample-fills-color :fill-color "#aabbcc")}
             :copy2-root-params {:children-labels [:copy2-nested-head]}))

        propagate-fn (fn [f]
                       (-> f
                           (tho/propagate-component-changes :component1)
                           (tho/propagate-component-changes :component2)))

        ;; ==== Action – override the nested-head color, then reset WITHOUT propagation
        file'
        (-> file
            (tho/update-bottom-color :nested-head "#fabada" :propagate-fn propagate-fn)
            ;; Reset without propagate-fn: the component definition is updated but
            ;; the change is never pushed to the copy.
            (tho/reset-overrides (ths/get-shape file :nested-head)))

        ;; ==== Get
        copy2-bottom-color (tho/bottom-fill-color file' :copy2-root)]

    ;; ==== Check
    ;; Without propagation the copy still reflects the overridden color
    (t/is (= copy2-bottom-color "#fabada"))))
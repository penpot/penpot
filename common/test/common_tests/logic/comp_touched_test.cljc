;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-touched-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest test-touched-when-changing-attribute
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
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy-child)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

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
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:fill-group}))))

(t/deftest test-touched-from-library
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
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy-child)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

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
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:fill-group}))))

(t/deftest test-not-touched-when-adding-shape
  (let [;; ==== Setup
        file  (-> (thf/sample-file :file1)
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
                                       (thi/id :copy-root)       ; parent-id
                                       0                         ; to-index
                                       #{(thi/id :free-shape)})   ; ids


        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') nil))))

(t/deftest test-not-touched-when-deleting-shape
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-simple-component-with-copy :component1
                                                           :main-root
                                                           :main-child
                                                           :copy-root
                                                           :copy-root-params {:children-labels [:copy-child]}))

        page       (thf/current-page file)
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
                                    {})

        file'   (thf/apply-changes file changes)

        ;; ==== Get
        copy-root'  (ths/get-shape file' :copy-root)
        copy-child' (ths/get-shape file' :copy-child)]

    ;; ==== Check
    (t/is (some? copy-root'))
    (t/is (some? copy-child'))
    (t/is (= (:touched copy-root') nil))
    (t/is (= (:touched copy-child') #{:visibility-group}))))

(t/deftest test-not-touched-when-moving-shape
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
        copy-child1 (ths/get-shape file :copy-child1)

        ;; ==== Action

        ;; IMPORTANT: as modifying copies structure is now forbidden, this action
        ;; will not have any effect, and so the parent shape won't also be touched.
        changes (cls/generate-relocate (-> (pcb/empty-changes nil)
                                           (pcb/with-page-id (:id page))
                                           (pcb/with-objects (:objects page)))
                                       (thi/id :copy-root)         ; parent-id
                                       2                           ; to-index
                                       #{(:id copy-child1)})       ; ids


        file'   (thf/apply-changes file changes)

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
    (t/is (= (first (:shapes copy-root')) (:id copy-child1')))
    (t/is (= (second (:shapes copy-root')) (:id copy-child2')))
    (t/is (= (nth (:shapes copy-root') 2) (:id copy-child3')))))

(t/deftest test-touched-when-changing-upper
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

        file'     (thf/apply-changes file changes)

        ;; ==== Get
        copy2-root' (ths/get-shape file' :copy2-root)
        fills'      (:fills copy2-root')
        fill'       (first fills')]

    ;; ==== Check
    (t/is (some? copy2-root'))
    (t/is (= (count fills') 1))
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') #{:fill-group}))))

(t/deftest test-touched-when-changing-lower
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
        copy2-child (ths/get-shape file :copy2-child)

        ;; ==== Action
        changes   (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(:id copy2-child)}
                                              (fn [shape]
                                                (assoc shape :fills (ths/sample-fills-color :fill-color "#fabada")))
                                              (:objects page)
                                              {})

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
    (t/is (= (:fill-color fill') "#fabada"))
    (t/is (= (:fill-opacity fill') 1))
    (t/is (= (:touched copy2-root') nil))
    (t/is (= (:touched copy2-child') #{:fill-group}))))

(t/deftest test-undo-of-a-touch-gives-back-remote-synced
  ;; a copy shape synced from a remote main (after a reset below the copy
  ;; root, say) loses :remote-synced when it is touched
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                                        :copy-root-params {:children-labels [:copy-child]})
                    (ths/update-shape :copy-child :remote-synced true))
        page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id :copy-child)}
                                            #(assoc % :opacity 0.5)
                                            (:objects page)
                                            {})
        file'   (thf/apply-changes file changes)
        file''  (thf/apply-undo-changes file' changes)]
    (t/is (nil? (:remote-synced (ths/get-shape file' :copy-child))))
    (t/is (true? (:remote-synced (ths/get-shape file'' :copy-child))))
    (t/is (nil? (:touched (ths/get-shape file'' :copy-child))))))

(t/deftest test-touched-set-by-an-update-then-its-attrs-touch
  ;; an update sets the touched groups of a copy shape and changes an
  ;; attribute: the attribute touches its group on top of them, whatever the
  ;; order of the shape's keys
  (doseq [[attr value group] [[:fills (ths/sample-fills-color :fill-color "#ff0000") :fill-group]
                              [:opacity 0.5 :layer-effects-group]
                              [:strokes [{:stroke-color "#ff0000" :stroke-opacity 1 :stroke-width 2}] :stroke-group]
                              [:hidden true :visibility-group]
                              [:blocked true :modifiable-group]
                              [:r1 5 :radius-group]
                              [:name "Renamed" :name-group]]]
    (let [file    (-> (thf/sample-file :file1)
                      (tho/add-simple-component-with-copy :component1 :main-root :main-child :copy-root
                                                          :copy-root-params {:children-labels [:copy-child]}))
          page    (thf/current-page file)
          changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                              #{(thi/id :copy-child)}
                                              #(assoc % attr value :touched #{:shadow-group})
                                              (:objects page)
                                              {})
          file'   (thf/apply-changes file changes)]
      (t/is (= #{:shadow-group group} (:touched (ths/get-shape file' :copy-child))) (name attr)))))


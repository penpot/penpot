;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.text-touched-test
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

(t/deftest test-text-copy-changed-attribute
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                #{(:id copy-child)}
                                                (fn [shape]
                                                  (assoc-in shape [:content :children 0 :children 0 :font-size] "32"))
                                                (:objects page)
                                                {})

        file'       (thf/apply-changes file changes)
        copy-child' (ths/get-shape file' :copy-child)]
    (t/is (= #{:content-group :text-content-attribute} (:touched copy-child')))))

(t/deftest test-text-copy-changed-text
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                #{(:id copy-child)}
                                                (fn [shape]
                                                  (assoc-in shape [:content :children 0 :children 0 :text] "Bye"))
                                                (:objects page)
                                                {})

        file'       (thf/apply-changes file changes)
        copy-child' (ths/get-shape file' :copy-child)]
    (t/is (= #{:content-group :text-content-text} (:touched copy-child')))))

(t/deftest test-text-copy-changed-both
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                #{(:id copy-child)}
                                                (fn [shape]
                                                  (-> shape
                                                      (assoc-in [:content :children 0 :children 0 :font-size] "32")
                                                      (assoc-in [:content :children 0 :children 0 :text] "Bye")))
                                                (:objects page)
                                                {})

        file'       (thf/apply-changes file changes)
        copy-child' (ths/get-shape file' :copy-child)]
    (t/is (= #{:content-group :text-content-attribute :text-content-text} (:touched copy-child')))))

(t/deftest test-text-copy-changed-structure-same-attrs
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                #{(:id copy-child)}
                                                (fn [shape]
                                                  (let [line (get-in shape [:content :children 0 :children 0])]
                                                    (update-in shape [:content :children 0 :children]
                                                               #(conj % line))))
                                                (:objects page)
                                                {})

        file'       (thf/apply-changes file changes)
        copy-child' (ths/get-shape file' :copy-child)]
    (t/is (= #{:content-group :text-content-structure} (:touched copy-child')))))

(t/deftest test-text-copy-changed-structure-diff-attrs
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        copy-child (ths/get-shape file :copy-child)

        ;; ==== Action
        changes     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                #{(:id copy-child)}
                                                (fn [shape]
                                                  (let [line (-> shape
                                                                 (get-in [:content :children 0 :children 0])
                                                                 (assoc :font-size "32"))]
                                                    (update-in shape [:content :children 0 :children]
                                                               #(conj % line))))
                                                (:objects page)
                                                {})

        file'       (thf/apply-changes file changes)
        copy-child' (ths/get-shape file' :copy-child)]
    (t/is (= #{:content-group :text-content-structure} (:touched copy-child')))))


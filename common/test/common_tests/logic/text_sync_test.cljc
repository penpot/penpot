;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.logic.text-sync-test
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


(t/deftest test-sync-unchanged-copy-when-changed-attribute
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :font-size] "32"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "32" (:font-size line)))
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-unchanged-copy-when-changed-text
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Bye"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "14" (:font-size line)))
    (t/is (= "Bye" (:text line)))))

(t/deftest test-sync-unchanged-copy-when-changed-both
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page       (thf/current-page file)
        main-child (ths/get-shape file :main-child)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :text] "Bye")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "32" (:font-size line)))
    (t/is (= "Bye" (:text line)))))

(t/deftest test-sync-updated-attr-copy-when-changed-attribute
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc-in shape [:content :children 0 :children 0 :children 0 :font-weight] "700"))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :font-size] "32"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr doesn't change, because it was touched
    (t/is (= "14" (:font-size line)))
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-updated-attr-copy-when-changed-text
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc-in shape [:content :children 0 :children 0 :children 0 :font-weight] "700"))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Bye"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "14" (:font-size line)))
    ;; The text is updated because only attrs were touched
    (t/is (= "Bye" (:text line)))))

(t/deftest test-sync-updated-attr-copy-when-changed-both
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc-in shape [:content :children 0 :children 0 :children 0 :font-weight] "700"))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :text] "Bye")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr doesn't change, because it was touched
    (t/is (= "14" (:font-size line)))
    ;; The text is updated because only attrs were touched
    (t/is (= "Bye" (:text line)))))

(t/deftest test-sync-updated-text-copy-when-changed-attribute
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Hi"))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :font-size] "32"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr is updated because only text were touched
    (t/is (= "32" (:font-size line)))
    (t/is (= "Hi" (:text line)))))

(t/deftest test-sync-updated-text-copy-when-changed-text
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Hi"))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Bye"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "14" (:font-size line)))
    ;; The text doesn't change, because it was touched
    (t/is (= "Hi" (:text line)))))

(t/deftest test-sync-updated-text-copy-when-changed-both
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Hi"))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :text] "Bye")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr is updated because only text were touched
    (t/is (= "32" (:font-size line)))
    ;; The text doesn't change, because it was touched
    (t/is (= "Hi" (:text line)))))

(t/deftest test-sync-updated-both-copy-when-changed-attribute
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (-> shape
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :font-weight] "700")
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :text] "Hi")))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :font-size] "32"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr doesn't change, because it was touched
    (t/is (= "14" (:font-size line)))
    (t/is (= "Hi" (:text line)))))

(t/deftest test-sync-updated-both-copy-when-changed-text
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (-> shape
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :font-weight] "700")
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :text] "Hi")))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Bye"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "14" (:font-size line)))
    ;; The text doesn't change, because it was touched
    (t/is (= "Hi" (:text line)))))

(t/deftest test-sync-updated-both-copy-when-changed-both
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (-> shape
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :font-weight] "700")
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :text] "Hi")))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :text] "Bye")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr doesn't change, because it was touched
    (t/is (= "14" (:font-size line)))
    ;; The text doesn't change, because it was touched
    (t/is (= "Hi" (:text line)))))

(t/deftest test-sync-updated-structure-same-attrs-copy-when-changed-attribute
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (let [line (get-in shape [:content :children 0 :children 0 :children 0])]
                                                   (update-in shape [:content :children 0 :children 0 :children]
                                                              #(conj % line))))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   ;; Update the attrs on all the content tree
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :font-size] "32")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr is updated because all the attrs on the structure are equal
    (t/is (= "32" (:font-size line)))
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-updated-structure-same-attrs-copy-when-changed-text
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (let [line (get-in shape [:content :children 0 :children 0 :children 0])]
                                                   (update-in shape [:content :children 0 :children 0 :children]
                                                              #(conj % line))))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Bye"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "14" (:font-size line)))
    ;; The text doesn't change, because the structure was touched
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-updated-structure-same-attrs-copy-when-changed-both
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (let [line (get-in shape [:content :children 0 :children 0 :children 0])]
                                                   (update-in shape [:content :children 0 :children 0 :children]
                                                              #(conj % line))))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                     ;; Update the attrs on all the content tree
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :text] "Bye")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr is updated because all the attrs on the structure are equal
    (t/is (= "32" (:font-size line)))
    ;; The text doesn't change, because the structure was touched
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-updated-structure-diff-attrs-copy-when-changed-attribute
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (let [line (-> (get-in shape [:content :children 0 :children 0 :children 0])
                                                                (assoc :font-weight "700"))]
                                                   (update-in shape [:content :children 0 :children 0 :children]
                                                              #(conj % line))))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   ;; Update the attrs on all the content tree
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :font-size] "32")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr doesn't change, because not all the attrs on the structure are equal
    (t/is (= "14" (:font-size line)))
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-updated-structure-diff-attrs-copy-when-changed-text
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (let [line (-> (get-in shape [:content :children 0 :children 0 :children 0])
                                                                (assoc :font-weight "700"))]
                                                   (update-in shape [:content :children 0 :children 0 :children]
                                                              #(conj % line))))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc-in shape [:content :children 0 :children 0 :children 0 :text] "Bye"))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= "14" (:font-size line)))
    ;; The text doesn't change, because the structure was touched
    (t/is (= "hello world" (:text line)))))

(t/deftest test-sync-updated-structure-diff-attrs-copy-when-changed-both
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (let [line (-> (get-in shape [:content :children 0 :children 0 :children 0])
                                                                (assoc :font-weight "700"))]
                                                   (update-in shape [:content :children 0 :children 0 :children]
                                                              #(conj % line))))
                                               (:objects (thf/current-page file0))
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                     ;; Update the attrs on all the content tree
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :font-size] "32")
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :text] "Bye")))
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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    ;; The attr doesn't change, because not all the attrs on the structure are equal
    (t/is (= "14" (:font-size line)))
    ;; The text doesn't change, because the structure was touched
    (t/is (= "hello world" (:text line)))))
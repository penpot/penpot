;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-sync-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.comp-processors :as cfcp]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.text :as txt]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)


(t/deftest sync-unchanged-copy-when-changed-attribute
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

(t/deftest sync-unchanged-copy-when-changed-text
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

(t/deftest sync-unchanged-copy-when-changed-both
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

(t/deftest sync-updated-attr-copy-when-changed-attribute
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
    ;; Each style is an override of its own: the copy's weight does not
    ;; keep the main's size out
    (t/is (= "32" (:font-size line)))
    (t/is (= "hello world" (:text line)))))

(t/deftest sync-updated-attr-copy-when-changed-text
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

(t/deftest sync-updated-attr-copy-when-changed-both
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
    ;; Each style is an override of its own: the copy's weight does not
    ;; keep the main's size out
    (t/is (= "32" (:font-size line)))
    ;; The text is updated because only attrs were touched
    (t/is (= "Bye" (:text line)))))

(t/deftest sync-updated-text-copy-when-changed-attribute
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

(t/deftest sync-updated-text-copy-when-changed-text
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

(t/deftest sync-updated-text-copy-when-changed-both
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

(t/deftest sync-updated-both-copy-when-changed-attribute
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
    ;; Letters and a style overridden keep the other styles following the
    ;; main
    (t/is (= "32" (:font-size line)))
    (t/is (= "Hi" (:text line)))))

(t/deftest sync-updated-both-copy-when-changed-text
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

(t/deftest sync-updated-both-copy-when-changed-both
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
    ;; Letters and a style overridden keep the other styles following the
    ;; main
    (t/is (= "32" (:font-size line)))
    ;; The text doesn't change, because it was touched
    (t/is (= "Hi" (:text line)))))

(t/deftest sync-updated-structure-same-attrs-copy-when-changed-attribute
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
    ;; The style follows the main, as the copy only changed its letters
    (t/is (= "32" (:font-size line)))
    ;; The copy keeps its letters
    (t/is (= "hello worldhello world" (txt/content->text (:content copy-child'))))))

(t/deftest sync-updated-structure-same-attrs-copy-when-changed-text
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
    ;; The copy keeps its letters
    (t/is (= "hello worldhello world" (txt/content->text (:content copy-child'))))))

(t/deftest sync-updated-structure-same-attrs-copy-when-changed-both
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
    ;; The style follows the main, as the copy only changed its letters
    (t/is (= "32" (:font-size line)))
    ;; The copy keeps its letters
    (t/is (= "hello worldhello world" (txt/content->text (:content copy-child'))))))

(t/deftest sync-updated-structure-diff-attrs-copy-when-changed-attribute
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
    ;; The copy's own letters and weight do not keep the main's size out
    (t/is (= "32" (:font-size line)))
    (t/is (= "hello world" (:text line)))))

(t/deftest sync-updated-structure-diff-attrs-copy-when-changed-text
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
    ;; The copy keeps its letters
    (t/is (= "hello world" (:text line)))))

(t/deftest sync-updated-structure-diff-attrs-copy-when-changed-both
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
    ;; The copy's own letters and weight do not keep the main's size out
    (t/is (= "32" (:font-size line)))
    ;; The copy keeps its letters
    (t/is (= "hello world" (:text line)))))

(t/deftest sync-text-override-with-geometry-touched-keeps-width
  ;; Documents the #12721 gap: text+geometry touched receives font-size from
  ;; main but keeps local width until frontend re-measure.
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)
        copy-width (:width copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (-> shape
                                                     (assoc-in [:content :children 0 :children 0 :children 0 :text] "Hi")
                                                     (assoc :width (+ copy-width 25)
                                                            :touched #{:content-group
                                                                       :text-content-text
                                                                       :geometry-group})))
                                               (:objects page0)
                                               {:ignore-touched true})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)
        copy-before (ths/get-shape file :copy-child)

        ;; ==== Action — main font-size change + sync
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (-> shape
                                                       (assoc-in [:content :children 0 :children 0 :children 0 :font-size] "32")
                                                       (assoc :width (+ (:width shape) 50))))
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
    (t/is (= "Hi" (:text line)))
    (t/is (= (:width copy-before) (:width copy-child')))
    (t/is (contains? (:touched copy-child') :geometry-group))))

(t/deftest sync-text-only-override-without-geometry-receives-font-size
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
                                               (:objects page0)
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)
        copy-mid   (ths/get-shape file :copy-child)

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
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (= #{:content-group :text-content-text} (:touched copy-mid)))
    (t/is (not (contains? (:touched copy-mid) :geometry-group)))
    (t/is (= "32" (:font-size line)))
    (t/is (= "Hi" (:text line)))))

(t/deftest sync-fixed-copy-keeps-grow-type-and-local-width-when-geometry-touched
  (let [;; ==== Setup
        file0      (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world"
                                                :child-params {:grow-type :fixed :width 120 :height 40})
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page0      (thf/current-page file0)
        copy-child (ths/get-shape file0 :copy-child)

        changes    (cls/generate-update-shapes (pcb/empty-changes nil (:id page0))
                                               #{(:id copy-child)}
                                               (fn [shape]
                                                 (-> shape
                                                     (assoc :width 200
                                                            :touched #{:geometry-group})))
                                               (:objects page0)
                                               {:ignore-touched true})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)

        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(:id main-child)}
                                                 (fn [shape]
                                                   (assoc shape :width 300))
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

        file'       (thf/apply-changes updated-file changes2)
        copy-child' (ths/get-shape file' :copy-child)]
    (t/is (= :fixed (:grow-type copy-child')))
    (t/is (= 200 (:width copy-child')))
    (t/is (contains? (:touched copy-child') :geometry-group))))

(t/deftest sync-grow-type-override-kept-on-copy
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
                                                 (assoc shape :grow-type :fixed))
                                               (:objects page0)
                                               {})
        file       (thf/apply-changes file0 changes)
        main-child (ths/get-shape file :main-child)
        page       (thf/current-page file)
        copy-mid   (ths/get-shape file :copy-child)

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

        file'       (thf/apply-changes updated-file changes2)
        copy-child' (ths/get-shape file' :copy-child)
        line (get-in copy-child' [:content :children 0 :children 0 :children 0])]
    (t/is (contains? (:touched copy-mid) :text-font-group))
    (t/is (= :fixed (:grow-type copy-child')))
    (t/is (= "32" (:font-size line)))))

(t/deftest sync-copy-touched-only-in-content-group
  ;; A copy text whose `:touched` has `:content-group` but no text subgroup,
  ;; as files from before the subgroups hold it, keeps its own content
  ;; when the main changes, once the file is migrated.
  (let [;; ==== Setup
        file       (-> (thf/sample-file :file1)
                       (tho/add-frame-with-text :main-root :main-child "hello world")
                       (thc/make-component :component1 :main-root)
                       (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        copy-child (ths/get-shape file :copy-child)
        file       (-> file
                       (ths/update-shape :copy-child :content
                                         (assoc-in (:content copy-child)
                                                   [:children 0 :children 0 :children 0 :text]
                                                   "copy text"))
                       (ths/update-shape :copy-child :touched #{:content-group})
                       (update :data cfcp/convert-text-overrides {}))
        page       (thf/current-page file)

        ;; ==== Action
        changes1     (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                                 #{(thi/id :main-child)}
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
    (t/is (= "copy text" (:text line)))
    (t/is (= "32" (:font-size line)))
    (t/is (= #{:content-group :text-content-text} (:touched copy-child')))))

(defn- type-letters
  "The editor's commit of `text` (in the notation of `thtx`) into the text
   `label`: the content, and the name that follows it, a derived write."
  [file label text]
  (let [page    (thf/current-page file)
        content (thtx/content text)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            #(assoc % :content content
                                                    :name (txt/generate-shape-name (txt/content->text content)))
                                            (:objects page)
                                            {:ignore-touched #{:name}})]
    (thf/apply-changes file changes)))

(defn- sync-component1
  [file]
  (thf/apply-changes file (cll/generate-sync-file-changes (pcb/empty-changes) nil :components
                                                          (:id file) (thi/id :component1) (:id file)
                                                          {(:id file) file} (:id file))))

(t/deftest the-automatic-name-of-a-copy-text-follows-its-letters
  (let [file (-> (thf/sample-file :file1)
                 (thtx/add-text-component-with-copy [[["hello world"]]])
                 (type-letters :main-child [[["hello world"]]])
                 (sync-component1))]
    (t/testing "an untouched copy takes the main's letters and name"
      (let [file' (-> file (type-letters :main-child [[["main text"]]]) (sync-component1))]
        (t/is (= "main text" (:name (ths/get-shape file' :copy-child))))
        (t/is (nil? (:touched (ths/get-shape file' :copy-child))))))
    (t/testing "a copy that typed its letters keeps the name they give"
      (let [file' (-> file
                      (type-letters :copy-child [[["copy text"]]])
                      (type-letters :main-child [[["main text"]]])
                      (sync-component1))
            copy  (ths/get-shape file' :copy-child)]
        (t/is (= "copy text" (:name copy)))
        (t/is (= #{:content-group :text-content-text} (:touched copy)))))
    (t/testing "a main named by hand passes its name on"
      (let [file' (-> file
                      (ths/update-shape :main-child :name "Title")
                      (sync-component1))]
        (t/is (= "Title" (:name (ths/get-shape file' :copy-child))))))))


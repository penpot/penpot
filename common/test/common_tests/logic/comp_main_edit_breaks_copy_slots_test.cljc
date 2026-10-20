;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.comp-main-edit-breaks-copy-slots-test
  (:require
   [app.common.files.changes :as cpc]
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; Main-side reorder and deletion preserve copy reference integrity and support
;; exact undo across pages.
(defn- setup-main
  [file]
  (-> file
      (tho/add-simple-component :icon :icon-main :icon-child)
      (tho/add-frame :row-main :name "Row")
      (thc/instantiate-component :icon :icon-1 :parent-label :row-main)
      (thc/instantiate-component :icon :icon-2 :parent-label :row-main)
      (thc/instantiate-component :icon :icon-3 :parent-label :row-main)
      (thc/make-component :row :row-main)))

(defn- setup-file
  []
  (-> (thf/sample-file :file1)
      (setup-main)
      (thc/instantiate-component :row :row-copy :children-labels [:copy-1 :copy-2 :copy-3])))

(defn- add-copy-page-metadata
  [file]
  (let [page-id  (thi/id :page2)
        copy-id  (thi/id :copy-1)
        flow-id  (thi/new-id! :copy-flow)
        guide-id (thi/new-id! :copy-guide)]
    (-> file
        (ths/add-interaction :copy-2 :copy-1)
        (assoc-in [:data :pages-index page-id :flows flow-id]
                  {:id flow-id :name "Copy flow" :starting-frame copy-id})
        (assoc-in [:data :pages-index page-id :guides guide-id]
                  {:id guide-id :axis :x :position 10 :frame-id copy-id}))))

;; Same structure, but with :row-copy on a second page: the propagated
;; deletions then leave the page mounted in the changes builder.
(defn- setup-cross-page-file
  []
  (-> (thf/sample-file :file1 :page-label :page1)
      (setup-main)
      (thf/add-sample-page :page2)
      (thc/instantiate-component :row :row-copy :children-labels [:copy-1 :copy-2 :copy-3])
      (add-copy-page-metadata)
      (thf/switch-to-page :page1)))

(defn- delete-changes
  [file shape-label]
  (let [page (thf/current-page file)]
    (second (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                        file
                                        page
                                        (:objects page)
                                        #{(:id (ths/get-shape file shape-label))}
                                        {}))))

(defn- deleted-ids
  [changes]
  (->> (:redo-changes changes)
       (filter #(= :del-obj (:type %)))
       (map :id)))

;; Deleting a nested sub-head of a COPY only hides it (deleted-subinstance).
(t/deftest deleting-a-copy-subhead-only-hides-it
  (let [file    (setup-file)
        file'   (tho/delete-shape file :copy-1)
        copy-1' (ths/get-shape file' :copy-1)]
    (t/is (some? copy-1'))
    (t/is (true? (:hidden copy-1')))))

;; Reordering a nested sub-head within a COPY keeps referential integrity.
(t/deftest reordering-a-copy-subhead-keeps-referential-integrity
  (let [file     (setup-file)
        page     (thf/current-page file)
        copy-1   (ths/get-shape file :copy-1)
        row-copy (ths/get-shape file :row-copy)
        changes  (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (:id row-copy) 2 #{(:id copy-1)})
        file'    (thf/apply-changes file changes)]
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; Main-side reorders remain valid while component sync realigns copy children.
(t/deftest reordering-a-main-subhead-must-not-break-copies
  (let [file     (setup-file)
        page     (thf/current-page file)
        row-main (ths/get-shape file :row-main)
        icon-1   (ths/get-shape file :icon-1)
        ;; move the main's first sub-head to the end (index 2)
        changes  (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (:id row-main) 2 #{(:id icon-1)})
        file'    (thf/apply-changes file changes)]
    (t/is (some? (ths/get-shape file' :copy-1)))
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; Main-side deletions remove corresponding copy shapes and dangling refs.
(t/deftest deleting-a-main-subhead-must-not-break-copies
  (let [file  (setup-file)
        file' (tho/delete-shape file :icon-1)]
    (t/is (nil? (ths/get-shape file' :copy-1)))
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; Each propagated shape is deleted once so undo restores a valid tree.
(t/deftest propagated-deletions-are-emitted-once
  (let [file    (setup-file)
        changes (delete-changes file :icon-1)
        ids     (deleted-ids changes)]
    (t/is (= (count ids) (count (distinct ids))))))

;; Empty main groups propagate deletion to their corresponding copy groups.
(t/deftest emptied-main-groups-propagate-to-copies
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-simple-component :icon :icon-main :icon-child)
                  (tho/add-frame :row-main :name "Row")
                  (tho/add-group :grp-main :parent-label :row-main)
                  (thc/instantiate-component :icon :icon-1 :parent-label :grp-main)
                  (thc/make-component :row :row-main)
                  (thc/instantiate-component :row :row-copy :children-labels [:grp-copy])
                  (tho/delete-shape :icon-1))]
    (t/is (nil? (ths/get-shape file :grp-main)))
    (t/is (nil? (ths/get-shape file :grp-copy)))))

;; Propagated deletion supersedes hiding the same selected copy shape.
(t/deftest propagated-deletions-are-not-hidden-first
  (let [file    (setup-file)
        page    (thf/current-page file)
        icon-1  (ths/get-shape file :icon-1)
        copy-1  (ths/get-shape file :copy-1)
        [_ changes] (cls/generate-delete-shapes (pcb/empty-changes nil (:id page))
                                                file page (:objects page)
                                                #{(:id icon-1) (:id copy-1)}
                                                {})
        file'   (thf/apply-changes file changes)]
    (t/is (nil? (ths/get-shape file' :copy-1)))
    (t/is (not (contains? (->> (:redo-changes changes)
                               (filter #(= :mod-obj (:type %)))
                               (map :id)
                               (set))
                          (:id copy-1))))))

;; Cross-page propagation restores the exact original structure on undo.
(t/deftest deleting-a-main-subhead-propagates-across-pages
  (let [file      (setup-cross-page-file)
        changes   (delete-changes file :icon-1)
        ids       (deleted-ids changes)
        file'     (thf/apply-changes file changes)
        file''    (thf/apply-undo-changes file' changes)
        page2-id  (thi/id :page2)
        copy-2'   (ths/get-shape file' :copy-2 :page-label :page2)]
    (t/is (= (count ids) (count (distinct ids))))
    (t/is (nil? (ths/get-shape file' :copy-1 :page-label :page2)))
    (t/is (some? (ths/get-shape file' :copy-2 :page-label :page2)))
    (t/is (some? (ths/get-shape file' :copy-3 :page-label :page2)))
    (t/is (empty? (:interactions copy-2')))
    (t/is (nil? (get-in file' [:data :pages-index page2-id :flows (thi/id :copy-flow)])))
    (t/is (nil? (get-in file' [:data :pages-index page2-id :guides (thi/id :copy-guide)])))
    (t/is (= (:pages-index (:data file))
             (:pages-index (:data file''))))))

;; A persisted main reorder remains valid before and after a later copy edit.
(t/deftest main-reorder-keeps-copies-valid-for-later-edits
  (let [file     (setup-file)
        page     (thf/current-page file)
        row-main (ths/get-shape file :row-main)
        icon-1   (ths/get-shape file :icon-1)
        reorder  (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (:id row-main) 2 #{(:id icon-1)})
        ;; Apply without validation to model persisted intermediate state.
        file'    (thf/apply-changes file reorder :validate? false)]
    (thf/validate-file! file')
    (let [file''  (tho/delete-shape file' :copy-2)
          copy-2' (ths/get-shape file'' :copy-2)]
      (t/is (some? copy-2'))
      (t/is (true? (:hidden copy-2'))))))

;; Copy ordering rejects local reorders unless component sync owns the operation.
(t/deftest reorder-children-change-cannot-alter-copies
  (let [file      (setup-file)
        page      (thf/current-page file)
        row-copy  (ths/get-shape file :row-copy)
        scrambled (vec (reverse (:shapes row-copy)))
        change    {:type :reorder-children
                   :page-id (:id page)
                   :parent-id (:id row-copy)
                   :shapes scrambled}
        get-order (fn [data]
                    (get-in data [:pages-index (:id page)
                                  :objects (:id row-copy) :shapes]))]
    ;; without allow-altering-copies the reorder is rejected
    (t/is (= (:shapes row-copy)
             (get-order (cpc/process-changes (:data file) [change] false))))
    ;; the sync engine can still restructure copies explicitly
    (t/is (= scrambled
             (get-order (cpc/process-changes
                         (:data file)
                         [(assoc change :allow-altering-copies true)]
                         false))))))

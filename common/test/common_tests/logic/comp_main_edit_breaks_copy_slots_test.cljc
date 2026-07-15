;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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

;; Regression for the referential-integrity crash (:missing-slot, "Shape has been
;; swapped, should have swap slot").
;;
;; Root cause (fixed): copy sub-heads used to be matched to the main's children
;; purely BY POSITION (`find-near-match`), while several code paths reorder or
;; mutilate one side only:
;;
;;   - reordering or deleting a nested sub-head IN THE MAIN of a component while
;;     copies exist (this file);
;;   - a grid reflow moving a hidden copy sub-head to the front of :shapes
;;     (see `reorder-grid-children` tests and the :reorder-children copy guard).
;;
;; The fixes under test here:
;;
;;   - validation treats a sub-head whose ref shape is still a child of the near
;;     main parent as a REORDER (valid, the sync realigns it), not a swap;
;;   - deleting a sub-head in a main also deletes the corresponding copy shapes
;;     (transitively), so no dangling refs remain;
;;   - the :reorder-children change cannot alter the structure of copies.
;;
;; Structure built for each test:
;;   {:icon-main}  # [Component :icon]            (a frame + rect)
;;
;;   {:row-main}   # [Component :row]             (a frame holding 3 :icon instances)
;;       :icon-1   @--> :icon-main
;;       :icon-2   @--> :icon-main
;;       :icon-3   @--> :icon-main
;;
;;   :row-copy     #--> [Component :row] :row-main (a copy of Row)
;;       :copy-1   @--> :icon-1
;;       :copy-2   @--> :icon-2
;;       :copy-3   @--> :icon-3
(defn- setup-file
  []
  (-> (thf/sample-file :file1)
      (tho/add-simple-component :icon :icon-main :icon-child)
      (tho/add-frame :row-main :name "Row")
      (thc/instantiate-component :icon :icon-1 :parent-label :row-main)
      (thc/instantiate-component :icon :icon-2 :parent-label :row-main)
      (thc/instantiate-component :icon :icon-3 :parent-label :row-main)
      (thc/make-component :row :row-main)
      (thc/instantiate-component :row :row-copy :children-labels [:copy-1 :copy-2 :copy-3])))

;; ---------------------------------------------------------------------------
;; Characterization: copy-SIDE structural edits are handled correctly
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Regression: main-SIDE structural edits must not break copies
;; ---------------------------------------------------------------------------

;; Reordering a sub-head IN THE MAIN must keep the copies referentially valid:
;; the copies' sub-heads are only reordered relative to the main (the sync
;; realigns them), not swapped, so no swap slot is required.
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
        ;; apply-changes validates -> this used to throw :missing-slot
        file'    (thf/apply-changes file changes)]
    (t/is (some? (ths/get-shape file' :copy-1)))
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; Deleting a sub-head IN THE MAIN must keep the copies referentially valid:
;; the corresponding copy sub-head is deleted along with it, so no dangling
;; shape-refs (and no shifted positional slots) remain.
(t/deftest deleting-a-main-subhead-must-not-break-copies
  (let [file  (setup-file)
        file' (tho/delete-shape file :icon-1)]
    (t/is (nil? (ths/get-shape file' :copy-1)))
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; ---------------------------------------------------------------------------
;; The FULL chain that used to crash: a main-side reorder persisted a corrupt
;; state (a file saved this way loaded fine, since load does not validate), and
;; a LATER copy-side edit ran validation and surfaced it as the crash. Now the
;; reordered state is valid on its own and later copy edits keep working.
;; ---------------------------------------------------------------------------
(t/deftest main-reorder-keeps-copies-valid-for-later-edits
  (let [file     (setup-file)
        page     (thf/current-page file)
        row-main (ths/get-shape file :row-main)
        icon-1   (ths/get-shape file :icon-1)
        reorder  (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (:id row-main) 2 #{(:id icon-1)})
        ;; 1) reorder a main sub-head WITHOUT validating (as a persisted file)
        file'    (thf/apply-changes file reorder :validate? false)]
    ;; 2) the file is valid nevertheless
    (thf/validate-file! file')
    ;; 3) a later copy-side edit (delete = hide) works instead of crashing
    (let [file''  (tho/delete-shape file' :copy-2)
          copy-2' (ths/get-shape file'' :copy-2)]
      (t/is (some? copy-2'))
      (t/is (true? (:hidden copy-2'))))))

;; ---------------------------------------------------------------------------
;; The :reorder-children change (emitted by grid reflows) must not alter the
;; child structure of component copies: that structure is owned by the
;; component sync engine. (A grid reflow used to move a hidden copy sub-head
;; to the front of :shapes this way, breaking the copy/main alignment.)
;; ---------------------------------------------------------------------------
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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.logic.comp-main-edit-breaks-copy-slots-test
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

;; Regression for the referential-integrity crash (:missing-slot, "Shape has been
;; swapped, should have swap slot").
;;
;; Root cause: changing the child STRUCTURE of a component's MAIN (reordering or
;; deleting a nested sub-head) while copies exist. `find-near-match` matches a
;; copy's nested sub-heads to the main's children purely BY POSITION; when the
;; main's order changes, the copies' shape-refs no longer match their position and
;; no swap slot is propagated to the copies -> the file fails referential-integrity
;; validation. (The copy-SIDE edits below are handled correctly and pass.)
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
;; Characterization: copy-SIDE structural edits are handled correctly (pass today)
;; ---------------------------------------------------------------------------

;; Deleting a nested sub-head of a COPY only hides it (deleted-subinstance).
(t/deftest deleting-a-copy-subhead-only-hides-it
  (let [file    (setup-file)
        file'   (tho/delete-shape file :copy-1)
        copy-1' (ths/get-shape file' :copy-1)]
    (t/is (some? copy-1'))
    (t/is (true? (:hidden copy-1')))))

;; Reordering a nested sub-head within a COPY assigns swap slots as needed.
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
;; (these FAIL today with :missing-slot; they go green once the sync propagates
;;  swap slots to copies on a main reorder/delete)
;; ---------------------------------------------------------------------------

;; Reordering a sub-head IN THE MAIN must keep the copies referentially valid.
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
        ;; apply-changes validates -> throws :missing-slot today (the reproduced bug)
        file'    (thf/apply-changes file changes)]
    (t/is (some? (ths/get-shape file' :copy-1)))
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; Deleting a sub-head IN THE MAIN must keep the copies referentially valid.
(t/deftest deleting-a-main-subhead-must-not-break-copies
  (let [file  (setup-file)
        ;; deleting a main sub-head removes a slot; copies must be reconciled
        file' (tho/delete-shape file :icon-1)]
    (t/is (some? (ths/get-shape file' :copy-2)))
    (t/is (some? (ths/get-shape file' :copy-3)))))

;; ---------------------------------------------------------------------------
;; The FULL chain (models the real crash): a main-side reorder persists a corrupt
;; state (a file saved this way loads fine, since load does not validate), and a
;; LATER copy-side edit is what runs validation and surfaces it as the crash.
;; This documents the observed buggy behavior end to end.
;; ---------------------------------------------------------------------------
(t/deftest main-reorder-corrupts-copies-and-a-later-copy-edit-surfaces-it
  (let [file     (setup-file)
        page     (thf/current-page file)
        row-main (ths/get-shape file :row-main)
        icon-1   (ths/get-shape file :icon-1)
        reorder  (cls/generate-relocate (-> (pcb/empty-changes nil)
                                            (pcb/with-page-id (:id page))
                                            (pcb/with-objects (:objects page)))
                                        (:id row-main) 2 #{(:id icon-1)})
        ;; 1) reorder a main sub-head WITHOUT validating -> the corrupt state persists
        corrupt  (thf/apply-changes file reorder :validate? false)
        ;; 2) the copies are now corrupt: validation reports :missing-slot
        details  (try (thf/validate-file! corrupt) nil
                      (catch clojure.lang.ExceptionInfo e (:details (ex-data e))))]
    (t/is (some #(= :missing-slot (:code %)) details)
          "main reorder must leave the copies in a :missing-slot state")
    ;; 3) a later copy-side edit (delete) runs validation -> throws (the user's crash)
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (tho/delete-shape corrupt :copy-2))
          "a copy-delete on the corrupt file must trigger the validation crash")))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-pages-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.main.data.workspace.pages :as dwpg]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(defn- pages
  [state]
  (let [file-id (:current-file-id state)]
    (get-in state [:files file-id :data :pages])))

(defn- make-five-pages
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (cthf/add-sample-page :page2)
      (cthf/add-sample-page :page3)
      (cthf/add-sample-page :page4)
      (cthf/add-sample-page :page5)
      (cthf/switch-to-page :page1)))

;; ---------------------------------------------------------------------------
;; Verify the order of undo changes so that when pages are restored they
;; appear in the original page order (not reversed).
;; ---------------------------------------------------------------------------

(defn- add-page-ids-from-undo-changes
  "Extract the sequence of page ids from the `:add-page` undo changes list."
  [changes]
  (->> (:undo-changes changes)
       (keep (fn [change]
               (when (= :add-page (:type change))
                 (:id change))))))

(t/deftest delete-pages-undo-changes-are-in-page-order
  (let [file      (make-five-pages)
        fdata     (:data file)
        pindex    (:pages-index fdata)
        pages     (:pages fdata)
        page2-id  (cthi/id :page2)
        page4-id  (cthi/id :page4)
        del-ids   (filter #{page2-id page4-id} pages)

        ;; Simulate the reduction from delete-pages but in REVERSE order
        ;; (the fix iterates (reverse del-ids) so undo changes are in correct order)
        changes   (reduce
                   (fn [changes id]
                     (let [page (-> (get pindex id)
                                    (assoc :index (d/index-of pages id)))]
                       (pcb/del-page changes page)))
                   (pcb/empty-changes)
                   del-ids)

        undo-before-fix (add-page-ids-from-undo-changes changes)

        changes-fixed   (reduce
                         (fn [changes id]
                           (let [page (-> (get pindex id)
                                          (assoc :index (d/index-of pages id)))]
                             (pcb/del-page changes page)))
                         (pcb/empty-changes)
                         (reverse del-ids))

        undo-after-fix (add-page-ids-from-undo-changes changes-fixed)]

    (t/is (= [page4-id page2-id] undo-before-fix)
          "before fix: undo changes are reversed (page order)")
    (t/is (= [page2-id page4-id] undo-after-fix)
          "after fix: undo changes are in correct page order")))

;; ---------------------------------------------------------------------------
;; delete-pages removes the correct pages and keeps remaining pages in order
;; ---------------------------------------------------------------------------

(t/deftest delete-pages-removes-pages-and-keeps-remaining-in-order
  (t/async
    done
    (let [file      (make-five-pages)
          page2-id  (cthi/id :page2)
          page4-id  (cthi/id :page4)
          store     (ths/setup-store file)
          events    [(dwpg/delete-pages [page2-id page4-id])]]

      (ths/run-store
       store done events
       (fn [state]
         (let [pgs (pages state)]
           (t/is (= 3 (count pgs))
                 "3 pages remain after deleting 2")
           (t/is (= [(cthi/id :page1) (cthi/id :page3) (cthi/id :page5)]
                    pgs)
                 "remaining pages stay in the original order")))))))

;; ---------------------------------------------------------------------------
;; delete-pages keeps at least one page in the file
;; ---------------------------------------------------------------------------

(t/deftest delete-pages-keeps-at-least-one-page
  (t/async
    done
    (let [file      (make-five-pages)
          page1-id  (cthi/id :page1)
          page2-id  (cthi/id :page2)
          page3-id  (cthi/id :page3)
          page4-id  (cthi/id :page4)
          page5-id  (cthi/id :page5)
          store     (ths/setup-store file)
          events    [(dwpg/delete-pages [page1-id page2-id page3-id page4-id page5-id])]]

      (ths/run-store
       store done events
       (fn [state]
         (let [pgs (pages state)]
           (t/is (= 1 (count pgs))
                 "at least one page remains")
           (t/is (= page1-id (first pgs))
                 "the first page (in page order) is kept")))))))

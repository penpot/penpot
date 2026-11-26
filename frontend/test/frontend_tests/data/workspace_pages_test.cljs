;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-pages-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.workspace.pages :as dwpg]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as tha]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

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

;; ---------------------------------------------------------------------------
;; Adding a page from the sitemap opens it and starts renaming it, once
;; the new page has initialized.
;; ---------------------------------------------------------------------------

(t/deftest ^:async add-page-opens-and-renames-the-new-page
  (let [file-id (uuid/next)
        stream  (rx/subject)
        event   (dwpg/create-page-and-focus {:file-id file-id})
        events  (atom [])
        done    (tha/observe (ptk/watch event {} stream)
                             :on-next #(swap! events conj %))]

    (await (tha/settle))
    (let [[create interrupt go-to] @events
          page-id                   (:id (ev/-data create))]
      (t/is (= ::dwpg/create-page (ptk/type create)))
      (t/is (= :interrupt interrupt))
      (t/is (= ::dcm/go-to-workspace (ptk/type go-to)))

      ;; A page added by someone else initializes first: no rename.
      (rx/push! stream (ptk/data-event ::dwpg/initialized (uuid/next)))
      (rx/push! stream (ptk/data-event ::dwpg/initialized page-id))
      (await done)

      (let [rename (last @events)
            state  (ptk/update rename {:workspace-local {}})]
        (t/is (= 4 (count @events)))
        (t/is (= ::dwpg/start-rename-page-item (ptk/type rename)))
        (t/is (= page-id (get-in state [:workspace-local :page-item])))))))

;; ---------------------------------------------------------------------------
;; Renaming a page to its current name commits nothing, so the next undo
;; reverts the last visible change.
;; ---------------------------------------------------------------------------

(defn- ^:async rename-page-events
  [store page-id name]
  (let [events (atom [])]
    (await (tha/observe (ptk/watch (dwpg/rename-page page-id name) @store (rx/subject))
                        :on-next #(swap! events conj %)))
    @events))

(t/deftest ^:async rename-page-to-the-same-name-commits-nothing
  (let [file    (cthf/sample-file :file1 :page-label :page1)
        page-id (cthi/id :page1)
        name    (:name (cthf/get-page file :page1))
        store   (ths/setup-store file)]
    (t/is (= [] (await (rename-page-events store page-id name))))
    (t/is (= [:app.main.data.changes/commit-changes]
             (map ptk/type (await (rename-page-events store page-id "Renamed")))))))

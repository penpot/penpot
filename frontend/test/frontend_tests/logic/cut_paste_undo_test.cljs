;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.cut-paste-undo-test
  "Undoing shapes moved with cut and paste."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.clipboard :as dwcp]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.streams :as ms]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(defonce ^:private copy-selected dwcp/copy-selected)

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (cthi/reset-idmap!)
             (rx/push! ms/mouse-position (gpt/point 300 300))
             ;; Writing the system clipboard needs a browser
             (set! dwcp/copy-selected #(ptk/data-event ::copied)))
   :after (fn []
            (rx/push! ms/mouse-position nil)
            (set! dwcp/copy-selected copy-selected))})

(defn- setup-file
  "A file whose first page holds :rect1 and whose second page is empty."
  []
  (-> (cthf/sample-file :file1 :page-label :page1)
      (cthf/add-sample-page :page2)
      (vary-meta assoc :current-page-id (cthi/id :page1))
      (ctho/add-rect :rect1 :x 100 :y 200 :width 50 :height 50)))

(defn- copied-rect
  "The clipboard data of copying :rect1."
  [file]
  (let [page (cthf/current-page file)]
    (thp/simulate-copy-shape #{(cthi/id :rect1)} (:objects page) {(:id file) file} page file #{} 67)))

(defn- page-rects
  [state page-label]
  (let [file (ths/get-file-from-state state)]
    (->> (get-in file [:data :pages-index (cthi/id page-label) :objects])
         (vals)
         (filter #(= :rect (:type %))))))

(defn- undo-steps
  [store]
  (count (get-in @store [:workspace-undo :items])))

(defn- ^:async step!
  "Emits `event` and waits until it adds an undo step."
  [store event]
  (let [before (undo-steps store)]
    (ptk/emit! store event)
    (await (async/wait-for #(< before (undo-steps store)) "the undo step of the event"))))

(defn- ^:async paste!
  "Pastes `pdata` on the second page and waits for the pasted shape. A paste
  that joins its cut adds no undo step of its own."
  [store pdata]
  (let [before (count (page-rects @store :page2))]
    (ptk/emit! store (dw/paste-shapes pdata))
    (await (async/wait-for #(< before (count (page-rects @store :page2))) "the pasted shape"))
    (await (async/settle))))

(defn- ^:async with-store
  "Runs `f` with a store of `file` whose edit watchers record the undo
  history, then disposes it."
  [file f]
  (let [store (ths/setup-store file)]
    (ptk/emit! store (dw/initialize-edit-watchers))
    (try
      (await (f store))
      (finally
        (ptk/emit! store (dw/finalize-edit-watchers))
        (rx/dispose! store)))))

(defn- ^:async cut-to-page2!
  "Cuts :rect1 on the first page and moves to the second one."
  [store]
  (ptk/emit! store (dws/select-shape (cthi/id :rect1)))
  (await (step! store (dw/cut-selected)))
  (ptk/emit! store #(assoc % :current-page-id (cthi/id :page2))))

(defn- ^:async undo!
  [store]
  (ptk/emit! store dwu/undo)
  (await (async/settle)))

;; Scenario: a shape is cut on one page and pasted on another. Proves: one
;; undo puts it back where it was, with no pasted copy left behind.
(t/deftest ^:async one-undo-reverts-a-cut-and-its-paste
  (let [file  (setup-file)
        pdata (copied-rect file)]
    (await
     (with-store
       file
       (^:async fn [store]
         (await (cut-to-page2! store))
         (await (paste! store pdata))
         (await (undo! store))
         (t/is (empty? (page-rects @store :page2)) "the pasted copy is gone")
         (t/is (= [(cthi/id :rect1)] (map :id (page-rects @store :page1)))
               "the cut shape is back on its page")
         (t/is (= 100 (:x (first (page-rects @store :page1))))))))))

;; Scenario: the cut shapes are pasted twice. Proves: only the first paste
;; joins the cut, so one undo reverts the second paste alone.
(t/deftest ^:async a-second-paste-of-a-cut-undoes-on-its-own
  (let [file  (setup-file)
        pdata (copied-rect file)]
    (await
     (with-store
       file
       (^:async fn [store]
         (await (cut-to-page2! store))
         (await (paste! store pdata))
         (await (paste! store pdata))
         (await (undo! store))
         (t/is (= 1 (count (page-rects @store :page2))) "the first paste stays")
         (t/is (empty? (page-rects @store :page1)) "the cut stays"))))))

;; Scenario: something else is done between the cut and the paste. Proves:
;; the paste is then its own undo step.
(t/deftest ^:async a-paste-after-another-change-undoes-on-its-own
  (let [file  (setup-file)
        pdata (copied-rect file)]
    (await
     (with-store
       file
       (^:async fn [store]
         (await (cut-to-page2! store))
         (await (step! store (dch/commit-changes
                              {:redo-changes [{:type :mod-page :id (cthi/id :page1) :name "Renamed"}]
                               :undo-changes [{:type :mod-page :id (cthi/id :page1) :name "Page 1"}]})))
         (await (paste! store pdata))
         (await (undo! store))
         (t/is (empty? (page-rects @store :page2)) "the paste is undone")
         (t/is (empty? (page-rects @store :page1)) "the cut stays"))))))

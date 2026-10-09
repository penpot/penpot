;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.changes-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.ids-map :as thi]
   [app.main.data.changes :as dch]
   [app.main.store :as st]
   [app.plugins.reflow :as pwrf]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

;; The component watcher schedules thumbnail renders that reach `window`,
;; absent headless (see `ftm/install-thumbnail-noop!`).
(t/use-fixtures :each
  {:before (fn []
             (thi/reset-idmap!)
             (ftm/install-thumbnail-noop!))
   :after  ftm/restore-thumbnail!})

(t/deftest change-without-page-leaves-the-pages-alone
  (t/async done
    (ftm/install! (setup/simple-component-with-copy))
    (let [file    (ftm/current-file)
          pages   (set (keys (get-in file [:data :pages-index])))
          changes (-> (pcb/empty-changes)
                      (pcb/with-library-data (:data file))
                      (pcb/update-component (thi/id :component1)
                                            #(assoc % :annotation "Note")))]
      (st/emit! (dch/commit-changes {:redo-changes (:redo-changes changes)
                                     :undo-changes (:undo-changes changes)}))
      (-> (pwrf/wait-for-layout-update nil 2000)
          (.then (fn []
                   (let [data (:data (ftm/current-file))]
                     (t/is (= "Note" (get-in data [:components (thi/id :component1) :annotation])))
                     (t/is (= pages (set (keys (:pages-index data))))))))
          (.catch #(t/is false (str "the commit did not settle: " %)))
          (.finally done)))))

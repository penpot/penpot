;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-reflow-test
  "Tests the reflow marks the `:layout/update` pipeline feeds to
  `app.main.data.workspace.reflow`, which is what plugin waits observe."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shape-layout :as dwsl]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each {:before wrf/reset-pending!
                       :after wrf/reset-pending!})

;; Starts the `:layout/update` pipeline on a throwaway store; its empty state
;; resolves every buffered update to no shapes and applies no modifiers.
(defn- start-pipeline!
  []
  (doto (ptk/store {:state {} :on-error #(js/console.error %)})
    (ptk/emit! (dwsl/initialize-shape-layout))))

(defn- stop-pipeline!
  [store]
  (ptk/emit! store (dwsl/finalize-shape-layout)))

(t/deftest root-only-layout-update-is-not-pending-work
  ;; The root lays out nothing, so an update with only its id holds no wait.
  (t/async done
    (let [store (start-pipeline!)]
      (ptk/emit! store (ptk/data-event :layout/update {:ids [uuid/zero]}))
      (-> (wrf/wait-for-layout-update nil 20)
          (.then #(t/is true "resolved with no pending work"))
          (.catch #(t/is false "a root-only update was marked as pending work"))
          (.then (fn []
                   (stop-pipeline! store)
                   (done)))))))

(t/deftest layout-update-is-pending-until-the-buffer-flushes
  ;; A shape id is marked on arrival and drained when the update is processed.
  (t/async done
    (let [store (start-pipeline!)]
      (ptk/emit! store (ptk/data-event :layout/update {:ids [(uuid/next) uuid/zero]}))
      (-> (wrf/wait-for-layout-update nil 20)
          (.then #(t/is false "resolved while the update was still buffered"))
          (.catch #(t/is true "stayed pending until the flush"))
          (.then #(wrf/wait-for-layout-update nil 5000))
          (.then #(t/is true "resolved once the update was processed"))
          (.catch #(t/is false "the pipeline never drained its mark"))
          (.then (fn []
                   (stop-pipeline! store)
                   (done)))))))

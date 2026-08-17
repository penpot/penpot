;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-reflow-test
  "Tests the reflow tasks the layout and text pipelines feed to
  `app.main.data.workspace.reflow`, which is what plugin waits observe."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.texts :as dwtxt]
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

(defn- start-text-pipeline!
  []
  (doto (ptk/store {:state {} :on-error #(js/console.error %)})
    (ptk/emit! (dwtxt/initialize-text-reflow))))

(defn- stop-text-pipeline!
  [store]
  (ptk/emit! store (dwtxt/finalize-text-reflow)))

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

(t/deftest stale-task-cannot-finish-current-work
  ;; Delayed callbacks from a finalized workspace carry the old generation and
  ;; must not drain a new workspace task for the same shape id.
  (t/async done
    (let [id           (uuid/next)
          stale-task   (wrf/start! :text-measure [id])
          _             (wrf/reset-pending!)
          current-task (wrf/start! :text-measure [id])]
      (wrf/finish! stale-task)
      (-> (wrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "a stale completion drained current work"))
          (.catch #(t/is true "current work stayed pending"))
          (.then (fn []
                   (wrf/finish! current-task)
                   (wrf/wait-for-layout-update [id] 100)))
          (.then #(t/is true "the exact current task drained normally"))
          (.catch #(t/is false "the current task did not drain"))
          (.then (fn [] (done)))))))

(t/deftest pending-promise-finishes-at-the-operation-boundary
  ;; Imperative render work is pending from before its thunk starts until the
  ;; exact promise returned by that thunk settles; no timer is involved.
  (t/async done
    (let [id       (uuid/next)
          resolve* (atom nil)
          started? (atom false)]
      (wrf/run-pending!
       :text-measure
       [id]
       (fn []
         (reset! started? true)
         (js/Promise. (fn [resolve _] (reset! resolve* resolve)))))
      (-> (wrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "resolved while the render operation was pending"))
          (.catch #(t/is @started? "the task was opened before running the operation"))
          (.then (fn []
                   (@resolve*)
                   (wrf/wait-for-layout-update [id] 100)))
          (.then #(t/is true "resolved as soon as the render operation settled"))
          (.catch #(t/is false "the settled render operation stayed pending"))
          (.then (fn [] (done)))))))

(t/deftest pending-promise-finishes-after-a-synchronous-error
  (let [id (uuid/next)]
    (try
      (wrf/run-pending! :text-measure [id] #(throw (js/Error. "boom")))
      (catch :default _))
    (t/async done
      (-> (wrf/wait-for-layout-update [id] 100)
          (.then #(t/is true "a synchronous failure drained its exact task"))
          (.catch #(t/is false "a synchronous failure leaked pending work"))
          (.then (fn [] (done)))))))

(t/deftest deleted-shapes-cancel-only-their-work
  (t/async done
    (let [id-a   (uuid/next)
          id-b   (uuid/next)
          task-a (wrf/start! :text-bridge [id-a])
          task-b (wrf/start! :text-bridge [id-b])]
      (wrf/cancel-shapes! [id-a])
      (-> (wrf/wait-for-layout-update [id-a] 100)
          (.then #(t/is true "deleted shape work was cancelled"))
          (.catch #(t/is false "deleted shape work stayed pending"))
          (.then #(wrf/wait-for-layout-update [id-b] 20))
          (.then #(t/is false "cancelling one shape drained its sibling"))
          (.catch #(t/is true "sibling work stayed pending"))
          (.then (fn []
                   (wrf/finish! task-a)
                   (wrf/finish! task-b)
                   (done)))))))

(t/deftest text-bridge-waits-for-each-shape
  ;; Starting measurement for one id must not release sibling bridge tasks.
  (t/async done
    (let [store (start-text-pipeline!)
          id-a  (uuid/next)
          id-b  (uuid/next)]
      (ptk/emit! store (ptk/data-event :text/reflow {:ids [id-a id-b]}))
      (let [task-a (wrf/start! :text-measure [id-a])]
        (wrf/finish! task-a))
      (-> (wrf/wait-for-layout-update [id-b] 20)
          (.then #(t/is false "the first text released its sibling bridge"))
          (.catch #(t/is true "the sibling bridge stayed pending"))
          (.then (fn []
                   (let [task-b (wrf/start! :text-measure [id-b])]
                     (wrf/finish! task-b))
                   (wrf/wait-for-layout-update [id-b] 100)))
          (.then #(t/is true "the sibling drained after its own measurement"))
          (.catch #(t/is false "the sibling never drained"))
          (.then (fn []
                   (stop-text-pipeline! store)
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

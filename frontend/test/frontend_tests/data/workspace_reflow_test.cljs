;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-reflow-test
  "Tests the reflow tasks the layout and text pipelines feed to
  `app.main.data.workspace.reflow`, which is what plugin waits observe. The
  promise view of the settle signal lives in `app.plugins.reflow`; these tests
  use it because it is the wait the plugin API ships."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.fonts :as fonts]
   [app.plugins.reflow :as pwrf]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [app.util.globals :as globals]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
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
      (-> (pwrf/wait-for-layout-update nil 20)
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
      (-> (pwrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "a stale completion drained current work"))
          (.catch #(t/is true "current work stayed pending"))
          (.then (fn []
                   (wrf/finish! current-task)
                   (pwrf/wait-for-layout-update [id] 100)))
          (.then #(t/is true "the exact current task drained normally"))
          (.catch #(t/is false "the current task did not drain"))
          (.then (fn [] (done)))))))

(t/deftest reinstalling-the-pending-scan-resets-work-and-keeps-tracking
  ;; Reinstall the pending scan with the latest reducer.
  (t/async done
    (let [id       (uuid/next)
          stale    (wrf/start! :text-measure [id])
          current* (atom nil)]
      (#'wrf/install-pending-subscription!)
      (-> (pwrf/wait-for-layout-update [id] 100)
          (.then #(t/is true "reinstalling the scan reset its previous generation"))
          (.catch #(t/is false "the replaced scan kept stale work pending"))
          (.then
           (fn []
             (reset! current* (wrf/start! :text-measure [id]))
             (pwrf/wait-for-layout-update [id] 20)))
          (.then #(t/is false "the replacement scan did not track new work"))
          (.catch #(t/is true "the replacement scan tracked new work"))
          (.then
           (fn []
             (wrf/finish! stale)
             (wrf/finish! @current*)
             (pwrf/wait-for-layout-update [id] 100)))
          (.then #(t/is true "the replacement scan drained its exact task"))
          (.catch #(t/is false "the replacement scan did not drain"))
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
      (-> (pwrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "resolved while the render operation was pending"))
          (.catch #(t/is @started? "the task was opened before running the operation"))
          (.then (fn []
                   (@resolve*)
                   (pwrf/wait-for-layout-update [id] 100)))
          (.then #(t/is true "resolved as soon as the render operation settled"))
          (.catch #(t/is false "the settled render operation stayed pending"))
          (.then (fn [] (done)))))))

(t/deftest pending-promise-finishes-after-a-synchronous-error
  (let [id (uuid/next)]
    (try
      (wrf/run-pending! :text-measure [id] #(throw (js/Error. "boom")))
      (catch :default _))
    (t/async done
      (-> (pwrf/wait-for-layout-update [id] 100)
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
      (-> (pwrf/wait-for-layout-update [id-a] 100)
          (.then #(t/is true "deleted shape work was cancelled"))
          (.catch #(t/is false "deleted shape work stayed pending"))
          (.then #(pwrf/wait-for-layout-update [id-b] 20))
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
      (-> (pwrf/wait-for-layout-update [id-b] 20)
          (.then #(t/is false "the first text released its sibling bridge"))
          (.catch #(t/is true "the sibling bridge stayed pending"))
          (.then (fn []
                   (let [task-b (wrf/start! :text-measure [id-b])]
                     (wrf/finish! task-b))
                   (pwrf/wait-for-layout-update [id-b] 100)))
          (.then #(t/is true "the sibling drained after its own measurement"))
          (.catch #(t/is false "the sibling never drained"))
          (.then (fn []
                   (stop-text-pipeline! store)
                   (done)))))))

(t/deftest text-bridge-observes-out-of-order-work
  ;; Start all bridges before matching work can finish.
  (t/async done
    (let [store (start-text-pipeline!)
          id-a  (uuid/next)
          id-b  (uuid/next)]
      (ptk/emit! store (ptk/data-event :text/reflow {:ids [id-a id-b]}))
      (let [task-b (wrf/start! :text-measure [id-b])]
        (wrf/finish! task-b))
      (let [task-a (wrf/start! :text-measure [id-a])]
        (wrf/finish! task-a))
      (-> (pwrf/wait-for-layout-update [id-a id-b] 100)
          (.then #(t/is true "both out-of-order bridges observed their work"))
          (.catch #(t/is false "a bridge missed work that started out of order"))
          (.then (fn []
                   (stop-text-pipeline! store)
                   (done)))))))

(t/deftest text-bridge-does-not-consume-preexisting-work
  ;; Ignore matching work that started before the bridge.
  (t/async done
    (let [store      (start-text-pipeline!)
          id         (uuid/next)
          prior-task (wrf/start! :text-measure [id])]
      (ptk/emit! store (ptk/data-event :text/reflow {:ids [id]}))
      (wrf/finish! prior-task)
      (-> (pwrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "preexisting work released the new bridge"))
          (.catch #(t/is true "the new bridge remained pending"))
          (.then (fn []
                   (let [current-task (wrf/start! :text-measure [id])]
                     (wrf/finish! current-task))
                   (pwrf/wait-for-layout-update [id] 100)))
          (.then #(t/is true "work started after the bridge drained it"))
          (.catch #(t/is false "the causal measurement did not drain the bridge"))
          (.then (fn []
                   (stop-text-pipeline! store)
                   (done)))))))

(t/deftest cancelling-a-bridge-does-not-block-later-reflow-events
  (t/async done
    (let [store (start-text-pipeline!)
          id-a  (uuid/next)
          id-b  (uuid/next)]
      (ptk/emit! store (ptk/data-event :text/reflow {:ids [id-a]}))
      (wrf/cancel-shapes! [id-a])
      (ptk/emit! store (ptk/data-event :text/reflow {:ids [id-b]}))
      (-> (pwrf/wait-for-layout-update [id-b] 20)
          (.then #(t/is false "the later bridge was not opened"))
          (.catch #(t/is true "the later bridge stayed pending"))
          (.then (fn []
                   (let [task-b (wrf/start! :text-measure [id-b])]
                     (wrf/finish! task-b))
                   (pwrf/wait-for-layout-update [id-b] 100)))
          (.then #(t/is true "the later bridge drained after its own work"))
          (.catch #(t/is false "the cancelled bridge blocked the pipeline"))
          (.then (fn []
                   (stop-text-pipeline! store)
                   (done)))))))

(t/deftest finalizing-a-page-cancels-its-text-bridges
  (t/async done
    (let [store (start-text-pipeline!)
          id    (uuid/next)]
      (ptk/emit! store (ptk/data-event :text/reflow {:ids [id]}))
      (ptk/emit! store (ptk/data-event :app.main.data.workspace.pages/finalize-page))
      (-> (pwrf/wait-for-layout-update [id] 100)
          (.then #(t/is true "page teardown drained the unmeasured text bridge"))
          (.catch #(t/is false "page teardown left text work pending"))
          (.then (fn []
                   (stop-text-pipeline! store)
                   (done)))))))

(t/deftest failed-wasm-font-storage-falls-back-and-drains
  (t/async done
    (let [id       (uuid/next)
          font-key {:font-id "gfont-does-not-load"
                    :weight 400
                    :style 0
                    :emoji? false}
          stream   (rx/subject)
          events   (atom [])]
      (->> (#'dwtxt/await-font-faces stream #{font-key} [id])
           (rx/subs! #(swap! events conj %)))
      (#'wasm.fonts/report-font-storage-failed! font-key)
      (-> (pwrf/wait-for-layout-update [id] 100)
          (.then (fn []
                   (t/is (= 1 (count @events))
                         "font failure dispatches one fallback resize")
                   (t/is (wasm.fonts/font-ready? font-key)
                         "the resize gate accepts the failed face's fallback")
                   (done)))
          (.catch (fn [_]
                    (t/is false "font failure leaked pending work")
                    (done)))))))

(t/deftest failed-dom-font-load-falls-back-and-drains
  (t/async done
    (let [id      (uuid/next)
          font-id "gfont-layout-failure-test"]
      (swap! fonts/fontsdb assoc font-id
             {:id font-id
              :backend :google
              :family "Layout Failure Test"
              :variants [{:id "regular"}]})
      (swap! fonts/loaded disj font-id)
      (swap! fonts/loading dissoc font-id)
      (mock/with-mocks
        {globals/browser? (constantly true)
         http/send!       (fn [_] (rx/throw (js/Error. "font fetch failed")))}
        (fn [done']
          (wrf/run-pending! :font [id] #(fonts/ensure-loaded! font-id))
          (-> (pwrf/wait-for-layout-update [id] 500)
              (.then (fn []
                       (t/is (not (contains? @fonts/loading font-id))
                             "a failed load must not remain cached as loading")))
              (.catch #(t/is false "failed DOM font load leaked pending work"))
              (.then (fn []
                       (swap! fonts/fontsdb dissoc font-id)
                       (swap! fonts/loaded disj font-id)
                       (swap! fonts/loading dissoc font-id)
                       (done')))))
        done))))

(t/deftest failed-google-font-css-does-not-abort-shared-consumers
  (t/async done
    (let [font-id "gfont-optional-css-test"
          values  (atom [])
          cleanup #(swap! fonts/fontsdb dissoc font-id)]
      (swap! fonts/fontsdb assoc font-id
             {:id font-id
              :backend :google
              :family "Optional CSS Test"
              :variants [{:id "regular"}]})
      (mock/with-mocks
        {http/send! (fn [_] (rx/throw (js/Error. "font css fetch failed")))}
        (fn [done']
          (->> (fonts/fetch-font-css {:font-id font-id})
               (rx/subs!
                #(swap! values conj %)
                (fn [_]
                  (cleanup)
                  (t/is false "an optional font CSS failure escaped the shared helper")
                  (done'))
                (fn []
                  (cleanup)
                  (t/is (empty? @values)
                        "a failed optional font contributes no CSS")
                  (done')))))
        done))))

(t/deftest deduplicated-wasm-font-failure-settles-every-face
  (t/async done
    (let [font-url "https://example.test/shared-font.ttf"
          regular  {:font-id "gfont-shared-regular"
                    :weight 400
                    :style 0
                    :emoji? false}
          bold     {:font-id "gfont-shared-bold"
                    :weight 700
                    :style 0
                    :emoji? false}]
      (mock/with-mocks
        {http/send! (fn [_] (rx/throw (js/Error. "shared fetch failed")))}
        (fn [done']
          (let [request (#'wasm.fonts/fetch-font regular font-url false false)
                duplicate (#'wasm.fonts/fetch-font bold font-url false false)]
            (t/is (some? request) "the first face owns the shared fetch")
            (t/is (nil? duplicate) "the second face reuses the shared fetch")
            (->> ((:callback request))
                 (rx/subs!
                  (fn [_])
                  (fn [_]
                    (t/is false "the shared fetch failure escaped its fallback")
                    (done'))
                  (fn []
                    (t/is (wasm.fonts/font-ready? regular)
                          "the first face settled through fallback")
                    (t/is (wasm.fonts/font-ready? bold)
                          "the deduplicated face settled through fallback")
                    (done'))))))
        done))))

(t/deftest missing-wasm-font-url-settles-without-entering-fetch-map
  (t/async done
    (let [font-data {:font-id "gfont-missing-url"
                     :weight 400
                     :style 0
                     :emoji? false}]
      (t/is (nil? (#'wasm.fonts/fetch-font font-data nil false false))
            "a missing URL starts no request")
      (t/is (not (contains? @wasm.fonts/fetching nil))
            "missing URLs are not deduplicated under nil")
      (js/setTimeout
       (fn []
         (t/is (wasm.fonts/font-ready? font-data)
               "the missing face settled through fallback")
         (done))
       0))))

(t/deftest wasm-font-resize-waits-for-every-face
  (t/async done
    (let [id           (uuid/next)
          regular-key  {:font-id "gfont-mixed"
                        :weight 400
                        :style 0
                        :emoji? false}
          bold-key     {:font-id "gfont-mixed"
                        :weight 700
                        :style 0
                        :emoji? false}
          stream       (rx/subject)
          events       (atom [])]
      (->> (#'dwtxt/await-font-faces stream #{regular-key bold-key} [id])
           (rx/subs! #(swap! events conj %)))
      (rx/push! wasm.fonts/font-stored-stream regular-key)
      (-> (pwrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "the first face released the font task"))
          (.catch #(t/is true "the second face remained pending"))
          (.then (fn []
                   (rx/push! wasm.fonts/font-storage-failed-stream bold-key)
                   (pwrf/wait-for-layout-update [id] 100)))
          (.then (fn []
                   (t/is (= 1 (count @events))
                         "all faces settling dispatches exactly one resize")
                   (done)))
          (.catch (fn [_]
                    (t/is false "the complete face set did not drain")
                    (done)))))))

(t/deftest buffered-wasm-resize-releases-on-stop-without-a-commit
  (t/async done
    (let [store (ptk/store {:state {} :on-error #(js/console.error %)})
          id    (uuid/next)]
      (ptk/emit! store (dwsh/update-shapes-buffer-start))
      (ptk/emit! store (dwwt/resize-wasm-text-all [id]))
      (-> (pwrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "the buffered resize was not marked pending"))
          (.catch #(t/is true "the resize stayed pending while the buffer was open"))
          (.then (fn []
                   (ptk/emit! store (dwsh/update-shapes-buffer-stop))
                   (pwrf/wait-for-layout-update [id] 500)))
          (.then #(t/is true "buffer stop released the fallback resize"))
          (.catch #(t/is false "buffer stop without a commit leaked pending work"))
          (.then (fn [] (done)))))))

(t/deftest buffered-wasm-resize-releases-on-workspace-finalize
  (t/async done
    (let [store (ptk/store {:state {} :on-error #(js/console.error %)})
          id    (uuid/next)]
      (ptk/emit! store (dwsh/update-shapes-buffer-start))
      (ptk/emit! store (dwwt/resize-wasm-text-all [id]))
      (-> (pwrf/wait-for-layout-update [id] 20)
          (.then #(t/is false "the buffered resize was not marked pending"))
          (.catch #(t/is true "the resize stayed pending while the buffer was open"))
          (.then (fn []
                   (ptk/emit! store (ptk/data-event :app.main.data.workspace/finalize-workspace))
                   (pwrf/wait-for-layout-update [id] 500)))
          (.then #(t/is true "workspace finalization released the buffered resize"))
          (.catch #(t/is false "workspace finalization leaked pending work"))
          (.then (fn [] (done)))))))

(t/deftest layout-update-is-pending-until-the-buffer-flushes
  ;; A shape id is marked on arrival and drained when the update is processed.
  (t/async done
    (let [store (start-pipeline!)]
      (ptk/emit! store (ptk/data-event :layout/update {:ids [(uuid/next) uuid/zero]}))
      (-> (pwrf/wait-for-layout-update nil 20)
          (.then #(t/is false "resolved while the update was still buffered"))
          (.catch #(t/is true "stayed pending until the flush"))
          (.then #(pwrf/wait-for-layout-update nil 5000))
          (.then #(t/is true "resolved once the update was processed"))
          (.catch #(t/is false "the pipeline never drained its mark"))
          (.then (fn []
                   (stop-pipeline! store)
                   (done)))))))

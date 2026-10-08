;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer.wasm
  "Main-thread side of the headless renderer.

  Renders run on pooled workers because Skia calls are synchronous and
  would block the event loop for every other export. Each batch keeps
  one worker for all its renders, sharing its caches and pool slot.

  `sys` is task-free infrastructure (the wasm pool instance, the
  `{:pool :timeout-ms}` map the pool service owns — hence unwrapping
  one level to reach the raw pool). The lease map holds the rest:
  `on-scope`, a fn receiving the leased 2-arg render fn and firing
  every headless export through it, and `check-cancelled`, the
  zero-arg cancel check raising `:job-cancelled` once the job ended.
  Cancel is fully driver-owned: the check runs before each render,
  and a watchdog interval polls it mid-render — on cancel the leased
  worker aborts (shared signal flipped, thread terminated) and the
  render rejects. The buffer the worker polls is born in the lease
  itself; nobody outside knows what it is. A nil check arms no
  interval and wires no cancel."
  (:require
   [app.common.logging :as l]
   [exporter.wasm.pool :as pool]))

(defn- serializer
  "Chains thunks so a job's renders run one at a time on its worker. A
  failure is isolated: it doesn't break the chain for the next one."
  []
  (let [queue (atom (js/Promise.resolve nil))]
    (fn [thunk]
      (let [result (.then @queue (fn [_] (thunk)) (fn [_] (thunk)))]
        (reset! queue (.then result (fn [_] nil) (fn [_] nil)))
        result))))

(defn ^:async with-scope
  "Leases one worker for the batch: `on-scope` fires every headless
  export through the leased render fn, one at a time on the same
  worker, so the check runs as each render's turn comes up. The
  interval dies with the lease; a truly stuck thread is backstopped
  by the pool's own silence watchdog."
  [sys {:keys [check-cancelled on-scope watch-interval] :or {watch-interval 1000}}]
  (await
   (pool/with-worker (:pool (:wasm-pool sys))
     (fn [worker]
       ((^:async fn []
          (let [chain  (serializer)
                live   (volatile! worker)
                signal (js/Int32Array. (js/SharedArrayBuffer. 4))
                timer  (volatile! nil)
                opts   {:cancel-buffer (.-buffer signal)
                        :check-cancelled check-cancelled
                        :timeout-ms    (:timeout-ms (:wasm-pool sys))}]
            (vreset! timer
                     (when check-cancelled
                       (js/setInterval
                        (fn []
                          (try
                            (check-cancelled)
                            (catch :default _cause
                              ;; One-shot: the lease is doomed anyway.
                              ;; Between objects the worker sees the
                              ;; flag; inside a render only terminating
                              ;; the thread stops it.
                              (some-> @timer js/clearInterval)
                              (js/Atomics.store signal 0 1)
                              (some-> @live pool/terminate))))
                        watch-interval)))
            (try
              (await (on-scope (fn [params on-object]
                                 (chain (fn [] (pool/render-on worker params on-object opts))))))
              (finally
                (some-> @timer js/clearInterval)
                (vreset! live nil))))))))))

(defn ^:async render
  "Same shape as `exporter.renderer.browser/render`: one export as a
  batch of one."
  [sys params on-object check]
  (l/info :hint "render" :type (:type params) :backend "wasm")
  (await (with-scope sys {:exports [params]
                          :on-object on-object
                          :check-cancelled check
                          :on-scope (fn [render-wasm] (render-wasm params on-object))})))

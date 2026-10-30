;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.wasm.scope
  "One leased render worker for a whole batch.

  The pool owns lending; this level owns what a lease means: every
  headless export runs on the same worker, one at a time, sharing its
  caches and pool slot, with the cancel abort armed for the life of
  the lease. Task keys arrive in `cfg` under `:exporter.renderer/*`
  (set by whoever built the batch); the pool instance under the
  pool's own key."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
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

(defn- render-on-worker-
  "Settles when the worker reports the render finished, failed, or the
  thread went away. That last case matters: a terminated worker (how a
  cancel stops a render mid-Skia) emits `exit` and never `error`, and a
  promise left pending there would keep its pool slot borrowed for the
  life of the process."
  [^js worker params cancel-buffer on-object timeout-ms]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (volatile! nil)]
       (letfn [(disarm []
                 (when-let [t @timer]
                   (js/clearTimeout t)
                   (vreset! timer nil)))

               (rearm []
                 (disarm)
                 (vreset! timer (js/setTimeout
                                 (fn []
                                   (l/error :hint "render worker went silent, terminating"
                                            :worker-id (unchecked-get worker "__id"))
                                   (cleanup)
                                   ;; Terminating is what frees the pool slot:
                                   ;; the `exit` it raises has no listener left.
                                   (unchecked-set worker "__alive" false)
                                   (.terminate ^js worker)
                                   (reject (ex/error :type :internal
                                                     :code :render-timeout
                                                     :hint "render worker stopped responding")))
                                 timeout-ms)))

               (cleanup []
                 (disarm)
                 (.off worker "message" on-message)
                 (.off worker "error" on-error)
                 (.off worker "exit" on-exit))

               (on-error [cause]
                 (cleanup)
                 (reject cause))

               (on-exit [code]
                 (cleanup)
                 (reject (ex/error :type :internal
                                   :code :worker-exited
                                   :hint (str "render worker exited with code " code))))

               (on-message [data]
                 (rearm)
                 (case (unchecked-get data "type")
                   ;; A failure while the main thread handles the object
                   ;; (moving the file, appending to the zip) has to end
                   ;; the render too, or nothing ever settles this promise.
                   "object" (try
                              (on-object (t/decode-str (unchecked-get data "payload")))
                              (catch :default cause
                                (cleanup)
                                (reject cause)))
                   "done"   (do (cleanup) (resolve nil))
                   "error"  (do (cleanup)
                                (reject (ex/error :type :internal
                                                  :code (or (some-> (unchecked-get data "code") keyword)
                                                            :wasm-render-error)
                                                  :hint (unchecked-get data "message"))))
                   nil))]

         (.on worker "message" on-message)
         (.once worker "error" on-error)
         (.once worker "exit" on-exit)
         (rearm)
         (.postMessage worker #js {:type "render"
                                   :params (t/encode-str params)
                                   :cancel cancel-buffer}))))))

(defn ^:async render-on
  "Low-level render on the worker leased in `cfg`. A missing worker
  or timeout raises instead of failing obscurely (`:worker-not-leased`,
  `:timeout-ms-missing`): both are programmer errors, and a missing
  timeout must never degrade silently into the default silence budget."
  [cfg params]
  (let [worker     (::pool/worker cfg)
        timeout-ms (or (:timeout-ms (:exporter.wasm/pool cfg))
                       (throw (ex/error :type :assertion
                                        :code :timeout-ms-missing
                                        :hint "render-on needs :timeout-ms in cfg")))]
    (when-not worker
      (throw (ex/error :type :assertion
                       :code :worker-not-leased
                       :hint "render-on needs a leased worker in cfg")))
    (when-let [check-cancelled (:exporter.renderer/check-cancelled cfg)]
      (check-cancelled))
    (await (render-on-worker- worker
                              params
                              (::cancel-buffer cfg)
                              (:exporter.renderer/on-object cfg)
                              timeout-ms))))

(defn ^:async run
  "Leases one worker for `on-scope`, a fn of a single-arg render fn.
  Every headless export fires through it, one at a time on the same
  worker, so the check-cancelled runs as each render's turn comes up. The check-cancelled
  is mandatory: without one the lease raises `:check-cancelled-
  missing` before acquiring anything. The per lease signal buffer is
  born here; the interval dies with the lease. A truly stuck thread
  is backstopped by the pool's own silence watchdog."
  [cfg on-scope]
  (let [check-cancelled (:exporter.renderer/check-cancelled cfg)]
    (when-not (fn? check-cancelled)
      (throw (ex/error :type :assertion
                       :code :check-cancelled-missing
                       :hint "scope/run needs a zero-arg check-cancelled in cfg")))
    (await
     (pool/run cfg
               (^:async fn [cfg]
                 (let [worker     (::pool/worker cfg)
                       chain      (serializer)
                       live       (volatile! worker)
                       signal     (js/Int32Array. (js/SharedArrayBuffer. 4))
                       timer      (volatile! nil)
                       render-cfg (assoc cfg
                                         ::cancel-buffer (.-buffer signal))]
                   (vreset! timer
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
                             (::watch-interval cfg 1000)))
                   (try
                     (await (on-scope (fn [params] (chain (fn [] (render-on render-cfg params))))))
                     (finally
                       (some-> @timer js/clearInterval)
                       (vreset! live nil)))))))))

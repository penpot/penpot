;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer.wasm
  "Main-thread side of the headless renderer.

  Renders run on pooled workers because Skia calls are synchronous and
  would block the event loop for every other export. Each job keeps one
  worker for all its renders, sharing its caches and pool slot.

  The worker pool arrives as the instance map the pool service owns
  (`{:pool :timeout-ms}`); the cancel source arrives as plain fns, so
  production wires the jobs registry and tests wire atoms:

  ```clojure
  {:cancel-signal (fn [job-id] ...)
   :cancelled?    (fn [job-id] ...)
   :on-cancel     (fn [job-id f] ...)}
  ```"
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
  "Runs `f`, a fn of a 2-arg render fn. Every render goes to the same
  worker, one at a time, so the cancel check runs as each render's turn
  comes up. Without a `job-id` nothing is wired for cancel."
  [wpool cancel-source job-id f]
  (let [{:keys [cancel-signal cancelled? on-cancel]} cancel-source]
    (await
     (pool/with-worker (:pool wpool)
       (fn [worker]
         ((^:async fn []
            (let [chain  (serializer)
                  live   (volatile! worker)
                  signal (when job-id (cancel-signal job-id))
                  opts   {:cancel-buffer (some-> signal (.-buffer))
                          :cancelled?    (when job-id (fn [] (cancelled? job-id)))
                          :timeout-ms    (:timeout-ms wpool)}]
              (when job-id
                ;; Between objects the worker sees the flag; inside a
                ;; render only terminating the thread stops it. Cleared
                ;; on the way out so a later cancel cannot terminate a
                ;; worker that is by then somebody else's.
                (on-cancel job-id (fn [] (pool/terminate @live))))
              (try
                (await (f (fn [params on-object]
                            (chain (fn [] (pool/render-on worker params on-object opts))))))
                (finally
                  (vreset! live nil)))))))))))

(defn ^:async render
  [wpool cancel-source params on-object]
  (let [job-id (:job-id params)]
    (l/info :hint "render" :type (:type params) :backend "wasm")
    (await (with-scope wpool cancel-source job-id
             (fn [render*] (render* params on-object))))))

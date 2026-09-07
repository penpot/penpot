;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.renderer.wasm
  "Main-thread side of the headless renderer.

  Renders run on pooled workers because Skia calls are synchronous and would block
  the HTTP server and other exports. Each job keeps one worker for all its renders,
  sharing its caches and pool slot."
  (:require
   [app.jobs :as jobs]
   [app.wasm.pool :as pool]
   [promesa.core :as p]))

(defn- serializer
  "Chains thunks so a job's renders run one at a time on its worker. A failure
  is isolated: it doesn't break the chain for the next one."
  []
  (let [queue (atom (p/resolved nil))]
    (fn [thunk]
      (let [result (p/handle @queue (fn [_ _] (thunk)))]
        (reset! queue (p/handle result (fn [_ _] nil)))
        result))))

(defn with-scope
  "Runs `f`, a fn of a 2-arg render fn. Every render goes to the same worker,
  one at a time, so the cancel check runs as each render's turn comes up."
  [job-id f]
  (pool/with-worker
    (fn [worker]
      (let [chain  (serializer)
            live   (volatile! worker)
            signal (when job-id (jobs/cancel-signal job-id))
            opts   {:cancel-buffer (some-> signal (.-buffer))
                    :cancelled? (when job-id #(jobs/cancelled? job-id))}]
        (when job-id
          ;; Between objects the worker sees the flag; inside a render only
          ;; terminating the thread stops it. Cleared on the way out so a later
          ;; cancel cannot terminate a worker that is by then somebody else's.
          (jobs/on-cancel job-id (fn [] (pool/terminate! @live))))
        (->> (p/do (f (fn [params on-object]
                        (chain #(pool/render-on worker params on-object opts)))))
             (p/fnly (fn [_ _] (vreset! live nil))))))))

(defn render
  [params on-object]
  (with-scope (:job-id params) (fn [render*] (render* params on-object))))

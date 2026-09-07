;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.core
  (:require
   ["node:process" :as proc]
   ["node:worker_threads" :as wt]
   [app.browser :as bwr]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http :as http]
   [app.jobs :as jobs]
   [app.jobs.utils :as job.utils]
   [app.redis :as redis]
   [app.wasm :as wasm]
   [app.wasm.pool :as wasm.pool]
   [app.wasm.worker :as wasm.worker]
   [promesa.core :as p]))

(enable-console-print!)
(l/setup! {:app :info})

(defn start
  "Render workers run this same bundle, so the thread decides what gets booted:
  the http server and its pools, or one render worker."
  [& _]
  (if-not ^boolean wt/isMainThread
    (wasm.worker/main)
    (do
      (l/info :msg "initializing"
              :public-uri (str (cf/get :public-uri))
              :internal-uri (str (cf/get-internal-uri))
              :version (:full cf/version))
      (when (contains? cf/flags :wasm-export)
        (l/info :msg "headless wasm export enabled (experimental)"
                :wasm-dir wasm/artifact-dir
                :workers (cf/get :wasm-worker-pool-max)
                :image-cache-size (cf/get :wasm-worker-image-cache-size)))
      (p/do
        (bwr/init)
        (redis/init)
        (jobs/init)
        (job.utils/init)
        (wasm.pool/init)
        (http/init)))))

(def main start)

;; Draining a pool waits for every checked-out resource to come back, which an
;; export in flight can hold for as long as its own timeout. On a hot reload
;; that would block `start` from ever running again, leaving a drained pool that
;; fails every later job.
(def ^:private shutdown-step-timeout 3000)

(defn- shutdown-step
  [label f]
  (-> (p/race [(p/do (f))
               (p/fmap (constantly ::timeout) (p/delay shutdown-step-timeout))])
      (p/handle (fn [result cause]
                  (when (or (some? cause) (= ::timeout result))
                    (l/warn :hint "shutdown step did not finish cleanly"
                            :step label
                            :cause cause))
                  nil))))

(defn stop
  [done]
  ;; an empty line for visual feedback of restart
  (js/console.log "")

  (if-not ^boolean wt/isMainThread
    ;; A render worker owns no server, pools or connections; nothing to unwind.
    (done)
    (do
      (l/info :msg "stopping")
      (p/do
        (shutdown-step "browser-pool" bwr/stop)
        (shutdown-step "wasm-worker-pool" wasm.pool/stop)
        (shutdown-step "redis" redis/stop)
        (shutdown-step "http" http/stop)
        (done)))))

(.on proc/default "uncaughtException"
     (fn [cause]
       (js/console.error cause)))

;; Signals are only delivered to the main thread, and `exit` in a worker would
;; take down that worker rather than the process.
(when ^boolean wt/isMainThread
  (.on proc/default "SIGTERM" (fn [] (proc/exit 0)))
  (.on proc/default "SIGINT" (fn [] (proc/exit 0))))

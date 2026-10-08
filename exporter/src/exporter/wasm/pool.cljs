;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.wasm.pool
  "Pool of headless render workers.

  Mirrors `exporter.browser`: a `generic-pool` whose objects are
  `worker_threads` instead of browsers, so acquisition and eviction
  behave the same way for both render backends. A worker is expensive
  to build (it boots its own render-wasm module), hence the pooling.

  Acquisition is not capped: the admission scheduler is the
  backpressure, and the idle watchdog guarantees a wedged worker gives
  its slot back.

  Workers run the same bundle as the main thread. There is always at
  least one worker, since a headless render has nowhere else to go.

  Unlike the browser service, the running instance is a map: the pool
  plus the silence timeout the renders await on it. There are no
  promesa chains in this namespace: the pool speaks native promises,
  and `await` covers the rest."
  (:require
   ["generic-pool" :as gp]
   ["node:path" :as path]
   ["node:process" :as proc]
   ["node:worker_threads" :as wt]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [exporter.utils.system :as system]))

(l/set-level! :info)

(def defaults
  "Static pool defaults. The sizing (`:max`) mirrors the worker
  concurrency default; the wiring layer passes the env-derived values."
  {:max               2
   :min               1
   :timeout-ms        300000
   :ready-timeout-ms  60000
   :eviction-interval 30000
   :eviction-batch    2
   :idle-timeout      300000})

(defn await-ready
  "Resolves the worker once it posts its ready handshake. A worker
  that stays silent too long is terminated and rejected with
  `:worker-not-ready`; a boot error rejects with its own cause."
  [worker timeout-ms]
  (js/Promise.
   (fn [resolve reject]
     (let [timer (js/setTimeout
                  (fn []
                    (l/error :hint "render worker did not become ready")
                    (.terminate ^js worker)
                    (reject (ex/error :type :internal
                                      :code :worker-not-ready
                                      :hint "render worker did not become ready")))
                  timeout-ms)]
       (.on ^js worker "error"
            (fn [cause]
              (l/error :hint "render worker error" :cause cause)
              (unchecked-set worker "__alive" false)
              ;; Rejecting after `resolve` is a no-op, so this is safe
              ;; for the errors that arrive once the worker is pooled.
              (js/clearTimeout timer)
              (reject cause)))
       (.on ^js worker "exit"
            (fn [code]
              (l/info :hint "render worker exited" :code code)
              (unchecked-set worker "__alive" false)))
       ;; Not `.once`: a stray message before the handshake would
       ;; consume the listener and hang the worker until the timeout.
       (letfn [(on-ready [data]
                 (when (= "ready" (unchecked-get data "type"))
                   (js/clearTimeout timer)
                   (.off ^js worker "message" on-ready)
                   (resolve worker)))]
         (.on ^js worker "message" on-ready))))))

(defn- default-script
  []
  (path/resolve (aget (.-argv proc/default) 1)))

(defn- default-create-worker
  [script ready-timeout-ms]
  (fn []
    (await-ready (new wt/Worker script) ready-timeout-ms)))

(defn- ^:async create-instance
  [create-worker id-counter]
  (let [worker (await (create-worker))
        id     (swap! id-counter inc)]
    (l/info :origin "factory" :action "create" :worker-id id)
    (unchecked-set worker "__id" id)
    (unchecked-set worker "__alive" true)
    worker))

(defn- destroy-instance
  [worker]
  (l/info :origin "factory" :action "destroy"
          :worker-id (unchecked-get worker "__id"))
  (unchecked-set worker "__alive" false)
  (.terminate ^js worker))

(defn- validate-instance
  [worker]
  (true? (unchecked-get worker "__alive")))

(defn- make-factory
  [create-worker]
  (let [id-counter (atom 0)]
    #js {:create   (fn [] (create-instance create-worker id-counter))
         :destroy  destroy-instance
         :validate validate-instance}))

(defmethod system/init-key ::pool
  [_ cfg]
  (let [opts          (merge defaults (d/without-nils cfg))
        max-workers   (:max opts)
        script        (or (:script opts) (default-script))
        create-worker (or (:create-worker opts)
                          (default-create-worker script (:ready-timeout-ms opts)))]
    (l/info :hint "initializing render worker pool" :max max-workers)
    {:pool       (gp/createPool (make-factory create-worker)
                                #js {:max max-workers
                                     :min (min max-workers (:min opts))
                                     :testOnBorrow true
                                     :evictionRunIntervalMillis (:eviction-interval opts)
                                     :numTestsPerEvictionRun (:eviction-batch opts)
                                     :idleTimeoutMillis (:idle-timeout opts)})
     :timeout-ms (:timeout-ms opts)}))

(defn- ^:async drain-pool
  [pool]
  (await (.drain ^js pool))
  (await (.clear ^js pool))
  nil)

(defmethod system/halt-key ::pool
  [_ instance]
  (when-let [pool (:pool instance)]
    (l/info :hint "finalizing render worker pool")
    (drain-pool pool)))

(defn- pool?
  "A raw generic-pool: anything lendable through acquire and release."
  [x]
  (and (some? x)
       (fn? (unchecked-get x "acquire"))
       (fn? (unchecked-get x "release"))))

(defn ^:async run
  "Lends one worker: resolves the pool from `cfg` (the `::pool`
  service instance, or a raw pool), acquires a worker, applies `f` to
  the cfg carrying it under `::worker` plus any extra `args`, and
  returns the worker — or destroys it when `f` fails, the way a failed
  transaction rolls back instead of returning its connection: a failed
  worker may be aborted or mid-write, and a terminated worker cannot
  come back."
  [cfg f & args]
  (let [cfg  (if (pool? cfg) {::pool {:pool cfg}} cfg)
        pool (:pool (::pool cfg))]
    (when-not (pool? pool)
      (throw (ex/error :type :assertion
                       :code :invalid-pool-cfg
                       :hint "no pool in cfg")))
    (let [worker (await (.acquire ^js pool))]
      (try
        (let [result (await (apply f (assoc cfg ::worker worker) args))]
          (await (.release ^js pool worker))
          result)
        (catch :default cause
          (try
            (await (.destroy ^js pool worker))
            (catch :default cause'
              (l/warn :hint "render worker destroy failed"
                      :cause cause')))
          (throw cause))))))

(defn terminate
  [worker]
  (when worker
    (unchecked-set worker "__alive" false)
    (.terminate worker)))

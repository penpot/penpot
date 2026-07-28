;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.pool
  "Main-thread pool of headless render workers.

  A render is synchronous wasm work; running it here would block the node
  event loop for the whole export, stalling the http server and every other
  request. So each render runs in a `node:worker_threads` worker owning its
  own wasm module, and this namespace does the scheduling.

  Round-robin: any idle worker takes the next request, so one large export
  spreads across the pool. Two consequences worth knowing:

  - Each worker holds its own image/font cache, so partitions of the same
    export do not share a warm cache and may refetch the same images.
  - Objects no longer complete in submission order across partitions. Callers
    that care about order must sort by `:index` (see
    `handlers.export-frames`); appending in completion order gives a PDF with
    shuffled pages.

  Because the render is off-thread, a stuck request can finally be bounded:
  the watchdog below terminates a worker that stops reporting progress, and a
  fresh one replaces it."
  (:require
   ["node:path" :as path]
   ["node:process" :as proc]
   ["node:worker_threads" :as wt]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [promesa.core :as p]))

(def ^:private default-pool-size
  "Conservative: every worker holds a full wasm heap plus its own image cache,
  so memory grows with the pool, not just cpu usage."
  2)

(def ^:private default-timeout-ms
  "Watchdog budget between two progress reports, not for the whole request: a
  50-object partition is legitimately slow, a wedged one reports nothing."
  120000)

(def ^:private crash-window-ms
  "A worker dying sooner than this after spawning never got as far as doing
  work, so it is a startup failure (bad config, missing wasm artifact) that
  restarting cannot fix — not a crash on some particular file."
  5000)

(def ^:private max-restarts 5)
(def ^:private restart-backoff-ms 1000)

(defn pool-size [] (max 1 (cf/get :wasm-pool-size default-pool-size)))
(defn timeout-ms [] (cf/get :wasm-render-timeout default-timeout-ms))

(defn- worker-script
  "Absolute path of the compiled worker bundle. Derived from the running
  script (`app.js`) so it resolves both in devenv (`node target/app.js`) and
  in the docker image (`/opt/penpot/exporter/app.js`)."
  []
  (or (cf/get :wasm-worker-script)
      (-> (aget (.-argv ^js proc/default) 1)
          (path/dirname)
          (path/resolve "worker" "wasm-worker.js"))))

;; --- pool state
;;
;; One atom per worker so a single worker's lifecycle can be swapped without
;; touching the others. Each holds {:worker :job :timer}; `:job` is nil when
;; idle, and a worker is never given a second job while one is in flight.

(defonce ^:private workers* (atom []))
(defonce ^:private pending* (atom #queue []))
(defonce ^:private req-id* (atom 0))

(declare pump! respawn!)

(defn- pool-dead?
  []
  (let [workers @workers*]
    (and (seq workers) (every? (fn [w] (:dead? @w)) workers))))

(defn- drain-pending!
  "Fails everything still queued. Called when the last worker gives up: without
  it those requests would wait for a worker that is never coming back."
  [cause]
  (let [queued @pending*]
    (reset! pending* #queue [])
    (doseq [job queued]
      ((:reject job) cause))))

;; --- watchdog

(defn- clear-timer!
  [wstate]
  (when-let [timer (:timer @wstate)]
    (js/clearTimeout timer))
  (swap! wstate dissoc :timer))

(defn- arm-timer!
  [wstate]
  (clear-timer! wstate)
  (let [timer (js/setTimeout
               (fn []
                 (when-let [job (:job @wstate)]
                   (l/error :hint "wasm pool: worker stopped reporting progress, terminating"
                            :request-id (:id job)
                            :timeout (timeout-ms))
                   (swap! wstate assoc :job nil)
                   ((:reject job) (ex-info "headless render timed out"
                                           {:request-id (:id job)
                                            :timeout (timeout-ms)}))
                   (respawn! wstate)))
               (timeout-ms))]
    (swap! wstate assoc :timer timer)))

;; --- job lifecycle

(defn- settle-ok!
  "Resolves the job, but only once every `on-object` call has settled: those
  run as a promise chain so the caller's zip append / file move keep the
  order the worker emitted them in."
  [wstate]
  (clear-timer! wstate)
  (when-let [job (:job @wstate)]
    (swap! wstate assoc :job nil)
    (->> @(:chain job)
         (p/fmap (fn [_] ((:resolve job) nil)))
         (p/merr (fn [cause] ((:reject job) cause) (p/resolved nil)))))
  (pump!))

(defn- settle-error!
  [wstate cause]
  (clear-timer! wstate)
  (when-let [job (:job @wstate)]
    (swap! wstate assoc :job nil)
    ((:reject job) cause))
  (pump!))

(defn- append-object!
  "Chains one `on-object` call after the previous one. The worker may run ahead
  of the caller (it writes tempfiles as fast as it renders); chaining keeps the
  callbacks themselves strictly ordered."
  [job object path]
  (swap! (:chain job)
         (fn [prev]
           (->> prev
                (p/mcat (fn [_] (p/do ((:on-object job) (assoc object :path path)))))))))

(defn- on-message
  [wstate ^js msg]
  (let [type (unchecked-get msg "type")
        job  (:job @wstate)]
    ;; A reply can outlive the job it belongs to (watchdog fired, worker was
    ;; already terminated); drop anything that no longer matches.
    (when (and job (= (:id job) (unchecked-get msg "id")))
      (case type
        "object" (do (arm-timer! wstate)
                     (append-object! job
                                     (t/decode-str (unchecked-get msg "object"))
                                     (unchecked-get msg "path")))
        "done"   (settle-ok! wstate)
        "error"  (settle-error! wstate (ex-info (unchecked-get msg "message") {}))
        nil))))

;; --- worker lifecycle

(defn- note-death!
  "Counts consecutive startup crashes. A worker that survived past the crash
  window did real work before dying, so its counter resets."
  [wstate]
  (let [alive-ms (- (js/Date.now) (:spawned-at @wstate 0))]
    (swap! wstate update :failures
           (fn [n] (if (< alive-ms crash-window-ms) (inc (or n 0)) 0)))))

(defn- spawn!
  [wstate]
  (let [worker (new wt/Worker (worker-script))]
    (swap! wstate assoc :spawned-at (js/Date.now))
    (.on ^js worker "message" (fn [msg] (on-message wstate msg)))
    (.on ^js worker "error"
         (fn [cause]
           (l/error :hint "wasm pool: worker crashed" :cause cause)
           (settle-error! wstate cause)
           (respawn! wstate)))
    (.on ^js worker "exit"
         (fn [code]
           ;; A clean exit only happens on `stop`; anything else means the
           ;; worker died under a request, so fail it and rebuild.
           (when-not (:stopping? @wstate)
             (when (:job @wstate)
               (l/error :hint "wasm pool: worker exited mid-render" :code code)
               (settle-error! wstate (ex-info "headless render worker exited" {:code code})))
             (respawn! wstate))))
    (swap! wstate assoc :worker worker)
    wstate))

(defn- respawn!
  [wstate]
  (when-let [worker (:worker @wstate)]
    (swap! wstate dissoc :worker)
    (.terminate ^js worker))
  (note-death! wstate)
  (when-not (:stopping? @wstate)
    (if (>= (:failures @wstate 0) max-restarts)
      ;; Restarting is not going to help: it died on startup this many times in
      ;; a row, so the cause is the environment (config, missing artifact).
      ;; Give up on this worker and, once none are left, stop accepting work
      ;; instead of queueing requests nobody will ever pick up.
      (do
        (swap! wstate assoc :dead? true)
        (l/error :hint "wasm pool: worker keeps failing at startup, giving up"
                 :restarts max-restarts
                 :script (worker-script))
        (when (pool-dead?)
          (l/error :hint "wasm pool: no workers left, failing headless exports")
          (drain-pending! (ex-info "headless render pool is unavailable" {}))))
      (js/setTimeout (fn []
                       (when-not (:stopping? @wstate)
                         (spawn! wstate)
                         (pump!)))
                     (* restart-backoff-ms (inc (:failures @wstate 0)))))))

(defn- ensure-pool!
  []
  (when (empty? @workers*)
    (let [size (pool-size)]
      (l/info :hint "wasm pool: starting" :workers size :script (worker-script))
      (reset! workers* (vec (for [_ (range size)]
                              (spawn! (atom {}))))))))

;; --- dispatch

(defn- dispatch!
  [wstate job]
  (swap! wstate assoc :job job)
  (arm-timer! wstate)
  (.postMessage ^js (:worker @wstate)
                #js {:type   "render"
                     :id     (:id job)
                     :params (t/encode-str (:params job))}))

(defn- pump!
  "Hands queued requests to idle workers, oldest request first."
  []
  (loop []
    (when-let [wstate (->> @workers*
                           (filter (fn [w] (and (:worker @w) (nil? (:job @w)))))
                           (first))]
      (when-let [job (peek @pending*)]
        (swap! pending* pop)
        (dispatch! wstate job)
        (recur)))))

(defn render
  "Runs one export request on a pool worker. `on-object` is called with each
  rendered object plus its tempfile `:path`, in the order the worker produced
  them. Resolves once the request is done and every `on-object` has settled."
  [params on-object]
  (ensure-pool!)
  (p/create
   (fn [resolve reject]
     (if (pool-dead?)
       (reject (ex-info "headless render pool is unavailable" {}))
       (do
         (swap! pending* conj {:id (swap! req-id* inc)
                               :params params
                               :on-object on-object
                               :chain (atom (p/resolved nil))
                               :resolve resolve
                               :reject reject})
         (pump!))))))

(defn stop
  "Terminates every worker. Needed on devenv hot reload, where otherwise each
  reload would leak a full pool."
  []
  (let [workers @workers*]
    (reset! workers* [])
    (p/all (for [wstate workers]
             (do (swap! wstate assoc :stopping? true)
                 (clear-timer! wstate)
                 (when-let [worker (:worker @wstate)]
                   (.terminate ^js worker)))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.consumer.worker
  "The consumer of the backend `:exporter` queue.

  K pollers run in parallel, each one owning its Redis connection: the
  blocking BLPOP of a poller holds that connection for the whole wait,
  and the poller does not come back to the queue until the job it
  claimed has settled, so the connection is both the semaphore of the
  slot and the backpressure — nothing the queue receives can appear
  twice taken, and no process ever runs more exports than it has
  connections.

  The dispatcher pushes `[job-id, scheduled-at]` as JSON into
  `penpot.worker.queue:<tenant>:exporter`. Corrupt payloads are logged
  and dropped; a Redis hiccup is a lost turn, not a lost job: the
  poller waits a bit and pops again (what was already delivered is
  claimed, and the loop does not stop the process). Stopping falls the
  running flag, lets every poller finish the turn it already started,
  and only then quits the connections: a payload that arrives in that
  window goes back to its queue instead of starting an export the
  shutdown is about to cut, and no poller survives its handle.

  The worker is a system component (`:exporter.consumer/worker`): its
  config carries the queue knobs (`:concurrency`, `:queue-key`, the
  `:connect` factory) and the `:renderer` it renders through (the
  `:exporter/renderer` service, injected by ref). There are no promesa
  chains in this namespace."
  (:require
   ["ioredis" :as redis]
   [app.common.logging :as l]
   [app.common.types.job :as ctj]
   [exporter.config :as cf]
   [exporter.consumer :as consumer]
   [exporter.consumer.api :as api]
   [exporter.consumer.config :as ccfg]
   [exporter.utils.system :as system]))

(def ^:private poll-timeout-s 5)
(def ^:private reconnect-delay-ms 1000)
(def ^:private drain-poll-ms 100)
(def ^:private default-shutdown-grace-ms 30000)

(defn- wait
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn read-payload
  "The JSON payload of the dispatcher: `job-id` and the `scheduled-at`
  of the row when it was pushed, decoded against the shape both sides
  share (`app.common.types.job`). Anything else is a corrupt payload:
  none, and the caller drops it."
  [payload]
  (try
    (let [decoded (js->clj (js/JSON.parse payload))]
      ;; `sm/decoder` hands an invalid value back untouched, so the shape
      ;; is checked first: a payload that is not the pair is dropped, not
      ;; turned into a claim with nil ids.
      (if (ctj/valid-queue-payload? decoded)
        (let [[job-id scheduled-at] (ctj/decode-queue-payload decoded)]
          {:job-id job-id :scheduled-at scheduled-at})
        (throw (ex-info "unexpected queue payload" {:payload decoded}))))
    (catch :default cause
      (l/warn :hint "corrupt queue payload dropped"
              :payload (str payload) :cause cause)
      nil)))

(defn ^:async process
  "One payload of the queue: claim the job, and when the backend gives
  it to this poller, run the export to its settle. `skip` means
  something else won it (or it is gone): the payload is dropped, with
  the state the backend says."
  [cfg {:keys [job-id scheduled-at]}]
  (let [answer (await (api/claim-job job-id scheduled-at))]
    (if (= :skip (:action answer))
      (l/info :hint "skipping job" :job-id (str job-id)
              :name (:name answer) :status (:status answer))
      (do
        (l/info :hint "running job" :job-id (str job-id) :name (:name answer))
        (await (consumer/run-export (:renderer cfg) (:exporter/tmpdir cfg) {:job-id job-id} (:params answer)))
        (l/info :hint "job settled" :job-id (str job-id))
        nil))))

;; ---- THE POLLER

(defn ^:async poll-once
  "One turn: pop the queue, claim what came, and wait for the settle.
  An empty pop and a dropped payload both resolve; a claimed job is
  awaited the whole run. A payload popped after the stop flag fell goes
  back to the queue: the shutdown must not start an export it is about
  to cut, and the next worker (or the next start) claims it."
  [handle conn key on-payload]
  (let [answer  (await (.blpop ^js conn key poll-timeout-s))
        payload (second answer)]
    (if (and (some? payload) (not @(:running handle)))
      (do
        (await (.lpush ^js conn key payload))
        (l/info :hint "payload handed back to the queue" :key key)
        nil)
      (let [claim (when (some? payload) (read-payload payload))]
        (when (some? claim)
          (await (on-payload claim))
          nil)))))

(defn- ^:async poll-loop
  "The wait of one poller, turn after turn, until the handle stops. A
  failed turn (Redis down, a bug) is logged, never allowed to end the
  loop; a delay keeps the retry from spinning while the connection
  rebuilds. An explicit loop, not recursion: a self-call under `await`
  would retain one async frame per turn on a process that lives for
  weeks."
  [handle conn key on-payload]
  (loop []
    (when @(:running handle)
      (try
        (await (poll-once handle conn key on-payload))
        (catch :default cause
          (l/error :hint "poller turn failed" :key key :cause cause)
          (await (wait reconnect-delay-ms))))
      (recur))))

(defn- open-connection
  "One Redis connection of one poller. ioredis reconnects on its own
  strategy, so a fallen Redis comes back with this process doing
  nothing but wait (and the failed pop comes right after)."
  []
  (new redis/default (cf/get :redis-uri)))

;; ---- THE START

(defn ^:async start
  "Launches the pollers over the queue and resolves the handle their
  `stop` needs. The temp area boots clean before the first poller: the
  tmpdir service drops what a previous process left on its init."
  [{:keys [concurrency queue-key connect] :as cfg}]
  (let [running    (atom true)
        inflight   (atom 0)
        handle     {:running running :conns (atom []) :loops (atom [])
                    :inflight inflight}
        on-payload (^:async fn [claim]
                     (swap! inflight inc)
                     (try
                       (await (process cfg claim))
                       (finally
                         (swap! inflight dec))))
        open       (or connect open-connection)
        k          (or concurrency (ccfg/concurrency))]
    (l/info :hint "worker started" :pollers k :queue queue-key)
    (doseq [_ (range k)]
      (let [conn (open)]
        (swap! (:conns handle) conj conn)
        (swap! (:loops handle) conj (poll-loop handle conn queue-key on-payload))))
    handle))

(defn- ^:async drain-inflight
  [inflight deadline]
  (when (and (pos? @inflight)
             (< (js/Date.now) deadline))
    (await (wait drain-poll-ms))
    (await (drain-inflight inflight deadline))))

(defn ^:async stop
  "Ends the pollers, closing their connections and their loops: the
  running flag falls first, so a turn that ends after the stop hands
  its payload back instead of starting an export, then the pollers
  finish the turn they already began (bounded by the pop timeout, so a
  running export never holds the shutdown here) and only after that
  the connections quit. The in-flight runs drain within the grace
  period before the pools halt behind them. Idempotent: a second stop
  quits nothing."
  ([handle] (stop handle nil))
  ([handle {:keys [grace-ms turn-ms]
            :or {grace-ms default-shutdown-grace-ms
                 turn-ms (* 1000 (inc poll-timeout-s))}}]
   (when (and (some? handle)
              (compare-and-set! (:running handle) true false))
     ;; The blocking pop cannot be cancelled, so the wait is bounded by
     ;; its own timeout: after it, every poller is either idle or
     ;; running an export, which the drain below handles.
     (await (js/Promise.race
             #js [(js/Promise.allSettled @(or (:loops handle) (atom [])))
                  (wait turn-ms)]))
     (await (js/Promise.allSettled
             (mapv (fn [conn] (.quit ^js conn)) @(:conns handle))))
     (let [inflight (or (:inflight handle) (atom 0))]
       (await (drain-inflight inflight (+ (js/Date.now) grace-ms))))
     nil)))

(defmethod system/init-key ::consumer/worker
  [_ cfg]
  (start cfg))

(defmethod system/halt-key ::consumer/worker
  [_ handle]
  (stop handle))

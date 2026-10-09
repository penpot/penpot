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
  claimed, and the loop does not stop the process). Stopping quits
  every connection and ends every loop: unlike the legacy worker,
  whose loops kept failing turns after the stop, no poller survives
  its handle.

  The worker is a system component (`:exporter.consumer/worker`): its
  config carries the queue knobs (`:concurrency`, `:queue-key`, the
  `:connect` factory) and the `:renderer` it renders through (the
  `:exporter/renderer` service, injected by ref). There are no promesa
  chains in this namespace."
  (:require
   ["ioredis" :as redis]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.jobs.utils :as job.utils]
   [exporter.consumer :as consumer]
   [exporter.consumer.api :as api]
   [exporter.consumer.config :as ccfg]
   [exporter.utils.system :as system]))

(def ^:private poll-timeout-s 5)
(def ^:private reconnect-delay-ms 1000)

(defn- wait
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn read-payload
  "The JSON payload of the dispatcher: `job-id` and the `scheduled-at`
  of the row when it was pushed. Anything else is a corrupt payload:
  none, and the caller drops it."
  [payload]
  (try
    (let [[job-id scheduled-at] (js->clj (js/JSON.parse payload))]
      {:job-id job-id :scheduled-at scheduled-at})
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
  awaited the whole run."
  [conn key on-payload]
  (let [answer  (await (.blpop ^js conn key poll-timeout-s))
        payload (second answer)
        claim   (when (some? payload) (read-payload payload))]
    (when (some? claim)
      (await (on-payload claim))
      nil)))

(defn- ^:async poll-loop
  "The wait of one poller, turn after turn, until the handle stops. A
  failed turn (Redis down, a bug) is logged, never allowed to end the
  loop; a delay keeps the retry from spinning while the connection
  rebuilds."
  [handle conn key on-payload]
  (try
    (await (poll-once conn key on-payload))
    (catch :default cause
      (l/error :hint "poller turn failed" :key key :cause cause)
      (await (wait reconnect-delay-ms))))
  (when @(:running handle)
    (await (poll-loop handle conn key on-payload))))

(defn- open-connection
  "One Redis connection of one poller. ioredis reconnects on its own
  strategy, so a fallen Redis comes back with this process doing
  nothing but wait (and the failed pop comes right after)."
  []
  (new redis/default (cf/get :redis-uri)))

;; ---- THE START

(defn ^:async start
  "Launches the pollers over the queue and resolves the handle their
  `stop` needs. A previous process leaves no temp files behind: the
  boot cleans what the crash owned before the first pop."
  [{:keys [concurrency queue-key connect] :as cfg}]
  (await (job.utils/clean-orphans))
  (let [running    (atom true)
        handle     {:running running :conns (atom [])}
        on-payload (fn [claim] (process cfg claim))
        open       (or connect open-connection)
        k          (or concurrency (ccfg/concurrency))]
    (l/info :hint "worker started" :pollers k :queue queue-key)
    (doseq [_ (range k)]
      (let [conn (open)]
        (swap! (:conns handle) conj conn)
        (poll-loop handle conn queue-key on-payload)))
    handle))

(defn ^:async stop
  "Ends the pollers, closing their connections and their loops: the
  running flag falls first, so a turn that ends after the quit does
  not pop again. Idempotent: a second stop quits nothing."
  [handle]
  (when (and (some? handle)
             (compare-and-set! (:running handle) true false))
    (await (js/Promise.allSettled
            (mapv (fn [conn] (.quit ^js conn)) @(:conns handle))))
    nil))

(defmethod system/init-key ::consumer/worker
  [_ cfg]
  (start cfg))

(defmethod system/halt-key ::consumer/worker
  [_ handle]
  (stop handle))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.consumer.worker
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
  claimed, and the loop does not stop the process)."
  (:require
   ["ioredis" :as redis]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.consumer.api :as api]
   [app.consumer.config :as ccfg]
   [app.consumer.exports :as exports]
   [promesa.core :as p]))

(def ^:private poll-timeout-s 5)
(def ^:private reconnect-delay-ms 1000)

(defonce ^:private pollers (atom {}))

(defn- queue-key
  "The list the backend dispatcher pushes to."
  []
  (ccfg/queue-key))

(defn- connect!
  "One Redis connection of one poller. ioredis reconnects on its own
  strategy, so a fallen Redis comes back with this process doing
  nothing but wait (and the failed pop comes right after)."
  []
  (new redis/default (cf/get :redis-uri)))

;; ---- THE QUEUE

(defn read-payload!
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

(defn process!
  "One payload of the queue: claim the job, and when the backend gives
  it to this poller, run the export to its settle. `skip` means
  something else won it (or it is gone): the payload is dropped, with
  the state the backend says."
  [{:keys [job-id scheduled-at]}]
  (p/let [answer (api/claim-job job-id scheduled-at)]
    (if (= :skip (:action answer))
      (l/info :hint "skipping job" :job-id (str job-id)
              :name (:name answer) :status (:status answer))
      (p/do
        (l/info :hint "running job" :job-id (str job-id) :name (:name answer))
        (exports/run-export! {:job-id job-id} (:params answer))
        (l/info :hint "job settled" :job-id (str job-id))))))

;; ---- THE POLLER

(defn poll-once!
  "One turn: pop the queue, claim what came, and wait for the settle.
  An empty pop and a dropped payload both resolve; a claimed job is
  awaited the whole run."
  [conn key on-payload]
  (p/let [answer  (.blpop ^js conn key poll-timeout-s)
          payload (second answer)
          claim   (when (some? payload) (read-payload! payload))
          settled (when (some? claim) (on-payload claim))]
    settled))

(defn- poll-loop!
  "The wait of one poller, turn after turn, for the life of the
  process. A failed turn (Redis down, a bug) is logged, never allowed
  to end the loop; a delay keeps the retry from spinning while the
  connection rebuilds."
  [conn key on-payload]
  (p/loop []
    (->> (poll-once! conn key on-payload)
         (p/merr (fn [cause]
                   (l/error :hint "poller turn failed" :key key :cause cause)
                   (p/delay reconnect-delay-ms)))
         (p/fmap (fn [_] (p/recur))))))

;; ---- THE START

(defn start!
  "Launches `K` pollers, one connection each. The worker cleans the
  temp files a previous process left behind at the same moment: a
  crashed worker owns nothing of its crash."
  []
  (let [key (queue-key)
        k   (ccfg/concurrency)]
    (doseq [i (range k)]
      (let [conn (connect!)]
        (swap! pollers assoc i conn)
        (l/info :hint "poller started" :id i :key key)
        (poll-loop! conn key process!)))))

(defn stop!
  "Ends the pollers, closing their connections. The job that got orphan
  in a settle goes one row further to the next poller; no state hides
  in this process."
  []
  (doseq [conn (vals @pollers)]
    (.quit ^js conn))
  (reset! pollers {}))

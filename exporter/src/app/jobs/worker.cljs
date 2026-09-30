;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.worker
  "The `worker` role: pulls jobs from the shared queue and runs them.

  A job is claimed only while this process has a free slot, so work stays on
  the queue, where any idle worker can take it, rather than piling up in one
  process. Claimed jobs still go through the local scheduler, which keeps
  applying the per-profile and render-worker limits.

  Every worker also reaps: on a timer it puts back on the queue whatever
  instances that stopped sending heartbeats had claimed."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.handlers.export :as export]
   [app.jobs :as jobs]
   [app.jobs.queue :as queue]
   [promesa.core :as p]))

;; How long one blocking claim waits before the loop checks it should keep
;; running. Short enough for a prompt shutdown.
(def ^:private claim-timeout 2)
(def ^:private idle-ms 250)
(def ^:private retry-ms 1000)

(defonce ^:private state
  (atom {:running? false
         :in-flight 0
         :reaper nil}))

(defn- capacity
  []
  (cf/get :exporter-max-concurrent-jobs 4))

(defn in-flight
  []
  (:in-flight @state))

(defn- run-job!
  [job-id job payload]
  (cond
    (nil? job)
    (do (l/warn :hint "claimed job has no record, dropping" :job-id job-id)
        (p/resolved nil))

    (jobs/terminal? job)
    (p/resolved nil)

    (nil? payload)
    (jobs/fail-unowned! job "export payload expired before a worker took it")

    :else
    (export/run-claimed! job payload)))

(defn- process!
  "Runs a claimed job to the end and releases the claim. Never rejects: its
  promise is not awaited by anyone."
  [job-id]
  (->> (p/all [(jobs/fetch job-id)
               (->> (queue/payload job-id)
                    (p/merr (fn [cause]
                              (l/error :hint "unable to read job payload" :job-id job-id :cause cause)
                              (p/resolved nil))))])
       (p/mcat (fn [[job payload]] (run-job! job-id job payload)))
       (p/merr (fn [cause]
                 (l/warn :hint "claimed job did not finish cleanly" :job-id job-id :cause cause)
                 (p/resolved nil)))
       (p/mcat (fn [_] (queue/ack! job-id)))
       (p/fmap (fn [_]
                 (swap! state update :in-flight dec)
                 nil))))

(defn- step!
  "Claims one job when there is a free slot, and starts it without waiting
  for it. Resolves once the loop may go on; never rejects."
  []
  (if (>= (:in-flight @state) (capacity))
    (p/delay idle-ms)
    (->> (queue/claim! claim-timeout)
         (p/fmap (fn [job-id]
                   (when job-id
                     (l/dbg :hint "claimed export job" :job-id job-id)
                     (swap! state update :in-flight inc)
                     (process! job-id))
                   nil))
         (p/merr (fn [cause]
                   (when (:running? @state)
                     (l/warn :hint "unable to claim from the export queue" :cause cause))
                   (p/delay retry-ms))))))

(defn- pull!
  "The claim loop. Each round schedules the next one rather than returning
  it, so the promises of past rounds are never kept alive."
  []
  (when (:running? @state)
    (->> (step!)
         (p/fmap (fn [_]
                   (pull!)
                   nil)))))

(defn- recover!
  []
  (->> (queue/reap!)
       (p/mcat (fn [job-ids] (p/all (map jobs/requeue! job-ids))))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to recover jobs of lost instances" :cause cause)
                 (p/resolved nil)))))

(defn stop
  []
  (when-let [reaper (:reaper @state)]
    (js/clearInterval reaper))
  (swap! state assoc :running? false :reaper nil))

(defn init
  []
  (stop)
  (l/info :hint "export queue worker started" :capacity (capacity))
  (swap! state assoc
         :running? true
         :reaper (js/setInterval recover! (* 1000 (cf/get :exporter-heartbeat-ttl))))
  (recover!)
  (pull!)
  nil)

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs
  "Export job model and lifecycle.

  The record is persisted in redis (`app.jobs.store`); the runtime bits that
  cannot be serialized -- cancel callbacks, the cancel signal shared with a
  render worker, the throttling bookkeeping -- stay in this process, keyed by
  job id.

  Every state change also publishes the same `:export-update` message the
  exporter has always published, so websocket clients keep working unchanged."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.jobs.store :as store]
   [app.redis :as redis]
   [promesa.core :as p]))

(l/set-level! :debug)

;; A large export reports progress per object; persisting each one would be
;; hundreds of writes for information nobody reads at that resolution.
(def ^:private progress-throttle-ms 250)

(def ^:private terminal-states #{"ended" "error" "cancelled"})

(defonce ^:private registry (atom {}))

(defn- now-ms
  []
  (inst-ms (ct/now)))

(defn- runtime
  [job-id]
  (get @registry (str job-id)))

(defn lookup
  "The live record of a job running in this process, or nil."
  [job-id]
  (:job (runtime job-id)))

(defn fetch
  "The job record from the shared store."
  [job-id]
  (store/fetch job-id))

(defn- publish!
  [{:keys [id profile-id resource-id state done total name filename mtype
           resource-uri error]}]
  (redis/pub! (redis/->tenant-key (str profile-id))
              (d/without-nils
               {:type :export-update
                :job-id id
                :resource-id resource-id
                :status state
                :done done
                :total total
                :name name
                :filename filename
                :mtype mtype
                :resource-uri resource-uri
                :cause error})))

(defn- store-job!
  [job]
  (swap! registry update (str (:id job)) assoc :job job)
  job)

(defn create!
  "Builds a queued job and persists it. `run-fn` is a 1-arg fn of the job that
  performs the export and returns a promise; the caller decides when to run
  it."
  [{:keys [profile-id cmd backend total name resource-id]} run-fn]
  (let [job {:id (uuid/next)
             :profile-id profile-id
             :cmd cmd
             :backend backend
             :state "queued"
             :done 0
             :total total
             :name name
             :resource-id resource-id
             :created-at (now-ms)}]
    (swap! registry assoc (str (:id job)) {:job job :run-fn run-fn :cancelled? false})
    (->> (store/persist! job)
         (p/fmap (constantly job)))))

(defn run-fn
  [job-id]
  (:run-fn (runtime job-id)))

(defn cancelled?
  [job-id]
  (boolean (:cancelled? (runtime job-id))))

(defn terminal?
  [job]
  (contains? terminal-states (:state job)))

(defn check-cancelled!
  "Raises when the job has been cancelled. Called as each render's turn comes
  up, so a cancellation stops the ones that have not started yet."
  [job]
  (when (cancelled? (:id job))
    (ex/raise :type :internal
              :code :job-cancelled
              :hint "export job was cancelled")))

(defn cancel-signal
  "Int32Array over a SharedArrayBuffer, readable from a worker thread: 0 while
  the job is live, 1 once it has been cancelled. Nil once the job has been
  released -- writing the signal back would leave an entry for a settled job in
  the registry that nothing would ever remove."
  [job-id]
  (let [k (str job-id)]
    (when-let [rt (get @registry k)]
      (or (:cancel-signal rt)
          (let [signal (js/Int32Array. (js/SharedArrayBuffer. 4))]
            (swap! registry update k (fn [rt] (some-> rt (assoc :cancel-signal signal))))
            (when (cancelled? job-id)
              (js/Atomics.store signal 0 1))
            signal)))))

(defn on-cancel
  "Registers a callback used to abort the job's in-flight work (terminating a
  render worker). A job fans out over several renders, so callbacks
  accumulate. One registered for a job that already settled is dropped: keeping
  it would revive that job's registry entry for good."
  [job-id f]
  (swap! registry update (str job-id)
         (fn [rt] (some-> rt (update :cancel-fns (fnil conj []) f)))))

(defn release!
  "Drops the runtime entry once the job settled. The persisted record stays
  until its TTL."
  [job-id]
  (swap! registry dissoc (str job-id)))

(defn- live
  "The job as the lifecycle last left it, or nil once it settled. Callers hold
  the snapshot handed to them when their work started; writing that back would
  resurrect a failed export as running and drop the error with it."
  [job]
  (when-let [current (:job (runtime (:id job)))]
    (when-not (terminal? current)
      current)))

(defn- persist-and-publish!
  [job]
  (store-job! job)
  (publish! job)
  (store/persist! job))

(defn transition!
  "Moves the job on. The first terminal state wins: anything arriving after it
  is dropped, so a late failure cannot overwrite a cancellation, nor a straggler
  overwrite either."
  [job data]
  (if-let [job (live job)]
    (persist-and-publish! (merge job data))
    (p/resolved job)))

(defn start!
  [job]
  (transition! job {:state "running" :started-at (now-ms)}))

(defn progress!
  "Reports `done` objects completed. Writes are throttled, so the caller need
  not care how often it calls this."
  [{:keys [id] :as job} done]
  (if-let [job (live job)]
    (let [k       (str id)
          now     (now-ms)
          last    (:last-progress-ms (runtime id) 0)
          job     (assoc job :done done)
          write?  (>= (- now last) progress-throttle-ms)]
      (store-job! job)
      (if write?
        (do
          (swap! registry update k assoc :last-progress-ms now)
          (persist-and-publish! job))
        (p/resolved job)))
    (p/resolved job)))

(defn complete!
  [job {:keys [uri filename mtype size] :as _resource}]
  (transition! job {:state "ended"
                    :ended-at (now-ms)
                    :done (:total job)
                    :resource-uri uri
                    :filename filename
                    :mtype mtype
                    :size size}))

(defn fail!
  [job cause]
  (l/error :hint "export job failed" :job-id (str (:id job)) :cause cause)
  (transition! job {:state "error"
                    :ended-at (now-ms)
                    :error (ex-message cause)}))

(defn- cancel-local!
  [job-id]
  (let [k    (str job-id)
        rt   (get @registry k)
        job  (:job rt)]
    (if (or (nil? job) (terminal? job))
      (p/resolved job)
      (do
        (swap! registry update k (fn [rt] (some-> rt (assoc :cancelled? true))))
        (when-let [signal (:cancel-signal rt)]
          (js/Atomics.store signal 0 1))
        ;; Recorded before the callbacks run, not after: one of them
        ;; (`scheduler/drop-queued!`) releases the job, and `transition!` on a
        ;; released job is a no-op, so a queued job would keep claiming to be
        ;; queued in the store and never publish its `cancelled` update.
        (let [result (transition! job {:state "cancelled" :ended-at (now-ms)})]
          (doseq [f (:cancel-fns rt)]
            (try
              (f)
              (catch :default cause
                (l/warn :hint "error on job cancel callback" :job-id k :cause cause))))
          result)))))

(defn cancel!
  "Cancels a job. One this process does not own is broadcast over the cancel
  topic, so whoever runs it acts on it. Idempotent."
  [job-id]
  (if (some? (runtime job-id))
    (cancel-local! job-id)
    (->> (fetch job-id)
         (p/mcat (fn [job]
                   (cond
                     (nil? job)     (p/resolved nil)
                     (terminal? job) (p/resolved job)
                     :else          (p/do
                                      (store/request-cancel! (:id job))
                                      job)))))))

(defn- clean-abandoned!
  "Marks every job left mid-flight by a previous process as cancelled.

  A queue and its running jobs live in the memory of the process that owns
  them, so nothing in flight when it died can be resumed; without this the
  record would keep claiming to be running until its TTL expires.

  NOTE: the store cannot tell whose jobs are whose, so with more than one
  exporter behind a load balancer this would also cancel a sibling's running
  jobs. Single-instance deployments only."
  []
  (->> (store/fetch-all)
       (p/mcat (fn [jobs]
                 (let [abandoned (remove terminal? jobs)]
                   (when (seq abandoned)
                     (l/warn :hint "cancelling jobs abandoned by a previous process"
                             :count (count abandoned)))
                   (->> abandoned
                        (map (fn [job]
                               (store/persist! (assoc job
                                                      :state "cancelled"
                                                      :interrupted true
                                                      :ended-at (now-ms)))))
                        (p/all)))))
       (p/fmap (fn [result] (count result)))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to clean abandoned jobs" :cause cause)
                 (p/resolved 0)))))

(defn init
  []
  (store/on-cancel-request
   (fn [job-id]
     (when (some? (runtime job-id))
       (l/info :hint "remote cancel request" :job-id job-id)
       (cancel-local! job-id))))
  (clean-abandoned!))

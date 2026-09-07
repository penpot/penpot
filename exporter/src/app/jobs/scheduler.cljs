;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs.scheduler
  "Admission control for export jobs.

  Limits concurrent jobs and rejects work rather than allowing an unbounded backlog.
  Queue order is FIFO, except jobs whose profile is already at its cap are skipped,
  as are headless jobs once every render worker is busy."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.jobs :as jobs]
   [app.jobs.utils :as job.utils]
   [app.wasm.pool :as pool]
   [promesa.core :as p]))

(l/set-level! :debug)

(defonce ^:private state
  (atom {:running {}   ;; job-id -> {:profile-id :headless?}
         :queue []}))  ;; vector of {:job :resolve :reject}

(defn- max-concurrent [] (cf/get :exporter-max-concurrent-jobs 4))
(defn- max-per-profile [] (cf/get :exporter-max-jobs-per-profile 2))
(defn- max-queued [] (cf/get :exporter-queue-max 64))

(defn- headless?
  [job]
  (= "wasm" (:backend job)))

(defn- running-for
  [{:keys [running]} profile-id]
  (count (filter #(= profile-id (:profile-id %)) (vals running))))

(defn- running-headless
  [{:keys [running]}]
  (count (filter :headless? (vals running))))

(defn- eligible?
  [state job]
  (and (< (count (:running state)) (max-concurrent))
       (< (running-for state (:profile-id job)) (max-per-profile))
       ;; A headless job holds one render worker for its whole run, so admitting
       ;; more of them than there are workers would only move the wait inside
       ;; the pool, with the job already reporting itself as running.
       (or (not (headless? job))
           (< (running-headless state) (pool/capacity)))))

(declare ^:private pump!)

(defn- finish!
  [job-id]
  (swap! state update :running dissoc (str job-id))
  (jobs/release! job-id)
  (job.utils/release! job-id)
  (pump!))

(defn- execute!
  [{:keys [id profile-id] :as job}]
  (swap! state update :running assoc (str id) {:profile-id profile-id
                                               :headless? (headless? job)})
  (if (jobs/cancelled? id)
    (do (finish! id)
        (p/resolved job))
    (let [run-fn (jobs/run-fn id)]
      (->> (p/do (jobs/start! job))
           (p/mcat (fn [job] (p/do (run-fn job))))
           (p/fnly (fn [_ _] (finish! id)))))))

(defn- drop-queued!
  "Removes a queued job and settles its promise, freeing its queue slot on cancellation."
  [job-id]
  (let [entry (volatile! nil)]
    (swap! state (fn [state]
                   (let [queue (:queue state)
                         idx   (->> (map-indexed vector queue)
                                    (some (fn [[idx entry]]
                                            (when (= (str job-id) (str (-> entry :job :id)))
                                              idx))))]
                     (if idx
                       (do (vreset! entry (nth queue idx))
                           (assoc state :queue (into (subvec queue 0 idx) (subvec queue (inc idx)))))
                       state))))
    (when-let [{:keys [resolve]} @entry]
      (jobs/release! job-id)
      (job.utils/release! job-id)
      (resolve nil))))

(defn- take-eligible
  "Pops the first queued entry that can run now, or nil."
  [state]
  (let [queue (:queue state)
        idx   (->> (map-indexed vector queue)
                   (some (fn [[idx entry]]
                           (when (eligible? state (:job entry))
                             idx))))]
    (when idx
      [(assoc state :queue (into (subvec queue 0 idx) (subvec queue (inc idx))))
       (nth queue idx)])))

(defn- pump!
  []
  (loop []
    (let [entry (volatile! nil)]
      (swap! state (fn [state]
                     (if-let [[next-state next-entry] (take-eligible state)]
                       (do (vreset! entry next-entry) next-state)
                       (do (vreset! entry nil) state))))
      (when-let [{:keys [job resolve reject]} @entry]
        (-> (execute! job)
            (p/then resolve)
            (p/catch reject))
        (recur)))))

(defn submit!
  "Registers `job` for execution. Returns a promise of the job's result, which
  resolves when the export actually finishes; callers that only need the handle
  can ignore it. Raises when the exporter is saturated."
  [job]
  (let [resolve* (volatile! nil)
        reject*  (volatile! nil)
        pending  (p/create (fn [resolve reject]
                             (vreset! resolve* resolve)
                             (vreset! reject* reject)))]
    (if (eligible? @state job)
      (-> (execute! job)
          (p/then @resolve*)
          (p/catch @reject*))

      (let [queued (volatile! false)]
        (swap! state (fn [state]
                       (if (< (count (:queue state)) (max-queued))
                         (do (vreset! queued true)
                             (update state :queue conj {:job job
                                                        :resolve @resolve*
                                                        :reject @reject*}))
                         (do (vreset! queued false) state))))
        (when-not @queued
          (ex/raise :type :validation
                    :code :queue-full
                    :hint "too many queued export jobs"))
        (jobs/on-cancel (:id job) (fn [] (drop-queued! (:id job))))
        (l/dbg :hint "export job queued" :job-id (str (:id job)))))
    pending))

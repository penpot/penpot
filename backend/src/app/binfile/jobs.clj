;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.binfile.jobs
  "The binfile work a job runs.

  A job has no SSE channel, so the `:progress` points that `bf.v1` and
  `bf.v3` emit become `progress` events of the job through
  `jobs/heartbeat`, which throttles them to one per second and drops the
  ones that do not fit. The event keeps only `current` and `stage`: the
  ids the taps carry are not part of the job event contract.

  Between units of work the adapter checks that the job is still active.
  A job that was cancelled, or that already reached a terminal state,
  stops reporting and the run is discarded when it returns, so it never
  completes; an import running inside the transaction of its handler
  rolls back with it."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.exceptions :as ex]
   [app.jobs :as jobs]
   [app.util.events :as events]))

(def ^:private terminal-statuses
  #{"completed" "failed" "cancelled"})

(defn- inactive-status
  "The status of the job when it is no longer active, nil while it is. A
  job row that is already gone counts as inactive: there is nothing left
  to report the work to."
  [cfg context]
  (let [job (jobs/get-job cfg (:id context))]
    (if (nil? job)
      "gone"
      (let [status (:status job)]
        (when (contains? terminal-statuses status)
          status)))))

(defn- raise-cancelled
  [context status]
  (ex/raise :type :validation
            :code :job-cancelled
            :hint "the job is no longer active"
            :job-id (:id context)
            :status status))

(defn check-active
  "Stop at a checkpoint when the job is no longer active.

  The status is read from the row, because `heartbeat` returning zero
  cannot tell a throttled report from a terminal job."
  [cfg context]
  (when-let [status (inactive-status cfg context)]
    (raise-cancelled context status)))

(defn- progress-event
  "The job event a progress tap becomes: how many units the core has
  reported and which section it is on, and nothing else."
  [current section]
  (cond-> {:current current}
    (some? section)
    (assoc :stage (name section))))

(defn- with-progress
  "Run `f` with the progress taps of the core turned into job events.

  The listener runs on its own thread, so the job id is taken from the
  context and passed explicitly: the dynamic binding the runner installs
  around the handler is not visible from another thread.

  A cancellation the listener detects is kept in `stopped` and raised
  here, once the run has returned: the work cannot be interrupted in the
  middle of a unit, but it is never completed and the transaction of the
  caller rolls back."
  [cfg context f]
  (let [counter  (volatile! 0)
        stopped  (volatile! nil)
        job-id   (:id context)
        on-event (fn [[type data]]
                   (when (= :progress type)
                     (if-let [status (inactive-status cfg context)]
                       (do
                         (vreset! stopped status)
                         (raise-cancelled context status))
                       (do
                         (vswap! counter inc)
                         (jobs/heartbeat cfg
                                         :job-id job-id
                                         :progress (progress-event @counter
                                                                   (:section data)))))))
        result   (events/run-with! f on-event)]
    (when-let [status @stopped]
      (raise-cancelled context status))
    result))

(defn export-files
  "Export a set of files into `output` as part of a job, reporting
  progress as job events."
  [cfg context {:keys [ids export-type output]}]
  (check-active cfg context)
  (with-progress cfg context
    #(bf.v3/export-files! (bfc/export-cfg cfg {:ids ids
                                               :export-type export-type})
                          output)))

(defn import-files
  "Import the package at `input` as part of a job, reporting progress as
  job events. The package was read from the resource of the job, so the
  caller resolves the destination team and the adapter only fixes what
  the legacy RPC and a job must share.

  Both formats answer with the same shape: the version 1 reader returns
  the set of files it created, and the version 3 one returns a map that
  already carries them together with the resolution of the libraries."
  [cfg context {:keys [profile-id project-id team name input version]}]
  (check-active cfg context)
  (let [cfg (bfc/import-cfg cfg {:profile-id profile-id
                                 :project-id project-id
                                 :team team
                                 :name name
                                 :input input})]
    (with-progress cfg context
      #(case (int version)
         1 {:file-ids (vec (bf.v1/import-files! cfg))}
         3 (bf.v3/import-files! cfg)
         (ex/raise :type :validation
                   :code :unsupported-version
                   :hint "unsupported binfile version"
                   :version version)))))

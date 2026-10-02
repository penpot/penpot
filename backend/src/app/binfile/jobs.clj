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
  ones that do not fit. The adapter turns a tap into a self-contained
  milestone: a `:stage` of a single vocabulary (the two formats name the
  same work differently) and the counters of the tap, with the file in
  course as context. The ids the taps carry are not part of the job event
  contract.

  The adapter checks that the job is still active before it starts and
  once the run returns. The progress listener cannot stop the core, so a
  job that was cancelled, or that already reached a terminal state, is
  discarded at the end: it never completes, and the transaction of its
  handler rolls back with it.

  The price of that design is that a cancelled job runs to the end before
  it is discarded: stopping it in the middle would need the core to ask
  between units whether it should stop, which the taps of the current
  reader cannot do from another thread."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.db :as db]
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
  "Raise when the job is no longer active.

  The status is read from the row, because `heartbeat` returning zero
  cannot tell a throttled report from a terminal job."
  [cfg context]
  (when-let [status (inactive-status cfg context)]
    (raise-cancelled context status)))

(def ^:private stage-of
  "The stage a tap section becomes: one vocabulary for the two formats, so
  a client resolves each stage once. The counter of the activity is named
  after its own stage, which is what lets a client render
  `counters[stage]` without a table of equivalences."
  {:file            :files
   :page            :pages
   :media           :media
   :thumbnail       :thumbnails
   :thumbnails      :thumbnails
   :component       :components
   :color           :colors
   :typography      :typographies
   :tokens-lib      :tokens
   :tokens-status   :tokens
   :storage-object  :storage-objects
   :storage-objects :storage-objects
   :relations       :relations
   :manifest        :manifest
   :v1/metadata     :metadata
   :v1/files        :files
   :v1/rels         :relations
   :v1/sobjects     :storage-objects})

(defn- stage
  "The stage of a tap section; a section without a name of its own keeps
  it, so a new tap is never dropped from the stream."
  [section]
  (get stage-of section section))

(defn- counter
  "The counter a tap reports, when it reports one: a tap that only marks a
  point of the run has no units to count."
  [{:keys [current total]}]
  (when (some? current)
    (cond-> {:current current}
      (some? total)
      (assoc :total total))))

(defn- milestone
  "The milestone a tap becomes: what the job is doing now and the counters
  the tap knows, with the file in course as the outer context of the
  stages inside it.

  Each milestone is self-contained, so a client that misses one (the
  substrate throttles the events) still renders the truth from the next."
  [file-counter {:keys [section] :as tap}]
  (let [stage (stage section)
        own   (counter tap)
        files (if (= :files stage) (or own file-counter) file-counter)]
    (cond-> {:stage stage}
      (or (some? files) (some? own))
      (assoc :counters (cond-> (if (some? files) {:files files} {})
                         (some? own) (assoc stage own))))))

(defn- with-progress
  "Run `f` with the progress taps of the core turned into milestones of the
  job.

  Every tap is reported: the substrate throttles the durable events to one
  per second, and a milestone that does not fit loses no information
  because the next one carries the same state.

  The listener runs on a thread of its own, so it only reports progress:
  it cannot stop the core, and an exception raised there would be
  swallowed. A job that is no longer active is noticed once the run
  returns, which is what makes the transaction of the caller roll back."
  [cfg context f]
  (let [job-id   (:id context)
        file     (volatile! nil)
        on-event (fn [[type data]]
                   (when (= :progress type)
                     (let [milestone (milestone @file data)]
                       (when-let [files (get-in milestone [:counters :files])]
                         (vreset! file files))
                       (jobs/heartbeat cfg
                                       :job-id job-id
                                       :progress milestone))))
        result   (events/run-with! f on-event)]
    (check-active cfg context)
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

(defn- touch-project
  "Mark the destination project as modified.

  An import adds files, and the triggers of the schema only touch the
  project on an update, so a project that just received files keeps its
  old date and stays where it was in the list."
  [cfg project-id]
  (db/update! cfg :project
              {:modified-at (ct/now)}
              {:id project-id}
              {::db/return-keys false}))

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
      #(let [result (case (int version)
                      1 {:file-ids (vec (bf.v1/import-files! cfg))}
                      3 (bf.v3/import-files! cfg)
                      (ex/raise :type :validation
                                :code :unsupported-version
                                :hint "unsupported binfile version"
                                :version version))]
         (touch-project cfg project-id)
         result))))

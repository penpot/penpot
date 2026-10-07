;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.binfile.jobs
  "The binfile work a job runs.

  A job has no SSE channel, so the `:progress` points that `bf.v1` and
  `bf.v3` emit become `progress` events of the job through
  `jobs/heartbeat`, which throttles them and drops the ones that do not
  fit. The adapter turns a tap into a self-contained milestone: a `:stage`
  of a single vocabulary (the two formats name the same work differently)
  and the counters the tap carries, with the outer scopes in course as
  context. The reader puts the outer counters in every tap inside them,
  so the adapter holds no state between taps. The ids the taps carry are
  not part of the job event contract.

  The adapter checks that the job is still active before it starts, and
  the beat checks it on every write: a job that was cancelled, or that
  already reached a terminal state, raises an `:interrupt` right there on
  the run thread, so the work stops at its next event point and the
  transaction of its handler rolls back with it. A run whose last tap
  happened before the interruption is discarded once it returns, which is
  what makes that transaction roll back too."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.util.events :as events]))

(defn check-active
  "Raise an `:interrupt` when the job is no longer active.

  The substrate owns both the check and the error; this is the adapter's
  entry point to it, used by the two handlers before their run and by the
  tests."
  [cfg context]
  (jobs/check-active cfg (:id context)))

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
  the tap carries, with the outer scopes in course as context.

  The tap of a scope carries its outer counters, so the milestone is
  self-contained and the adapter remembers nothing between taps: a client
  that misses one (the substrate throttles the events) still renders the
  truth from the next."
  [{:keys [section outer-counters] :as tap}]
  (let [stage (stage section)
        own   (counter tap)]
    (cond-> {:stage stage}
      (or (some? outer-counters) (some? own))
      (assoc :counters (cond-> (or outer-counters {})
                         (some? own) (assoc stage own))))))

(defn- with-progress
  "Run `f` with the progress taps of the core turned into milestones of the
  job.

  Every tap is reported: the substrate throttles the durable events, and
  a milestone that does not fit loses no information because the next one
  carries the same state.

  The taps run on this same thread: the sink calls the heartbeat inline,
  so an `:interrupt` it raises aborts the core right there, inside the
  transaction of its handler, which rolls back."
  [cfg context f]
  (let [job-id (:id context)
        sink   (fn [[type data]]
                 (when (= :progress type)
                   (jobs/heartbeat cfg
                                   :job-id job-id
                                   :progress (milestone data))))]
    (binding [events/*sink* sink]
      (let [result (f)]
        (check-active cfg context)
        result))))

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

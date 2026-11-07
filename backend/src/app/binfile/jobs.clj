;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.binfile.jobs
  "The progress bridge of the binfile jobs.

  A job has no SSE channel, so the `:progress` points that `bf.v1` and
  `bf.v3` emit become `progress` events of the job through
  `jobs/heartbeat`, which throttles them and drops the ones that do not
  fit. The adapter turns a tap into a self-contained milestone: a `:stage`
  of a single vocabulary (the two formats name the same work differently)
  and the counters the tap carries, with the outer scopes in course as
  context. The reader puts the outer counters in every tap inside them,
  so the adapter holds no state between taps. The ids the taps carry are
  not part of the job event contract.

  The handlers in `app.tasks.export-binfile` and
  `app.tasks.import-binfile` run the core through `run` and
  guard it with `check-active`: a job that was cancelled, or that
  already reached a terminal state, raises an `:interrupt` right there on
  the run thread, so the work stops at its next event point and the
  transaction of its handler rolls back with it. A run whose last tap
  happened before the interruption is discarded once it returns, which is
  what makes that transaction roll back too."
  (:require
   [app.jobs :as jobs]
   [app.util.events :as events]))

(defn check-active
  "Raise an `:interrupt` when the job is no longer active.

  The substrate owns both the check and the error; this is the adapter's
  entry point to it, used by the two handlers before their run and by the
  tests. Answers with the cfg, so a handler can thread it."
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

(defn run
  "Run `f` with the progress taps of the core turned into milestones of the
  job.

  `f` is called with the cfg, so the adapter forwards what it receives
  and a caller can pass the core directly.

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
      (let [result (f cfg)]
        (check-active cfg context)
        result))))

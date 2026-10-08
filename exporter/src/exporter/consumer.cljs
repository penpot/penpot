;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.consumer
  "The runner of the `:export-assets` job.

  Runs one export end to end: the render plan of
  `exporter.consumer.plan` (the name transducers and the partitioning
  the export surfaces always kept), the pdf assembly of the frames
  run, the beats shaped by the progress contract (`:preparing`,
  `:rendering`, `:packaging`, with the `objects` and `pages` counters
  of every partition) and one settle: a `complete-job` multipart call
  that stores the artifact and closes the session the backend minted
  in the same step. The render runs as the job's owner through the
  session `create-job-session` opened.

  Cancellation arrives through the beats: a `skip` answer from
  `report-job-progress` says the job can no longer hear the worker
  (cancelled, aborted, settled). Between units of work the runner
  raises, with the same check the drivers run; inside a render the
  lease holder aborts its own worker. The local registry entry is
  released on every settle — unlike the legacy runner, which only
  released the temp files and leaked one entry per job.

  The run never rejects on an export failure: whatever goes wrong
  reaches the backend as `fail-job` (with the session id, so the render
  session does not outlive the settle). Only bugs of this process
  reject. There are no promesa chains in this namespace."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.handlers.resources :as rsc]
   [app.jobs.utils :as job.utils]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [exporter.consumer.api :as api]
   [exporter.consumer.plan :as plan]
   [exporter.jobs :as jobs]
   [exporter.renderer :as renderer]))

(def ^:private report-throttle-ms 250)
(def ^:private watchdog-interval-ms 1000)

;; ---- PROGRESS AND CANCEL (the milestones of the contract)

(defn- start-beats
  "The reporter of one run's beats.

  The beat reports the stage and the counters and asks the backend to
  go on; a `skip` answer means nobody is listening anymore — the job
  was cancelled or settled elsewhere — and the export must stop: the
  reporter fires `on-cancel` once (what marks the local flag the
  checks read; the in-flight render aborts itself) and returns the
  answer, so the runner sees it at the next unit of work and raises.

  Between forced beats the reporter throttles the way the legacy export
  did, since hundreds of objects would otherwise be hundreds of HTTP
  calls for information nobody reads at that resolution. An errored
  beat answers `run`: the export itself must not fail because the beat
  could not fly, and the backend that is unreachable has the lease to
  decide what happens to the row. The beat never rejects."
  [job-id on-cancel]
  (let [last-beat (atom 0)
        last      (atom nil)
        fired     (atom false)
        check     (fn [answer]
                    (when (= :skip (:action answer))
                      (when (compare-and-set! fired false true)
                        (l/info :hint "job cancelled by backend"
                                :job-id (str job-id))
                        (on-cancel)))
                    answer)
        report    (^:async fn [stage counters]
                    (try
                      (check (await (api/report-job-progress job-id {:stage stage
                                                                     :counters counters})))
                      (catch :default cause
                        (l/warn :hint "beat failed to land"
                                :job-id (str job-id)
                                :cause cause)
                        {:action :run})))
        beat      (^:async fn [stage counters & {:keys [force?]}]
                    (let [now  (js/Date.now)
                          now? (or force?
                                   (>= (- now @last-beat) report-throttle-ms))]
                      (if now?
                        (do (reset! last-beat now)
                            (reset! last {:stage stage :counters counters})
                            (await (report stage counters)))
                        {:action :run :throttled? true})))]
    {:beat beat :last last}))

;; ---- THE WATCHDOG

(defn- with-watchdog
  "The forced beats of one run: one every `watchdog-interval-ms`, and
  they repeat the last report the run made, so a cancellation that
  lands mid-render (no object landing, no unit of work ending) is seen
  within that interval. `stop` clears it once the run settles."
  [beat last]
  (let [timer (js/setInterval
               #(beat (:stage @last) (:counters @last) :force? true)
               watchdog-interval-ms)]
    (fn stop [] (js/clearInterval timer))))

(defn- counter
  "One counter of the vocabulary: `kind` is `objects` or `pages`."
  [kind current total]
  {kind {:current current :total total}})

;; ---- THE PARAMS

(defn- frame-item?
  "The frames export names pages of a file without a `type`: the pdf
  of their pages. A shape item always names its type."
  [item]
  (nil? (:type item)))

(defn- ->uuid
  "One id of the claim into what the renderer wants: the claim carries
  the job row JSON, every id a string, while the render spec only
  takes uuid objects. Values that already are uuids (tests, callers in
  process) pass through."
  [v]
  (if (string? v) (uuid/uuid v) v))

(defn- normalize-items
  "The items the claim delivered are the plain JSON the job row holds:
  the type arrives as text and the renderer dispatches on the keywords
  the legacy surface received; every id arrives as text and the render
  spec only takes uuid objects. The frames items are pages, not typed
  shapes: they name the pdf of a page each."
  [items]
  (->> items
       (mapv (fn [item]
               (cond-> item
                 (string? (:type item))      (update :type keyword)
                 (string? (:file-id item))   (update :file-id ->uuid)
                 (string? (:page-id item))   (update :page-id ->uuid)
                 (string? (:object-id item)) (update :object-id ->uuid)
                 (string? (:share-id item))  (update :share-id ->uuid)
                 (nil? (:type item))         (assoc :type :pdf :scale 1 :suffix ""))))))

(defn- make-plan
  "The render plan the legacy handlers prepare: the same transducers of
  names and partition size they served the old surface with, the same
  `single?` rule (one prepared export with one object, not forced),
  and the artifact the whole run fills."
  [job-id token {:keys [exports force-multiple name skip-children is-wasm]}]
  (let [items     (normalize-items exports)
        frames?   (boolean (every? frame-item? items))
        prepared  (plan/prepare-exports items token is-wasm)
        single?   (and (not frames?)
                       (not (true? force-multiple))
                       (= 1 (count prepared))
                       (= 1 (count (-> prepared first :objects))))
        kind      (cond
                    single? (-> prepared first :type)
                    frames? :pdf
                    :else   :zip)]
    {:frames?      frames?
     :single?      single?
     :job-id       (->uuid job-id)
     :prepared     prepared
     :total        (plan/count-objects prepared)
     :skip-children skip-children
     :is-wasm      (boolean is-wasm)
     :counter-kind (if frames? :pages :objects)
     :resource     (rsc/create kind
                               (or name (-> prepared first :name)))}))

;; ---- THE RUNNERS

(defn- check-beat
  "A forced beat after a unit of work: a `skip` there is a cancelled
  job, the same exception the legacy surfaces raised, so everything
  below unwinds."
  [answer]
  (when (= :skip (:action answer))
    (throw (ex/error :type :internal
                     :code :job-cancelled
                     :hint "export job was cancelled")))
  answer)

(defn- ^:async run-single
  "One render, one object: the artifact IS the object, moved into the
  resource path the multipart settle will name."
  [cfg beat plan check-cancelled]
  (let [{:keys [job-id resource counter-kind]} plan
        export   (-> plan :prepared first)
        object   (atom nil)]
    (job.utils/track job-id (:path resource))
    (check-beat (await (beat :rendering (counter counter-kind 0 1) :force? true)))
    (await (renderer/render cfg
                            :exports [(assoc export
                                             :job-id job-id
                                             :skip-children (:skip-children plan))]
                            :on-object (fn [obj]
                                         (reset! object obj)
                                         (job.utils/track job-id (:path obj))
                                         nil)
                            :check-cancelled check-cancelled))
    (await (sh/move (:path @object) (:path resource)))
    (check-beat (await (beat :packaging (counter counter-kind 1 1) :force? true)))
    resource))

(defn- ^:async run-multiple
  "The multi-object render: every prepared export renders through the
  one batch of the facade, appending each object to the zip as it
  lands, and the progress counts what has landed so far. Any zipping
  error surfaces after the renders, so the failures of the writer do
  not compete with the ones of the render."
  [cfg beat plan check-cancelled]
  (let [{:keys [job-id resource prepared total counter-kind]} plan
        failure  (volatile! nil)
        rendered (volatile! 0)
        zip      (rsc/create-zip :resource resource
                                 :on-error (fn [cause] (vreset! failure cause))
                                 :on-progress (fn [_] nil))
        append   (fn [{:keys [filename path]}]
                   (check-cancelled)
                   (job.utils/track job-id path)
                   (vswap! rendered inc)
                   (beat :rendering
                         (counter counter-kind @rendered total))
                   (rsc/add-to-zip zip path
                                   (str/replace filename
                                                plan/sanitize-file-regex
                                                "_"))
                   nil)]
    (check-beat (await (beat :rendering (counter counter-kind 0 total) :force? true)))
    (await (renderer/render cfg
                            :exports (mapv #(assoc % :job-id job-id) prepared)
                            :on-object append
                            :check-cancelled check-cancelled))
    (when-let [cause @failure]
      (throw cause))
    (await (rsc/close-zip zip))
    (check-beat (await (beat :packaging (counter counter-kind total total) :force? true)))
    resource))

(defn- ^:async join-pdf
  "The pages render one file per page; `pdfunite` stitches one pdf out
  of them all, the way the legacy export did."
  [job-id file-id paths]
  (let [path (job.utils/track job-id
                              (sh/tempfile :prefix (str/concat "penpot.pdfunite." file-id ".")
                                           :suffix ".pdf"))]
    (await (sh/run-cmd "pdfunite" (into [] (concat (vec paths) [path]))))
    path))

(defn- ^:async run-frames
  "The frames render: a file per page, joined into the pdf of the file
  once every page has landed."
  [cfg beat plan check-cancelled]
  (let [{:keys [job-id resource prepared total]} plan
        file-id   (-> prepared first :file-id)
        paths     (volatile! [])
        rendered  (volatile! 0)
        on-object (fn [{:keys [path]}]
                    (check-cancelled)
                    (job.utils/track job-id path)
                    (vswap! paths conj path)
                    (vswap! rendered inc)
                    (beat :rendering
                          (counter :pages @rendered total))
                    nil)]
    (check-beat (await (beat :rendering (counter :pages 0 total) :force? true)))
    (await (renderer/render cfg
                            :exports (mapv #(assoc % :job-id job-id
                                                   :is-wasm (:is-wasm plan))
                                           prepared)
                            :on-object on-object
                            :check-cancelled check-cancelled))
    (let [joined (await (join-pdf job-id file-id @paths))]
      (await (sh/move joined (:path resource)))
      (check-beat (await (beat :packaging (counter :pages total total) :force? true)))
      resource)))

(defn- run-prepared
  [cfg beat check-cancelled plan]
  (cond
    (:single? plan) (run-single cfg beat plan check-cancelled)
    (:frames? plan) (run-frames cfg beat plan check-cancelled)
    :else           (run-multiple cfg beat plan check-cancelled)))

;; ---- THE SETTLE

(defn- failure-report
  "The error shape the backend stores for `job.error`: type and code
  keywords, hint the human message. A cause that is not a penpot error
  becomes an internal one, with only its message as the hint."
  [cause]
  (let [data (ex-data cause)]
    {:type (or (:type data) :internal)
     :code (or (:code data) :export-failed)
     :hint (or (:hint data) (ex-message cause) "export failed")}))

(defn cancelled?
  "Whether `cause` ends a run the user asked to end: a cancelled
  export is a normal ending, reported and logged as one, never as a
  failure with a trace."
  [cause]
  (= :job-cancelled (:code (ex-data cause))))

(defn- ^:async settle-failure
  "Settles the job as failed, after releasing the temp files the run
  owns. The settle never rejects: if the failure cannot reach the
  backend, the row keeps running until the lease GC turns it `aborted`,
  which is the honest state for a worker that cannot talk."
  [job-id session cause]
  (await (job.utils/release job-id))
  (jobs/release job-id)
  (try
    (await (api/fail-job job-id (failure-report cause)
                         :session-id (some-> @session :session-id)))
    (catch :default settle-cause
      (l/warn :hint "unable to settle the failed job"
              :job-id job-id :cause settle-cause))))

(defn ^:async run-export
  "Runs one claimed export to its settle: first breath, render
  session, plan, render batch, multipart complete. `cfg` is the render
  config (a view over the running system); the claim names the job and
  `params` carries the frozen job params."
  [cfg {:keys [job-id]} params]
  (let [session         (atom nil)
        stop-watchdog   (atom nil)
        _               (jobs/register job-id)
        stop-cmd        (fn [] (jobs/mark-cancelled job-id))
        {:keys [beat last]} (start-beats job-id stop-cmd)
        check-cancelled (jobs/check-cancelled job-id)]
    (try
      (reset! stop-watchdog (with-watchdog beat last))
      ;; the first breath right after the claim, before the
      ;; session roundtrip: the widget moves from queued to
      ;; exporting on it, and a cancel that landed meanwhile
      ;; raises here instead of after wasted work
      (check-beat (await (beat :preparing {} :force? true)))
      (let [session' (await (api/create-job-session job-id))]
        (reset! session session')
        (l/info :hint "render session minted"
                :job-id (str job-id))
        (let [plan     (make-plan job-id (:session-token session') params)
              resource (await (run-prepared cfg beat check-cancelled plan))]
          ;; the watchdog outlives the render, so the run stops it on
          ;; the way out: a run that ends badly stops beating too, or
          ;; its interval would knock on a dead job forever, one HTTP
          ;; call a second that only ever answers skip
          (@stop-watchdog)
          (let [artifact {:path     (str (:path resource))
                          :filename (:filename resource)
                          :mtype    (:mtype resource)}]
            (await (api/complete-job-with-artifact
                    {:job-id job-id :session-id (:session-id session')}
                    artifact))
            (await (job.utils/release job-id))
            (jobs/release job-id)
            (l/info :hint "export job settled" :job-id (str job-id)
                    :outcome "completed")
            nil)))
      (catch :default cause
        (when-let [stop @stop-watchdog] (stop))
        (if (cancelled? cause)
          (l/info :hint "export job cancelled" :job-id (str job-id))
          (l/error :hint "export job failed" :job-id (str job-id) :cause cause))
        (await (settle-failure job-id session cause))))))

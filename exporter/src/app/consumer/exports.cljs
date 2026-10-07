;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.consumer.exports
  "The runner of the `:export-assets` job.

  Runs one export end to end: the render plan of
  `app.consumer.plan` (the name transducers and the partitioning that
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
  raises, the same `:job-cancelled` the legacy surfaces raised; inside
  a render mid-Skia, the watchdog fires the cancel port the wasm pool
  already serves (terminate of the leased thread).

  The run never rejects on an export failure: whatever goes wrong
  reaches the backend as `fail-job` (with the session id, so the render
  session does not outlive the settle). Only bugs of this process
  reject."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.consumer.api :as api]
   [app.consumer.plan :as plan]
   [app.handlers.resources :as rsc]
   [app.jobs :as jobs]
   [app.jobs.utils :as job.utils]
   [app.renderer :as rd]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private report-throttle-ms 250)
(def ^:private watchdog-interval-ms 1000)

;; ---- PROGRESS AND CANCEL (the milestones of the contract)

(defn- start-beats!
  "The reporter of one run's beats.

  The beat reports the stage and the counters and asks the backend to
  go on; a `skip` answer means nobody is listening anymore — the job
  was cancelled or settled elsewhere — and the export must stop: the
  reporter fires `on-cancel` once (what terminates the render worker
  the run holds mid-Skia; the thread itself does not listen, only the
  terminate does) and returns the answer, so the runner sees it at the
  next unit of work and raises.

  Between forced beats the reporter throttles the way the legacy export
  did, since hundreds of objects would otherwise be hundreds of HTTP
  calls for information nobody reads at that resolution. An errored
  beat answers `run`: the export itself must not fail because the beat
  could not fly, and the backend that is unreachable has the lease to
  decide what happens to the row."
  [job-id on-cancel]
  (let [last-beat  (atom 0)
        last       (atom nil)
        terminated (atom false)
        check      (fn [answer]
                     (when (= :skip (:action answer))
                       (when (compare-and-set! terminated false true)
                         (l/info :hint "job cancelled by backend"
                                 :job-id (str job-id))
                         (on-cancel)))
                     answer)
        report     (fn [stage counters]
                     (->> (api/report-job-progress job-id
                                                   {:stage stage
                                                    :counters counters})
                          (p/merr (fn [cause]
                                    (l/warn :hint "beat failed to land"
                                            :job-id (str job-id)
                                            :cause cause)
                                    (p/resolved {:action :run})))
                          (p/fmap check)))
        beats!     (fn [stage counters & {:keys [force?]}]
                     (let [now   (js/Date.now)
                           now?  (or force?
                                     (>= (- now @last-beat) report-throttle-ms))]
                       (if now?
                         (do (reset! last-beat now)
                             (reset! last {:stage stage :counters counters})
                             (report stage counters))
                         (p/resolved {:action :run :throttled? true}))))]
    {:beat! beats! :last last}))

;; ---- THE WATCHDOG

(defn- with-watchdog!
  "The forced beats of one run: one every `watchdog-interval-ms`, and
  they repeat the last report the run made, so a cancellation that
  lands mid-render (no object landing, no unit of work ending) is seen
  within that interval. `stop!` clears it once the run settles."
  [beats! last]
  (let [timer (js/setInterval
               #(beats! (:stage @last) (:counters @last) :force? true)
               watchdog-interval-ms)]
    (fn stop! [] (js/clearInterval timer))))

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
    {:frames?       frames?
     :single?       single?
     :job-id        (->uuid job-id)
     :prepared      prepared
     :total         (plan/count-objects prepared)
     :skip-children skip-children
     :is-wasm       (boolean is-wasm)
     :counter-kind  (if frames? :pages :objects)
     :resource      (rsc/create kind
                                (or name (-> prepared first :name)))}))

;; ---- THE CANCEL CHECK

(defn- check-cancelled!
  "The local arm of the cancel: the watchdog's terminate already died
  the in-flight thread; the units of work that follow check the flag
  and unwind without rendering anything else. The backend's row is
  already terminal, so the settle below only reports what happened."
  [cancelled]
  (when @cancelled
    (ex/raise :type :internal
              :code :job-cancelled
              :hint "export job was cancelled")))

;; ---- THE RUNNERS

(defn- check-beat!
  "A forced beat after a unit of work: a `skip` there is a cancelled
  job, the same exception the legacy surfaces raised, so everything
  below unwinds."
  [answer]
  (when (= :skip (:action answer))
    (ex/raise :type :internal
              :code :job-cancelled
              :hint "export job was cancelled"))
  answer)

(defn- run-single!
  "One render, one object: the artifact IS the object, moved into the
  resource path the multipart settle will name."
  [beats! plan]
  (let [{:keys [job-id resource counter-kind]} plan
        export   (-> plan :prepared first)
        object   (atom nil)]
    (job.utils/track! job-id (:path resource))
    (p/let [beat-one (beats! :rendering (counter counter-kind 0 1) :force? true)
            _        (check-beat! beat-one)
            _        (rd/render (assoc export
                                       :job-id        job-id
                                       :skip-children (:skip-children plan))
                                (fn [obj]
                                  (reset! object obj)
                                  (job.utils/track! job-id (:path obj))))
            _        (sh/move! (:path @object) (:path resource))
            beat-end (beats! :packaging (counter counter-kind 1 1) :force? true)
            _        (check-beat! beat-end)]
      resource)))

(defn- run-multiple!
  "The multi-object render: every prepared export renders with the same
  on-object callback, appending each object to the zip as it lands, and
  the progress counts what has landed so far. Any zipping error
  surfaces after the renders, so the failures of the writer do not
  compete with the ones of the render."
  [beats! cancelled plan]
  (let [{:keys [job-id resource prepared total counter-kind]} plan
        failure  (volatile! nil)
        rendered (volatile! 0)
        zip      (rsc/create-zip :resource resource
                                 :on-error (fn [cause] (vreset! failure cause))
                                 :on-progress (fn [_] nil))
        append   (fn [{:keys [filename path]}]
                   (check-cancelled! cancelled)
                   (job.utils/track! job-id path)
                   (vswap! rendered inc)
                   (beats! :rendering
                           (counter counter-kind @rendered total))
                   (rsc/add-to-zip zip path
                                   (str/replace filename
                                                plan/sanitize-file-regex
                                                "_")))]
    (p/let [beat-one (beats! :rendering (counter counter-kind 0 total) :force? true)
            _        (check-beat! beat-one)
            _        (rd/with-scope prepared
                       (fn [scoped-render]
                         (p/all (map (fn [export]
                                       (scoped-render
                                        (assoc export :job-id job-id)
                                        append))
                                     prepared))))
            error    (if-let [cause @failure]
                       (p/rejected cause)
                       (rsc/close-zip zip))
            _        (do error)
            beat-end (beats! :packaging (counter counter-kind total total) :force? true)
            _        (check-beat! beat-end)]
      resource)))

(defn- join-pdf!
  "The pages render one file per page; `pdfunite` stitches one pdf out
  of them all, the way the legacy export did."
  [job-id file-id paths]
  (let [path   (job.utils/track! job-id
                                 (sh/tempfile :prefix (str/concat "penpot.pdfunite." file-id ".")
                                              :suffix ".pdf"))]
    (p/let [_ (sh/run-cmd! "pdfunite" (into [] (concat (vec paths) [path])))]
      path)))

(defn- run-frames!
  "The frames render: a file per page, joined into the pdf of the file
  once every page has landed."
  [beats! cancelled plan]
  (let [{:keys [job-id resource prepared total]} plan
        file-id   (-> prepared first :file-id)
        paths     (volatile! [])
        rendered  (volatile! 0)
        on-object (fn [{:keys [path]}]
                    (check-cancelled! cancelled)
                    (job.utils/track! job-id path)
                    (vswap! paths conj path)
                    (vswap! rendered inc)
                    (beats! :rendering
                            (counter :pages @rendered total)))]
    (p/let [beat-one (beats! :rendering (counter :pages 0 total) :force? true)
            _        (check-beat! beat-one)
            _        (rd/with-scope prepared
                       (fn [scoped-render]
                         (p/all (map (fn [export]
                                       (scoped-render
                                        (assoc export :job-id job-id
                                               :is-wasm (:is-wasm plan))
                                        on-object))
                                     prepared))))
            joined   (join-pdf! job-id file-id @paths)
            _        (sh/move! joined (:path resource))
            beat-end (beats! :packaging (counter :pages total total) :force? true)
            _        (check-beat! beat-end)]
      resource)))

(defn- run-prepared!
  [beats! cancelled plan]
  (cond
    (:single? plan) (run-single!  beats! plan)
    (:frames? plan) (run-frames! beats! cancelled plan)
    :else           (run-multiple! beats! cancelled plan)))

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

(defn- settle-failure!
  "Settles the job as failed, after releasing the temp files the run
  owns. The settle never rejects: if the failure cannot reach the
  backend, the row keeps running until the lease GC turns it `aborted`,
  which is the honest state for a worker that cannot talk."
  [job-id session cause]
  (p/do
    (job.utils/release! job-id)
    (->> (api/fail-job job-id (failure-report cause)
                       :session-id (some-> @session :session-id))
         (p/merr (fn [settle-cause]
                   (l/warn :hint "unable to settle the failed job"
                           :job-id job-id :cause settle-cause))))))

(defn run-export!
  [{:keys [job-id]} params]
  (let [session        (atom nil)
        cancelled      (atom false)
        ;; the watchdog outlives the scope that starts it, so its
        ;; stopper travels in this atom: a run that ends badly stops
        ;; beating too, or its interval would knock on a dead job
        ;; forever, one HTTP call a second that only ever answers skip
        stop-watchdog! (atom nil)
        _              (jobs/register! job-id)
        stop-cmd! (fn []
                    (reset! cancelled true)
                    ;; the hard-cancel of the render the run holds: the
                    ;; `renderer.wasm/with-scope` of the in-flight render
                    ;; registered a terminate callback for this job
                    (jobs/mark-cancelled job-id))]
    (p/catch
     (p/let [beats    (start-beats! job-id stop-cmd!)
             beats!   (:beat! beats)
             _        (reset! stop-watchdog! (with-watchdog! beats! (:last beats)))
             ;; the first breath right after the claim, before the
             ;; session roundtrip: the widget moves from queued to
             ;; exporting on it, and a cancel that landed meanwhile
             ;; raises here instead of after wasted work
             _        (check-beat! (beats! :preparing {} :force? true))
             session' (api/create-job-session job-id)
             _        (reset! session session')
             token    (:session-token session')
             _        (l/info :hint "render session minted"
                              :job-id (str job-id))
             plan      (make-plan job-id token params)
             resource  (run-prepared! beats! cancelled plan)
             _        (@stop-watchdog!)
             artifact  {:path     (str (:path resource))
                        :filename (:filename resource)
                        :mtype    (:mtype resource)}
             _         (api/complete-job-with-artifact
                        {:job-id job-id :session-id (:session-id session')}
                        artifact)
             _         (job.utils/release! job-id)
             _         (l/info :hint "export job settled" :job-id (str job-id)
                               :outcome "completed")]
       nil)
     (fn [cause]
       (when-let [stop @stop-watchdog!] (stop))
       (if (cancelled? cause)
         (l/info :hint "export job cancelled" :job-id (str job-id))
         (l/error :hint "export job failed" :job-id (str job-id) :cause cause))
       (settle-failure! job-id session cause)))))



;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.consumer.exports
  "The runner of the `:export-assets` job.

  Takes the params the job-def froze and transplant the legacy export
  machinery into the jobs substrate: the name transducers and
  partitioning of `app.handlers.export-shapes`, and the pdf assembly
  its `export-frames` twin does, dress the same run the old `/api/export`
  served. What changes around them is the frame: the session minted
  through `create-job-session` renders as the user, the milestone
  vocabulary of the progress contract dresses the beats (`:preparing`,
  `:rendering`, `:packaging`, with the `objects` and `pages` counters
  of every partition), and the settle is one `complete-job` multipart
  call that stores the artifact and closes the session in the same
  step.

  The run never rejects on an export failure: whatever goes wrong
  reaches the backend as `fail-job` (with the session id, so the render
  session does not outlive the settle). Only bugs of this process
  reject."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.consumer.api :as api]
   [app.handlers.export-shapes :as shapes]
   [app.handlers.resources :as rsc]
   [app.jobs.utils :as job.utils]
   [app.renderer :as rd]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private report-throttle-ms 250)

;; ---- PROGRESS (the milestones of the contract)

(defn- report!
  [job-id stage counters]
  (api/report-job-progress job-id {:stage stage :counters counters}))

(defn- start-beats!
  "One progress reporter per run. The stage changes beat by force; the
  per-object beats are throttled the way the legacy export was, since
  hundreds of objects would otherwise be hundreds of malformed calls
  for information nobody reads at that resolution."
  [job-id]
  (let [last-beat (atom 0)]
    (fn [stage counters & {:keys [force?]}]
      (if force?
        (report! job-id stage counters)
        (let [now (js/Date.now)]
          (when (>= (- now @last-beat) report-throttle-ms)
            (reset! last-beat now)
            (report! job-id stage counters)))))))

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

(defn- normalize-items
  "The items the claim delivered are the plain JSON the job row holds:
  the type arrives as text and the renderer dispatches on the keywords
  the legacy surface received. The frames items are pages, not typed
  shapes: they name the pdf of a page each."
  [items]
  (->> items
       (mapv (fn [item]
               (cond-> item
                 (string? (:type item)) (update :type keyword)
                 (nil? (:type item))    (assoc :type :pdf :scale 1 :suffix ""))))))

(defn- make-plan
  "The render plan the legacy handlers prepare: the same transducers of
  names and partition size they served the old surface with, the same
  `single?` rule (one prepared export with one object, not forced),
  and the artifact the whole run fills."
  [token {:keys [exports force-multiple name skip-children is-wasm]}]
  (let [items     (normalize-items exports)
        frames?   (boolean (every? frame-item? items))
        prepared  (shapes/prepare-exports items token is-wasm)
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
     :prepared      prepared
     :total         (shapes/count-objects prepared)
     :skip-children skip-children
     :is-wasm       (boolean is-wasm)
     :counter-kind  (if frames? :pages :objects)
     :resource      (rsc/create kind
                                (or name (-> prepared first :name)))}))

;; ---- THE RUNNERS

(defn- run-single!
  "One render, one object: the artifact IS the object, moved into the
  resource path the multipart settle will name."
  [progress! plan]
  (let [{:keys [job-id resource counter-kind]} plan
        export   (-> plan :prepared first)
        object   (atom nil)]
    (job.utils/track! job-id (:path resource))
    (p/let [_       (progress! :rendering (counter counter-kind 0 1) :force? true)
            _       (rd/render (assoc export
                                      :job-id        job-id
                                      :skip-children (:skip-children plan))
                               (fn [obj]
                                 (reset! object obj)
                                 (job.utils/track! job-id (:path obj))))
            _       (sh/move! (:path @object) (:path resource))
            _       (progress! :packaging (counter counter-kind 1 1) :force? true)]
      resource)))

(defn- run-multiple!
  "The multi-object render: every prepared export renders with the same
  on-object callback, appending each object to the zip as it lands, and
  the progress counts what has landed so far. Any zipping error
  surfaces after the renders, so the failures of the writer do not
  compete with the ones of the render."
  [progress! plan]
  (let [{:keys [job-id resource prepared total counter-kind]} plan
        failure  (volatile! nil)
        rendered (volatile! 0)
        zip      (rsc/create-zip :resource resource
                                 :on-error (fn [cause] (vreset! failure cause))
                                 :on-progress (fn [_] nil))
        append   (fn [{:keys [filename path]}]
                   (job.utils/track! job-id path)
                   (vswap! rendered inc)
                   (progress! :rendering
                              (counter counter-kind @rendered total))
                   (rsc/add-to-zip zip path
                                   (str/replace filename
                                                shapes/sanitize-file-regex
                                                "_")))]
    (p/let [_       (progress! :rendering (counter counter-kind 0 total) :force? true)
            _       (rd/with-scope prepared
                      (fn [scoped-render]
                        (p/all (map (fn [export]
                                      (scoped-render
                                       (assoc export :job-id job-id)
                                       append))
                                    prepared))))
            error   (if-let [cause @failure]
                      (p/rejected cause)
                      (rsc/close-zip zip))
            _       (do error)
            _       (progress! :packaging (counter counter-kind total total) :force? true)]
      resource)))

(defn- join-pdf!
  [job-id file-id paths]
  (let [path   (job.utils/track! job-id
                                 (sh/tempfile :prefix (str/concat "penpot.pdfunite." file-id ".")
                                              :suffix ".pdf"))]
    (p/let [_     (sh/run-cmd! "pdfunite" (into [] (concat (vec paths) [path])))]
      path)))

(defn- run-frames!
  "The frames render: a file per page, joined into the pdf of the file
  once every page has landed."
  [progress! plan]
  (let [{:keys [job-id resource prepared total]} plan
        file-id   (-> prepared first :file-id)
        paths     (volatile! [])
        rendered  (volatile! 0)
        on-object (fn [{:keys [path]}]
                    (job.utils/track! job-id path)
                    (vswap! paths conj path)
                    (vswap! rendered inc)
                    (progress! :rendering
                               (counter :pages @rendered total)))]
    (p/let [_       (progress! :rendering (counter :pages 0 total) :force? true)
            _       (rd/with-scope prepared
                      (fn [scoped-render]
                        (p/all (map (fn [export]
                                      (scoped-render
                                       (assoc export :job-id job-id :is-wasm (:is-wasm plan))
                                       on-object))
                                    prepared))))
            joined   (join-pdf! job-id file-id @paths)
            _        (sh/move! joined (:path resource))
            _       (progress! :packaging (counter :pages total total) :force? true)]
      resource)))

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

(defn- run-prepared!
  [beats! plan]
  (cond
    (:single? plan) (run-single!   beats! plan)
    (:frames? plan) (run-frames!  beats! plan)
    :else           (run-multiple! beats! plan)))

(defn run-export!
  [{:keys [job-id] :as _job} params]
  (let [session (atom nil)
        beats!  (start-beats! job-id)]
    (p/catch
     (p/let [session'  (api/create-job-session job-id)
             _         (reset! session session')
             token     (:session-token session')
             _         (beats! :preparing {})
             plan      (make-plan token params)
             resource  (run-prepared! beats! plan)
             artifact  {:path     (str (:path resource))
                        :filename (:filename resource)
                        :mtype    (:mtype resource)}
             _         (api/complete-job-with-artifact
                        {:job-id job-id :session-id (:session-id session')}
                        artifact)
             _         (job.utils/release! job-id)
             _         (l/info :hint "export job settled" :job-id job-id
                               :outcome "completed")]
       nil)
     (fn [cause]
       (l/error :hint "export job failed" :job-id job-id :cause cause)
       (settle-failure! job-id session cause)))))

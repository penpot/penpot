;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.worker.runner
  "Job execution over the unified `job` table (impl)."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   java.lang.AutoCloseable))

(set! *warn-on-reflection* true)

(def ^:private sql:claim-job
  "UPDATE job
      SET status='running', started_at=now(), modified_at=now()
    WHERE id=?
      AND status IN ('new','scheduled','retry')")

(def ^:private sql:complete-job
  "UPDATE job
      SET status='completed', completed_at=?, modified_at=?, result=?, error=NULL
    WHERE id=?
      AND status IN ('running','retry')")

(def ^:private sql:fail-job
  "UPDATE job
      SET status='failed', modified_at=?, error=?
    WHERE id=?
      AND status IN ('running','retry')")

(def ^:private sql:retry-job
  "UPDATE job
      SET status='retry', modified_at=?, scheduled_at=?, retry_num=?, error=?
    WHERE id=?
      AND status IN ('running','retry')")

(defn- claim-job!
  "Conditional claim: only transition pending jobs (new/scheduled/retry)
  to running. A cancelled or terminal job produces 0 affected rows and is
  skipped without touching its state (first-terminal-wins companion)."
  [cfg job-id]
  (-> (db/exec-one! (db/get-connectable cfg)
                    [sql:claim-job job-id])
      (db/get-update-count)))

(defn get-error-context
  [_ item]
  (-> (cf/logging-context)
      (assoc :params item)))

(defn- encode-result
  [result]
  (try
    (db/json result)
    (catch Throwable _cause
      nil)))

(defn- encode-error
  [error]
  (db/json {:code  "failed"
            :message (or (when (ex/exception? error)
                           (ex-message error))
                         (str error))}))

(defn- get-job
  "Fetch the job row (props kept as raw pgobject; decoded later with the
  job-def decoder)."
  [cfg job-id]
  (ex/try!
   (some-> (db/get* cfg :job {:id job-id}))))

(defn- run-job
  [{:keys [::jobs/defs ::id ::queue ::mtx/metrics] :as cfg} job]
  (try
    (l/dbg :hint "start"
           :name (:name job)
           :job-id (str (:id job))
           :queue queue
           :runner-id id
           :retry (:retry-num job))

    (if (zero? (claim-job! cfg (:id job)))
      (l/wrn :hint "skiping job, not claimable"
             :id (str (:id job))
             :name (:name job)
             :status (:status job))

      (let [job-def   (jobs/get-job-def defs (:name job))
            params    (jobs/decode-params job-def (:props job))
            handler   (::jobs/handler job-def)
            tpoint    (ct/tpoint)
            labels    (into-array String [(:name job)])
            result    (binding [jobs/*job-id* (:id job)]
                        (try
                          (handler params)
                          (finally
                            (mtx/run! metrics
                                      {:id :tasks-timing
                                       :val (inst-ms (tpoint))
                                       :labels labels}))))]

        (l/dbg :hint "end"
               :name (:name job)
               :job-id (str (:id job))
               :queue queue
               :runner-id id
               :retry (:retry-num job)
               :elapsed (ct/format-duration (tpoint)))

        {:status "completed"
         :result result}))

    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (let [edata (ex-data cause)]
        (if (and (< (:retry-num job)
                    (:max-retries job))
                 (= ::wrk/retry (:type edata)))
          (cond-> {:status "retry" :error cause}
            (ct/duration? (:delay edata))
            (assoc :delay-ms (.toMillis ^java.time.Duration (:delay edata)))
            (int? (:delay edata))
            (assoc :delay-ms (:delay edata))

            (= ::noop (:strategy edata))
            (assoc :inc-by 0))
          (do
            (l/err :hint "unhandled exception on job"
                   ::l/context (assoc (cf/logging-context) :params job)
                   :cause cause)
            (if (>= (:retry-num job) (:max-retries job))
              {:status "failed" :error cause}
              {:status "retry" :error cause})))))))

(defn- run-job!
  [{:keys [::id ::timeout] :as cfg} job-id scheduled-at]
  (loop [job (get-job cfg job-id)]
    (cond
      (nil? job)
      (l/wrn :hint "no job found on the database"
             :runner-id id
             :job-id (str job-id))

      (ex/exception? job)
      (if (or (db/connection-error? job)
              (db/serialization-error? job))
        (do
          (l/wrn :hint "connection error on retrieving job from database (retrying in some instants)"
                 :runner-id id
                 :cause job)
          (px/sleep timeout)
          (recur (get-job cfg job-id)))
        (do
          (l/err :hint "unhandled exception on retrieving job from database (retrying in some instants)"
                 :runner-id id
                 :cause job)
          (px/sleep timeout)
          (recur (get-job cfg job-id))))

      (not= (inst-ms scheduled-at)
            (inst-ms (:scheduled-at job)))
      (l/wrn :hint "skiping job, rescheduled"
             :job-id (str job-id)
             :runner-id id
             :scheduled-at (ct/format-inst (:scheduled-at job))
             :expected-scheduled-at (ct/format-inst scheduled-at))

      :else
      (let [result (run-job cfg job)]
        (with-meta result
          {::job job})))))

(defn- run-worker-loop!
  [{:keys [::rds/conn ::timeout ::queue] :as cfg}]
  (letfn [(handle-job-retry [{:keys [error delay-ms inc-by] :or {inc-by 1 delay-ms 1000} :as result}]
            (let [job    (-> result meta ::job)
                  nretry (+ (:retry-num job) inc-by)
                  now    (ct/now)
                  delay  (->> (iterate #(* 2 %) delay-ms) (take nretry) (last))]
              (db/exec-one! (db/get-connectable cfg)
                            [sql:retry-job
                             now
                             (-> (ct/plus now (ct/duration {:millis delay}))
                                 (ct/truncate :millisecond))
                             nretry
                             (encode-error error)
                             (:id job)])
              nil))

          (handle-job-failure [{:keys [error] :as result}]
            (let [job (-> result meta ::job)]
              (db/exec-one! (db/get-connectable cfg)
                            [sql:fail-job
                             (ct/now)
                             (encode-error error)
                             (:id job)])
              nil))

          (handle-job-completion [result]
            (let [job (-> result meta ::job)]
              (db/exec-one! (db/get-connectable cfg)
                            [sql:complete-job
                             (ct/now)
                             (ct/now)
                             (encode-result (:result result))
                             (:id job)])
              nil))

          (decode-payload [payload]
            (try
              (let [payload  (json/decode payload)
                    job-id   (uuid/parse (first payload))
                    sched-at (ct/inst (second payload))]
                (if (and (uuid? job-id)
                         (ct/inst? sched-at))
                  [job-id sched-at]
                  (do
                    (l/err :hint "received unexpected payload"
                           :payload payload)
                    nil)))
              (catch Throwable cause
                (l/err :hint "unable to decode payload"
                       ::l/context (cf/logging-context)
                       :payload payload
                       :length (count payload)
                       :cause cause))))

          (process-result [{:keys [status] :as result}]
            (ex/try!
             (case status
               "retry"     (handle-job-retry result)
               "failed"    (handle-job-failure result)
               "completed" (handle-job-completion result)
               (throw (IllegalArgumentException.
                       (str "invalid status received: '" status "'"))))))

          (run-job-loop [[job-id scheduled-at]]
            (loop [result (run-job! cfg job-id scheduled-at)]
              (when-let [cause (some-> result process-result)]
                (if (or (db/connection-error? cause)
                        (db/serialization-error? cause))
                  (do
                    (l/wrn :hint "database exeption on processing job result (retrying in some instants)"
                           :cause cause)
                    (px/sleep timeout)
                    (recur result))
                  (l/err :hint "unhandled exception on processing job result"
                         ::l/context (cf/logging-context)
                         :cause cause)))))]

    (try
      (let [key         (str/ffmt "penpot.worker.queue:%" queue)
            [_ payload] (rds/blpop conn [key] timeout)]
        (some-> payload
                decode-payload
                run-job-loop))

      (catch InterruptedException cause
        (throw cause))

      (catch Exception cause
        (if (rds/timeout-exception? cause)
          (do
            (l/err :hint "redis pop operation timeout, consider increasing redis timeout (will retry in some instants)"
                   ::l/context (cf/logging-context)
                   :timeout timeout
                   :cause cause)
            (px/sleep timeout))

          (l/err :hint "unhandled exception"
                 ::l/context (cf/logging-context)
                 :cause cause))))))

(defn- start-thread!
  [{:keys [::id ::queue ::wrk/tenant] :as cfg}]
  (px/thread
    {:name (str "penpot/job-runner/" id)}
    (l/inf :hint "started" :id id :queue queue)

    (let [rconn (rds/connect cfg)]
      (try
        (loop [cfg (-> cfg
                       (assoc ::rds/conn rconn)
                       (assoc ::queue (str/ffmt "%:%" tenant queue))
                       (assoc ::timeout (ct/duration "5s")))]
          (when (px/interrupted?)
            (throw (InterruptedException. "interrupted")))

          (run-worker-loop! cfg)
          (recur cfg))

        (catch InterruptedException _
          (l/dbg :hint "interrupted"
                 :id id
                 :queue queue))
        (catch Throwable cause
          (l/err :hint "unexpected exception"
                 ::l/context (cf/logging-context)
                 :id id
                 :queue queue
                 :cause cause))
        (finally
          (.close ^AutoCloseable rconn)
          (l/inf :hint "terminated"
                 :id id
                 :queue queue))))))

(def ^:private schema:params
  [:map
   [::wrk/parallelism {:optional true} ::sm/int]
   [::wrk/queue :keyword]
   [::wrk/tenant ::sm/text]
   ::jobs/defs
   ::mtx/metrics
   ::db/pool
   ::rds/client])

(defmethod ig/assert-key ::wrk/runner
  [_ params]
  (assert (sm/check schema:params params)))

(defmethod ig/expand-key ::wrk/runner
  [k v]
  {k (merge {::wrk/parallelism 1} (d/without-nils v))})

(defmethod ig/init-key ::wrk/runner
  [_ {:keys [::db/pool ::wrk/queue ::wrk/parallelism] :as cfg}]
  (let [queue (d/name queue)
        cfg   (assoc cfg ::queue queue)]
    (if (db/read-only? pool)
      (l/wrn :hint "not started (db is read-only)" :queue queue :parallelism parallelism)
      (doall
       (->> (range parallelism)
            (map #(assoc cfg ::id (str queue "/" %)))
            (map start-thread!))))))

(defmethod ig/halt-key! ::wrk/runner
  [_ threads]
  (run! px/interrupt! threads))

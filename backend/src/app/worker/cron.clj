;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.cron
  "Cron pure scheduler: each tick claims the scheduled_task entry and
  submits a system job (no profile_id, no user ledger) for it to the
  dedicated `:cron` queue. Execution happens via the standard
  dispatcher -> runner path, and so with the same lease, orphan
  detection, retries and telemetry as for every other job. To keep the
  no-overlap semantics (one entry is not re-fired while the previous
  instance lives), the scheduler only submits when there is no active
  instance (`new`/`scheduled`/`running`/`retry` with the same `name`
  and `label` of the entry). The `FOR UPDATE SKIP LOCKED` claim on the
  scheduled_task row already serializes the tick between nodes."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.util.cron :as cron]
   [app.worker :as-alias wrk]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   java.util.concurrent.Future))

(set! *warn-on-reflection* true)

(def sql:upsert-cron-task
  "insert into scheduled_task (id, cron_expr)
   values (?, ?)
       on conflict (id)
       do nothing")

(def ^:private sql:count-active-jobs
  "SELECT count(*) AS n
     FROM job
    WHERE name = ?
      AND label = ?
      AND status IN ('new','scheduled','running','retry')")

(defn- synchronize-cron-entries!
  [{:keys [::db/conn ::entries]}]
  (doseq [{:keys [id cron]} entries]
    (let [result   (db/exec-one! conn [sql:upsert-cron-task id (str cron)])
          updated? (pos? (db/get-update-count result))]
      (l/dbg :hint "register task" :id id :cron (str cron)
             :status (if updated? "created" "exists")))))

(defn- lock-scheduled-task!
  [conn id]
  (let [sql (str "SELECT id FROM scheduled_task "
                 " WHERE id=? FOR UPDATE SKIP LOCKED")]
    (some? (db/exec-one! conn [sql (d/name id)]))))

(defn submit-cron-job!
  "Submit the system job for the entry to the `:cron` queue. Uses the
  entry id as the job label (stable per entry) and returns the created
  job-id. No-overlap pre-check lives in the caller."
  [cfg {:keys [task props id]}]
  (jobs/submit!
   cfg
   {::jobs/name   task
    ::jobs/params (or props {})  ;; entries don't carry props; default to empty map
    ::jobs/queue  :cron
    ::jobs/label  (name id)}))

(declare ^:private schedule-cron-task)

(defn- execute-cron-task
  "Tick thread for one entry: claim the scheduled_task row and submit
  the system job; no in-process execution of the handler here."
  [cfg {:keys [id cron task] :as entry}]
  (px/thread
    {:name (str "penpot/cron-task/" id)}
    (let [tpoint (ct/tpoint)]
      (try
        (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                          (db/exec-one! conn ["SET LOCAL statement_timeout=0;"])
                          (db/exec-one! conn ["SET LOCAL idle_in_transaction_session_timeout=0;"])
                          (when (lock-scheduled-task! conn id)
                            (db/update! conn :scheduled-task
                                        {:cron-expr (str cron)
                                         :modified-at (ct/now)}
                                        {:id id}
                                        {::db/return-keys false})

                            ;; The count query is not locked, so a job could
                            ;; transition from running to completed between the
                            ;; count and the submit. This is acceptable: the
                            ;; next tick will submit if needed. The FOR UPDATE
                            ;; SKIP LOCKED on the scheduled_task row prevents
                            ;; race conditions between nodes.
                            (let [active (get (db/exec-one! conn
                                                            [sql:count-active-jobs task (str id)])
                                              :n)]
                              (if (pos? (or active 0))
                                (l/dbg :hint "skip scheduling, active instance exists"
                                       :id id :task task)
                                (let [job-id (submit-cron-job! cfg entry)]
                                  (l/dbg :hint "cron job submitted"
                                         :id id
                                         :task task
                                         :job-id (str job-id))))))

                          (let [elapsed (ct/format-duration (tpoint))]
                            (l/dbg :hint "end" :id id :elapsed elapsed))))

        (catch InterruptedException _
          (let [elapsed (ct/format-duration (tpoint))]
            (l/debug :hint "task interrupted" :id id :elapsed elapsed)))

        (catch Throwable cause
          (let [elapsed (ct/format-duration (tpoint))]
            (binding [l/*context* (assoc (cf/logging-context) :params entry)]
              (l/err :hint "unhandled exception on running task"
                     :id id
                     :elapsed elapsed
                     :cause cause))))
        (finally
          (when-not (px/interrupted? :current)
            (schedule-cron-task cfg entry)))))))

(defn- ms-until-valid
  [cron]
  (assert (cron/cron-expr? cron) "expected cron instance")
  (let [now  (ct/now)
        next (cron/next-valid-instant-from cron now)]
    (ct/diff now next)))

(defn- schedule-cron-task
  [{:keys [::running] :as cfg} {:keys [cron id] :as task}]
  (let [ts (ms-until-valid cron)
        ft (px/schedule! ts (partial execute-cron-task cfg task))]

    (l/dbg :hint "schedule" :id id
           :ts (ct/format-duration ts)
           :at (ct/format-inst (ct/in-future ts)))

    (swap! running #(into #{ft} (filter p/pending?) %))))

(def ^:private schema:params
  [:map
   [::wrk/entries
    [:vector
     [:maybe
      [:map
       [:cron [:fn cron/cron-expr?]]
       [:task :keyword]
       [:props {:optional true} :map]
       [:id {:optional true} :keyword]]]]]
   ::jobs/defs
   ::db/pool])

(defmethod ig/assert-key ::wrk/cron
  [_ params]
  (assert (sm/check schema:params params)))

(defmethod ig/init-key ::wrk/cron
  [_ {:keys [::wrk/entries ::jobs/defs ::db/pool] :as cfg}]
  (if (db/read-only? pool)
    (l/wrn :hint "service not started (db is read-only)")
    (let [running (atom #{})
          entries (->> entries
                       (filter some?)
                       ;; If id is not defined, use the task as id.
                       (map (fn [{:keys [id task] :as item}]
                              (if (some? id)
                                (assoc item :id (d/name id))
                                (assoc item :id (d/name task)))))
                       (map (fn [item]
                              (update item :task d/name)))
                       (doall
                        (map (fn [item]
                               ;; fail fast when the entry references
                               ;; an unknown job name
                               (jobs/get-job-def defs (:task item))
                               item)
                             entries)))]

      (l/inf :hint "started" :tasks (count entries))

      (db/tx-run! cfg synchronize-cron-entries!)

      (->> (filter some? entries)
           (run! (partial schedule-cron-task cfg)))

      (reify
        clojure.lang.IDeref
        (deref [_] @running)

        java.lang.AutoCloseable
        (close [_]
          (l/inf :hint "terminated")
          (doseq [item @running]
            (when-not (.isDone ^Future item)
              (.cancel ^Future item true))))))))

(defmethod ig/halt-key! ::wrk/cron
  [_ instance]
  (some-> instance d/close!))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.dispatcher
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.worker :as-alias wrk]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   java.lang.AutoCloseable))

(set! *warn-on-reflection* true)

(def ^:private schema:dispatcher
  [:map
   [::wrk/tenant ::sm/text]
   ::mtx/metrics
   ::db/pool
   ::rds/client])

(defmethod ig/expand-key ::wrk/dispatcher
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::timeout (ct/duration "10s"))
         (assoc ::batch-size 100)
         (assoc ::wait-duration (ct/duration "5s")))})

(defmethod ig/assert-key ::wrk/dispatcher
  [_ cfg]
  (assert (sm/check schema:dispatcher cfg)))

(def ^:private sql:select-next-tasks
  "SELECT id, queue, scheduled_at from task AS t
    WHERE t.scheduled_at <= ?::timestamptz
      AND (t.status = 'new' OR t.status = 'retry')
      AND queue ~~* ?::text
    ORDER BY t.priority DESC, t.scheduled_at
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(def ^:private sql:mark-task-scheduled
  "UPDATE task SET status = 'scheduled'
    WHERE id = ANY(?)")

(def ^:private sql:reschedule-lost
  "UPDATE task
      SET status='new', scheduled_at=?::timestamptz
     FROM (SELECT t.id
             FROM task AS t
            WHERE status = 'scheduled'
              AND (?::timestamptz - t.scheduled_at) > '5 min'::interval) AS subquery
    WHERE task.id=subquery.id
RETURNING task.id, task.queue")

(def ^:private sql:clean-orphan
  "UPDATE task
      SET status='failed', modified_at=?::timestamptz,
          error='orphan with running status'
     FROM (SELECT t.id
             FROM task AS t
            WHERE status = 'running'
              AND (?::timestamptz - t.modified_at) > '24 hour'::interval) AS subquery
    WHERE task.id=subquery.id
RETURNING task.id, task.queue")

(defmethod ig/init-key ::wrk/dispatcher
  [_ {:keys [::db/pool ::wrk/tenant ::batch-size ::timeout] :as cfg}]
  (letfn [(reschedule-lost-tasks [{:keys [::db/conn ::timestamp]}]
            (doseq [{:keys [id queue]} (db/exec! conn [sql:reschedule-lost timestamp timestamp]
                                                 {:return-keys true})]
              (l/wrn :hint "reschedule"
                     :id (str id)
                     :queue queue)))

          (clean-orphan [{:keys [::db/conn ::timestamp]}]
            (doseq [{:keys [id queue]} (db/exec! conn [sql:clean-orphan timestamp timestamp]
                                                 {:return-keys true})]
              (l/wrn :hint "mark as orphan failed"
                     :id (str id)
                     :queue queue)))

          (get-tasks [{:keys [::db/conn ::timestamp] :as cfg}]
            (let [prefix (str tenant ":%")
                  result (db/exec! conn [sql:select-next-tasks timestamp prefix batch-size])]
              (not-empty result)))

          (mark-as-scheduled [{:keys [::db/conn]} items]
            (let [ids (map :id items)
                  sql [sql:mark-task-scheduled
                       (db/create-array conn "uuid" ids)]]
              (db/exec-one! conn sql)))

          (push-tasks [{:keys [::rds/conn] :as cfg} [queue tasks]]
            (let [items (mapv (juxt :id :scheduled-at) tasks)
                  key   (str/ffmt "penpot.worker.queue:%" queue)]

              (rds/rpush conn key (mapv t/encode-str items))
              (mark-as-scheduled cfg tasks)

              (doseq [{:keys [id queue]} tasks]
                (l/trc :hist "schedule"
                       :id (str id)
                       :queue queue))))

          (run-batch' [cfg]
            (let [cfg (assoc cfg ::timestamp (ct/now))]
              ;; Reschedule lost in transit tasks (can happen when
              ;; redis server is restarted just after task is pushed)
              (reschedule-lost-tasks cfg)

              ;; Mark as failed all tasks that are still marked as
              ;; running but it's been more than 24 hours since its
              ;; last modification
              (clean-orphan cfg)

              ;; Then, schedule the next tasks in queue
              (if-let [tasks (get-tasks cfg)]
                (->> (group-by :queue tasks)
                     (run! (partial push-tasks cfg)))

                ;; If no tasks found on this batch run, we signal the
                ;; run-loop to wait for some time before start running
                ;; the next batch interation
                ::wait)))

          (run-batch []
            (let [rconn (rds/connect cfg)]
              (try
                (-> cfg
                    (assoc ::rds/conn rconn)
                    (db/tx-run! run-batch'))

                (catch InterruptedException cause
                  (throw cause))
                (catch Exception cause
                  (cond
                    (rds/exception? cause)
                    (do
                      (l/wrn :hint "redis exception (will retry in an instant)" :cause cause)
                      (px/sleep timeout))

                    (db/sql-exception? cause)
                    (do
                      (l/wrn :hint "database exception (will retry in an instant)" :cause cause)
                      (px/sleep timeout))

                    :else
                    (do
                      (l/err :hint "unhandled exception (will retry in an instant)" :cause cause)
                      (px/sleep timeout))))

                (finally
                  (.close ^AutoCloseable rconn)))))

          (dispatcher []
            (l/inf :hint "started")
            (try
              (loop []
                (let [result (run-batch)]
                  (when (= result ::wait)
                    (px/sleep (::wait-duration cfg)))
                  (recur)))
              (catch InterruptedException _
                (l/trc :hint "interrupted"))
              (catch Throwable cause
                (l/err :hint " unexpected exception" :cause cause))
              (finally
                (l/inf :hint "terminated"))))]

    (if (db/read-only? pool)
      (l/wrn :hint "not started (db is read-only)")
      (px/fn->thread dispatcher :name "penpot/worker-dispatcher"))))

(defmethod ig/halt-key! ::wrk/dispatcher
  [_ thread]
  (some-> thread px/interrupt!))

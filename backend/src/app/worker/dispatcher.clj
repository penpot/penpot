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
  "select id, queue from task as t
    where t.scheduled_at <= now()
      and (t.status = 'new' or t.status = 'retry')
      and queue ~~* ?::text
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(def ^:private sql:mark-task-scheduled
  "UPDATE task SET status = 'scheduled'
    WHERE id = ANY(?)")

(defmethod ig/init-key ::wrk/dispatcher
  [_ {:keys [::db/pool ::wrk/tenant ::batch-size ::timeout] :as cfg}]
  (letfn [(get-tasks [{:keys [::db/conn]}]
            (let [prefix (str tenant ":%")]
              (not-empty (db/exec! conn [sql:select-next-tasks prefix batch-size]))))

          (mark-as-scheduled [{:keys [::db/conn]} ids]
            (let [sql [sql:mark-task-scheduled
                       (db/create-array conn "uuid" ids)]]
              (db/exec-one! conn sql)))

          (push-tasks [{:keys [::rds/conn] :as cfg} [queue tasks]]
            (let [ids (mapv :id tasks)
                  key (str/ffmt "taskq:%" queue)
                  res (rds/rpush conn key (mapv t/encode-str ids))]

              (mark-as-scheduled cfg ids)
              (l/trc :hist "enqueue tasks on redis"
                     :queue queue
                     :tasks (count ids)
                     :queued res)))

          (run-batch' [cfg]
            (if-let [tasks (get-tasks cfg)]
              (->> (group-by :queue tasks)
                   (run! (partial push-tasks cfg)))
              ::wait))

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

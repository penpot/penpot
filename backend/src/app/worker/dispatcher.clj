;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.dispatcher
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(set! *warn-on-reflection* true)

(defmethod ig/pre-init-spec ::wrk/dispatcher [_]
  (s/keys :req [::mtx/metrics ::db/pool ::rds/redis]))

(defmethod ig/prep-key ::wrk/dispatcher
  [_ cfg]
  (merge {::batch-size 100
          ::wait-duration (dt/duration "5s")}
         (d/without-nils cfg)))

(def ^:private sql:select-next-tasks
  "select id, queue from task as t
    where t.scheduled_at <= now()
      and (t.status = 'new' or t.status = 'retry')
      and queue ~~* ?::text
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defmethod ig/init-key ::wrk/dispatcher
  [_ {:keys [::db/pool ::rds/redis ::batch-size] :as cfg}]
  (letfn [(get-tasks [conn]
            (let [prefix (str (cf/get :tenant) ":%")]
              (seq (db/exec! conn [sql:select-next-tasks prefix batch-size]))))

          (push-tasks! [conn rconn [queue tasks]]
            (let [ids (mapv :id tasks)
                  key (str/ffmt "taskq:%" queue)
                  res (rds/rpush! rconn key (mapv t/encode ids))
                  sql [(str "update task set status = 'scheduled'"
                            " where id = ANY(?)")
                       (db/create-array conn "uuid" ids)]]

              (db/exec-one! conn sql)
              (l/trc :hist "enqueue tasks on redis"
                     :queue queue
                     :tasks (count ids)
                     :queued res)))

          (run-batch! [rconn]
            (try
              (db/with-atomic [conn pool]
                (if-let [tasks (get-tasks conn)]
                  (->> (group-by :queue tasks)
                       (run! (partial push-tasks! conn rconn)))
                  (px/sleep (::wait-duration cfg))))
              (catch InterruptedException cause
                (throw cause))
              (catch Exception cause
                (cond
                  (rds/exception? cause)
                  (do
                    (l/wrn :hint "redis exception (will retry in an instant)" :cause cause)
                    (px/sleep (::rds/timeout rconn)))

                  (db/sql-exception? cause)
                  (do
                    (l/wrn :hint "database exception (will retry in an instant)" :cause cause)
                    (px/sleep (::rds/timeout rconn)))

                  :else
                  (do
                    (l/err :hint "unhandled exception (will retry in an instant)" :cause cause)
                    (px/sleep (::rds/timeout rconn)))))))

          (dispatcher []
            (l/inf :hint "started")
            (try
              (dm/with-open [rconn (rds/connect redis)]
                (loop []
                  (run-batch! rconn)
                  (recur)))
              (catch InterruptedException _
                (l/trc :hint "interrupted"))
              (catch Throwable cause
                (l/err :hint " unexpected exception" :cause cause))
              (finally
                (l/inf :hint "terminated"))))]

    (if (db/read-only? pool)
      (l/wrn :hint "not started (db is read-only)")
      (px/fn->thread dispatcher :name "penpot/worker/dispatcher" :virtual false))))

(defmethod ig/halt-key! ::wrk/dispatcher
  [_ thread]
  (some-> thread px/interrupt!))

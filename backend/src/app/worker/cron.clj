;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.cron
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [app.worker.runner :refer [get-error-context]]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
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
       do update set cron_expr=?")

(defn- synchronize-cron-entries!
  [{:keys [::db/pool ::entries]}]
  (db/with-atomic [conn pool]
    (doseq [{:keys [id cron]} entries]
      (l/trc :hint "register cron task" :id id :cron (str cron))
      (db/exec-one! conn [sql:upsert-cron-task id (str cron) (str cron)]))))

(defn- lock-scheduled-task!
  [conn id]
  (let [sql (str "SELECT id FROM scheduled_task "
                 " WHERE id=? FOR UPDATE SKIP LOCKED")]
    (some? (db/exec-one! conn [sql (d/name id)]))))

(declare ^:private schedule-cron-task)

(defn- execute-cron-task
  [cfg {:keys [id] :as task}]
  (px/thread
    {:name (str "penpot/cron-task/" id)}
    (let [tpoint (dt/tpoint)]
      (try
        (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                          (db/exec-one! conn ["SET LOCAL statement_timeout=0;"])
                          (db/exec-one! conn ["SET LOCAL idle_in_transaction_session_timeout=0;"])
                          (when (lock-scheduled-task! conn id)
                            (l/dbg :hint "start task" :task-id id)
                            ((:fn task) task)
                            (let [elapsed (dt/format-duration (tpoint))]
                              (l/dbg :hint "end task" :task-id id :elapsed elapsed)))))

        (catch InterruptedException _
          (let [elapsed (dt/format-duration (tpoint))]
            (l/debug :hint "task interrupted" :task-id id :elapsed elapsed)))

        (catch Throwable cause
          (let [elapsed (dt/format-duration (tpoint))]
            (binding [l/*context* (get-error-context cause task)]
              (l/err :hint "unhandled exception on running task"
                     :task-id id
                     :elapsed elapsed
                     :cause cause))))
        (finally
          (when-not (px/interrupted? :current)
            (schedule-cron-task cfg task)))))))

(defn- ms-until-valid
  [cron]
  (s/assert dt/cron? cron)
  (let [now  (dt/now)
        next (dt/next-valid-instant-from cron now)]
    (dt/diff now next)))

(defn- schedule-cron-task
  [{:keys [::running] :as cfg} {:keys [cron id] :as task}]
  (let [ts (ms-until-valid cron)
        ft (px/schedule! ts (partial execute-cron-task cfg task))]

    (l/dbg :hint "schedule task" :task-id id
           :ts (dt/format-duration ts)
           :at (dt/format-instant (dt/in-future ts)))

    (swap! running #(into #{ft} (filter p/pending?) %))))


(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::id keyword?)
(s/def ::cron dt/cron?)
(s/def ::props (s/nilable map?))
(s/def ::task keyword?)

(s/def ::task-item
  (s/keys :req-un [::cron ::task]
          :opt-un [::props ::id]))

(s/def ::wrk/entries (s/coll-of (s/nilable ::task-item)))

(defmethod ig/pre-init-spec ::wrk/cron [_]
  (s/keys :req [::db/pool ::wrk/entries ::wrk/registry]))

(defmethod ig/init-key ::wrk/cron
  [_ {:keys [::wrk/entries ::wrk/registry ::db/pool] :as cfg}]
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
                       (map (fn [{:keys [task] :as item}]
                              (let [f (get registry task)]
                                (when-not f
                                  (ex/raise :type :internal
                                            :code :task-not-found
                                            :hint (str/fmt "task %s not configured" task)))
                                (-> item
                                    (dissoc :task)
                                    (assoc :fn f))))))

          cfg     (assoc cfg ::entries entries ::running running)]

      (l/inf :hint "started" :tasks (count entries))
      (synchronize-cron-entries! cfg)

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


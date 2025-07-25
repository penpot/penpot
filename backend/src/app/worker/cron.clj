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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.util.cron :as cron]
   [app.worker :as wrk]
   [app.worker.runner :refer [get-error-context]]
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
       do nothing")

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

(declare ^:private schedule-cron-task)

(defn- execute-cron-task
  [cfg {:keys [id cron] :as task}]
  (px/thread
    {:name (str "penpot/cron-task/" id)}
    (let [tpoint (ct/tpoint)]
      (try
        (db/tx-run! cfg (fn [{:keys [::db/conn]}]
                          (db/exec-one! conn ["SET LOCAL statement_timeout=0;"])
                          (db/exec-one! conn ["SET LOCAL idle_in_transaction_session_timeout=0;"])
                          (when (lock-scheduled-task! conn id)
                            (db/update! conn :scheduled-task
                                        {:cron-expr (str cron)
                                         :modified-at (ct/now)}
                                        {:id id}
                                        {::db/return-keys false})
                            (l/dbg :hint "start" :id id)
                            ((:fn task) task)
                            (let [elapsed (ct/format-duration (tpoint))]
                              (l/dbg :hint "end" :id id :elapsed elapsed)))))

        (catch InterruptedException _
          (let [elapsed (ct/format-duration (tpoint))]
            (l/debug :hint "task interrupted" :id id :elapsed elapsed)))

        (catch Throwable cause
          (let [elapsed (ct/format-duration (tpoint))]
            (binding [l/*context* (get-error-context cause task)]
              (l/err :hint "unhandled exception on running task"
                     :id id
                     :elapsed elapsed
                     :cause cause))))
        (finally
          (when-not (px/interrupted? :current)
            (schedule-cron-task cfg task)))))))

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
   ::wrk/registry
   ::db/pool])

(defmethod ig/assert-key ::wrk/cron
  [_ params]
  (assert (sm/check schema:params params)))

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
                              (let [f (wrk/get-task registry task)]
                                (when-not f
                                  (ex/raise :type :internal
                                            :code :task-not-found
                                            :hint (str/fmt "task %s not configured" task)))
                                (-> item
                                    (dissoc :task)
                                    (assoc :fn f))))))

          cfg     (assoc cfg ::entries entries ::running running)]

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


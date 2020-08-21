;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.worker
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.tasks.delete-object]
   [app.tasks.delete-profile]
   [app.tasks.gc]
   [app.tasks.remove-media]
   [app.tasks.sendmail]
   [app.tasks.trim-file]
   [app.util.async :as aa]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [promesa.exec :as px])
  (:import
   org.eclipse.jetty.util.thread.QueuedThreadPool
   java.util.concurrent.ExecutorService
   java.util.concurrent.Executors
   java.util.concurrent.Executor
   java.time.Duration
   java.time.Instant
   java.util.Date))

(declare start-scheduler-worker!)
(declare start-worker!)
(declare thread-pool)
(declare stop!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point (state initialization)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private tasks
  {"delete-profile" #'app.tasks.delete-profile/handler
   "delete-object" #'app.tasks.delete-object/handler
   "remove-media" #'app.tasks.remove-media/handler
   "sendmail" #'app.tasks.sendmail/handler})

(def ^:private schedule
  [{:id "remove-deleted-media"
    :cron (dt/cron "0 0 0 */1 * ? *") ;; daily
    :fn #'app.tasks.gc/remove-deleted-media}
   {:id "trim-file"
    :cron (dt/cron "0 0 0 */1 * ? *") ;; daily
    :fn #'app.tasks.trim-file/handler}
   ])


(defstate executor
  :start (thread-pool {:idle-timeout 10000
                            :min-threads 0
                            :max-threads 256})
  :stop (stop! executor))

(defstate worker
  :start (start-worker!
          {:tasks tasks
           :name "worker1"
           :batch-size 1
           :executor executor})
  :stop (stop! worker))

(defstate scheduler-worker
  :start (start-scheduler-worker! {:schedule schedule
                                        :executor executor})
  :stop (stop! scheduler-worker))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  sql:mark-as-retry
  "update task
      set scheduled_at = clock_timestamp() + '10 seconds'::interval,
          modified_at = clock_timestamp(),
          error = ?,
          status = 'retry',
          retry_num = retry_num + ?
    where id = ?")

(defn- mark-as-retry
  [conn {:keys [task error inc-by]
         :or {inc-by 1}}]
  (let [explain (ex-message error)
        sqlv [sql:mark-as-retry explain inc-by (:id task)]]
    (db/exec-one! conn sqlv)
    nil))

(defn- mark-as-failed
  [conn {:keys [task error]}]
  (let [explain (ex-message error)]
    (db/update! conn :task
                {:error explain
                 :modified-at (dt/now)
                 :status "failed"}
                {:id (:id task)})
    nil))

(defn- mark-as-completed
  [conn {:keys [task] :as opts}]
  (let [now (dt/now)]
    (db/update! conn :task
                {:completed-at now
                 :modified-at now
                 :status "completed"}
                {:id (:id task)})
    nil))

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props)))))

(defn- handle-task
  [tasks {:keys [name] :as item}]
  (let [task-fn (get tasks name)]
    (if task-fn
      (task-fn item)
      (do
        (log/warn "no task handler found for" (pr-str name))
        nil))))

(defn- run-task
  [{:keys [tasks conn]} item]
  (try
    (log/debugf "Started task '%s/%s/%s'." (:name item) (:id item) (:retry-num item))
    (handle-task tasks item)
    {:status :completed :task item}
    (catch Exception e
      (let [data (ex-data e)]
        (cond
          (and (= ::retry (:type data))
               (= ::noop (:strategy data)))
          {:status :retry :task item :error e :inc-by 0}

          (and (< (:retry-num item)
                  (:max-retries item))
               (= ::retry (:type data)))
          {:status :retry :task item :error e}

          :else
          (do
            (log/errorf e "Unhandled exception on task '%s' (retry: %s)\nProps: %s"
                        (:name item) (:retry-num item) (pr-str (:props item)))
            (if (>= (:retry-num item) (:max-retries item))
              {:status :failed :task item :error e}
              {:status :retry :task item :error e})))))
    (finally
      (log/debugf "Finished task '%s/%s/%s'." (:name item) (:id item) (:retry-num item)))))

(def ^:private
  sql:select-next-tasks
  "select * from task as t
    where t.scheduled_at <= now()
      and t.queue = ?
      and (t.status = 'new' or t.status = 'retry')
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defn- event-loop-fn*
  [{:keys [tasks executor batch-size] :as opts}]
  (db/with-atomic [conn db/pool]
    (let [queue (:queue opts "default")
          items (->> (db/exec! conn [sql:select-next-tasks queue batch-size])
                     (map decode-task-row)
                     (seq))
          opts  (assoc opts :conn conn)]

      (if (nil? items)
        ::empty
        (let [results (->> items
                           (map #(partial run-task opts %))
                           (map #(px/submit! executor %)))]
          (doseq [res results]
            (let [res (deref res)]
              (case (:status res)
                :retry (mark-as-retry conn res)
                :failed (mark-as-failed conn res)
                :completed (mark-as-completed conn res))))
          ::handled)))))

(defn- event-loop-fn
  [{:keys [executor] :as opts}]
  (aa/thread-call executor #(event-loop-fn* opts)))

(s/def ::batch-size ::us/integer)
(s/def ::poll-interval ::us/integer)
(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::tasks (s/map-of string? ::fn))

(s/def ::start-worker-params
  (s/keys :req-un [::tasks ::aa/executor ::batch-size]
          :opt-un [::poll-interval]))

(defn start-worker!
  [{:keys [poll-interval executor]
    :or {poll-interval 5000}
    :as opts}]
  (us/assert ::start-worker-params opts)
  (log/infof "Starting worker '%s' on queue '%s'."
             (:name opts "anonymous")
             (:queue opts "default"))
  (let [cch (a/chan 1)]
    (a/go-loop []
      (let [[val port] (a/alts! [cch (event-loop-fn opts)] :priority true)]
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port cch) (nil? val))
          (log/infof "Stop condition found. Shutdown worker: '%s'"
                     (:name opts "anonymous"))

          (db/pool-closed? db/pool)
          (do
            (log/info "Worker eventloop is aborted because pool is closed.")
            (a/close! cch))

          (and (instance? java.sql.SQLException val)
               (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState val)))
          (do
            (log/error "Connection error, trying resume in some instants.")
            (a/<! (a/timeout poll-interval))
            (recur))

          (and (instance? java.sql.SQLException val)
               (= "40001" (.getSQLState ^java.sql.SQLException val)))
          (do
            (log/debug "Serialization failure (retrying in some instants).")
            (a/<! (a/timeout 1000))
            (recur))

          (instance? Exception val)
          (do
            (log/errorf val "Unexpected error ocurried on polling the database (will resume operations in some instants). ")
            (a/<! (a/timeout poll-interval))
            (recur))

          (= ::handled val)
          (recur)

          (= ::empty val)
          (do
            (a/<! (a/timeout poll-interval))
            (recur)))))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (a/close! cch)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduled Tasks (cron based) IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private
  sql:upsert-scheduled-task
  "insert into scheduled_task (id, cron_expr)
   values (?, ?)
       on conflict (id)
       do update set cron_expr=?")

(defn- synchronize-schedule-item
  [conn {:keys [id cron] :as item}]
  (let [cron (str cron)]
    (log/debugf "Initialize scheduled task '%s' (cron: '%s')." id cron)
    (db/exec-one! conn [sql:upsert-scheduled-task id cron cron])))

(defn- synchronize-schedule!
  [schedule]
  (db/with-atomic [conn db/pool]
    (run! (partial synchronize-schedule-item conn) schedule)))

(def ^:private sql:lock-scheduled-task
  "select id from scheduled_task where id=? for update skip locked")

(declare schedule-task!)

(defn exception->string
  [error]
  (with-out-str
    (.printStackTrace ^Throwable error (java.io.PrintWriter. *out*))))

(defn- execute-scheduled-task
  [{:keys [scheduler executor] :as opts} {:keys [id cron] :as task}]
  (letfn [(run-task [conn]
            (try
              (when (db/exec-one! conn [sql:lock-scheduled-task id])
                (log/info "Executing scheduled task" id)
                ((:fn task) task))
              (catch Exception e
                e)))

          (handle-task* [conn]
            (let [result (run-task conn)]
              (if (instance? Throwable result)
                (do
                  (log/warnf result "Unhandled exception on scheduled task '%s'." id)
                  (db/insert! conn :scheduled-task-history
                              {:id (uuid/next)
                               :task-id id
                               :is-error true
                               :reason (exception->string result)}))
                (db/insert! conn :scheduled-task-history
                            {:id (uuid/next)
                             :task-id id}))))
          (handle-task []
            (db/with-atomic [conn db/pool]
              (handle-task* conn)))]

    (try
      (px/run! executor handle-task)
      (finally
        (schedule-task! opts task)))))

(defn ms-until-valid
  [cron]
  (s/assert dt/cron? cron)
  (let [^Instant now  (dt/now)
        ^Instant next (dt/next-valid-instant-from cron now)]
    (inst-ms (dt/duration-between now next))))

(defn- schedule-task!
  [{:keys [scheduler] :as opts} {:keys [cron] :as task}]
  (let [ms (ms-until-valid cron)]
    (px/schedule! scheduler ms (partial execute-scheduled-task opts task))))

(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::id string?)
(s/def ::cron dt/cron?)
(s/def ::props (s/nilable map?))
(s/def ::scheduled-task
  (s/keys :req-un [::id ::cron ::fn]
          :opt-un [::props]))

(s/def ::schedule (s/coll-of ::scheduled-task))
(s/def ::start-scheduler-worker-params
  (s/keys :req-un [::schedule]))

(defn start-scheduler-worker!
  [{:keys [schedule] :as opts}]
  (us/assert ::start-scheduler-worker-params opts)
  (let [scheduler (Executors/newScheduledThreadPool (int 1))
        opts      (assoc opts :scheduler scheduler)]
    (synchronize-schedule! schedule)
    (run! (partial schedule-task! opts) schedule)
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.shutdownNow ^ExecutorService scheduler)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Thread Pool
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn thread-pool
  ([] (thread-pool {}))
  ([{:keys [min-threads max-threads idle-timeout name]
     :or {min-threads 0 max-threads 128 idle-timeout 60000}}]
   (let [executor (QueuedThreadPool. max-threads min-threads)]
     (.setName executor (or name "default-tp"))
     (.start executor)
     executor)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn stop!
  [o]
  (cond
    (instance? java.lang.AutoCloseable o)
    (.close ^java.lang.AutoCloseable o)

    (instance? org.eclipse.jetty.util.component.ContainerLifeCycle o)
    (.stop ^org.eclipse.jetty.util.component.ContainerLifeCycle o)

    :else
    (ex/raise :type :not-implemented)))

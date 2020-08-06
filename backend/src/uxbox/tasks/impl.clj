;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.impl
  "Async tasks implementation."
  (:require
   [cuerdas.core :as str]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [promesa.exec :as px]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.util.async :as aa]
   [uxbox.util.blob :as blob]
   [uxbox.util.time :as dt])
  (:import
   java.util.concurrent.ScheduledExecutorService
   java.util.concurrent.Executors
   java.time.Duration
   java.time.Instant
   java.util.Date))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tasks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- string-strack-trace
  [^Throwable err]
  (with-out-str
    (.printStackTrace err (java.io.PrintWriter. *out*))))

(def ^:private
  sql:mark-as-retry
  "update task
      set scheduled_at = clock_timestamp() + '5 seconds'::interval,
          modified_at = clock_timestamp(),
          error = ?,
          status = 'retry',
          retry_num = retry_num + 1
    where id = ?")

(defn- mark-as-retry
  [conn task error]
  (let [explain (ex-message error)
        sqlv [sql:mark-as-retry explain (:id task)]]
    (db/exec-one! conn sqlv)
    nil))

(defn- mark-as-failed
  [conn task error]
  (let [explain (ex-message error)]
    (db/update! conn :task
                {:error explain
                 :modified-at (dt/now)
                 :status "failed"}
                {:id (:id task)})
    nil))

(defn- mark-as-completed
  [conn task]
  (let [now (dt/now)]
    (db/update! conn :task
                {:completed-at now
                 :modified-at now
                 :status "completed"}
                {:id (:id task)})
    nil))


(def ^:private
  sql:select-next-task
  "select * from task as t
    where t.scheduled_at <= now()
      and t.queue = ?
      and (t.status = 'new' or t.status = 'retry')
    order by t.priority desc, t.scheduled_at
    limit 1
      for update skip locked")

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props)))))


(defn- log-task-error
  [item err]
  (log/error (str/format "Unhandled exception on task '%s' (retry: %s)\n" (:name item) (:retry-num item))
             (str/format "Props: %s\n" (pr-str (:props item)))
             (with-out-str
               (.printStackTrace ^Throwable err (java.io.PrintWriter. *out*)))))

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
    (log/debug (str/format "Started task '%s/%s'." (:name item) (:id item)))
    (handle-task tasks item)
    (log/debug (str/format "Finished task '%s/%s'." (:name item) (:id item)))
    (mark-as-completed conn item)
    (catch Exception e
      (log-task-error item e)
      (if (>= (:retry-num item) (:max-retries item))
        (mark-as-failed conn item e)
        (mark-as-retry conn item e)))))

(defn- event-loop-fn
  [{:keys [tasks] :as opts}]
  (aa/thread-try
   (db/with-atomic [conn db/pool]
     (let [queue (:queue opts "default")
           item  (-> (db/exec-one! conn [sql:select-next-task queue])
                     (decode-task-row))
           opts  (assoc opts :conn conn)]

       (cond
         (nil? item)
         ::empty

         (or (= "new" (:status item))
             (= "retry" (:status item)))
         (do
           (run-task opts item)
           ::handled)

         :else
         (do
           (log/warn "Unexpected condition on worker event loop:" (pr-str item))
           ::handled))))))

(s/def ::poll-interval ::us/integer)
(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::tasks (s/map-of string? ::fn))
(s/def ::start-worker-params
  (s/keys :req-un [::tasks]
          :opt-un [::poll-interval]))

(defn start-worker!
  [{:keys [poll-interval]
    :or {poll-interval 5000}
    :as opts}]
  (us/assert ::start-worker-params opts)
  (log/info (str/format "Starting worker '%s' on queue '%s'."
                        (:name opts "anonymous")
                        (:queue opts "default")))
  (let [cch (a/chan 1)]
    (a/go-loop []
      (let [[val port] (a/alts! [cch (event-loop-fn opts)] :priority true)]
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port cch) (nil? val))
          (log/info (str/format "Stop condition found. Shutdown worker: '%s'"
                                (:name opts "anonymous")))

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
            (log/error "Unexpected error ocurried on polling the database." val)
            (log/info "Trying resume operations in some instants.")
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
;; Scheduled Tasks
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
    (db/exec-one! conn [sql:upsert-scheduled-task id cron cron])))

(defn- synchronize-schedule!
  [schedule]
  (db/with-atomic [conn db/pool]
    (run! (partial synchronize-schedule-item conn) schedule)))

(def ^:private sql:lock-scheduled-task
  "select id from scheduled_task where id=? for update skip locked")

(declare schedule-task!)

(defn- log-scheduled-task-error
  [item err]
  (log/error "Unhandled exception on scheduled task '" (:id item) "' \n"
             (with-out-str
               (.printStackTrace ^Throwable err (java.io.PrintWriter. *out*)))))

(defn- execute-scheduled-task
  [{:keys [id cron ::xtor] :as task}]
  (try
    (db/with-atomic [conn db/pool]
      ;; First we try to lock the task in the database, if locking is
      ;; successful, then we execute the scheduled task; if locking is
      ;; not possible (because other instance is already locked id) we
      ;; just skip it and schedule to be executed in the next slot.
      (when (db/exec-one! conn [sql:lock-scheduled-task id])
        (log/info "Executing scheduled task" id)
        ((:fn task) task)))

    (catch Exception e
      (log-scheduled-task-error task e))
    (finally
      (schedule-task! xtor task))))

(defn ms-until-valid
  [cron]
  (s/assert dt/cron? cron)
  (let [^Instant now  (dt/now)
        ^Instant next (dt/next-valid-instant-from cron now)]
    (inst-ms (dt/duration-between now next))))

(defn- schedule-task!
  [xtor {:keys [cron] :as task}]
  (let [ms    (ms-until-valid cron)
        task  (assoc task ::xtor xtor)]
    (px/schedule! xtor ms (partial execute-scheduled-task task))))

(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::id string?)
(s/def ::cron dt/cron?)
;; (s/def ::xtor #(instance? ScheduledExecutorService %))
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
  (let [xtor (Executors/newScheduledThreadPool (int 1))]
    (synchronize-schedule! schedule)
    (run! (partial schedule-task! xtor) schedule)
    (reify
      java.lang.AutoCloseable
      (close [_]
        (.shutdownNow ^ScheduledExecutorService xtor)))))

(defn stop!
  [worker]
  (.close ^java.lang.AutoCloseable worker))

;; --- Submit API

(s/def ::name ::us/string)
(s/def ::delay
  (s/or :int ::us/integer
        :duration dt/duration?))
(s/def ::queue ::us/string)

(s/def ::task-options
  (s/keys :req-un [::name]
          :opt-un [::delay ::props ::queue]))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, clock_timestamp() + ?)
   returning id")

(defn submit!
  [conn {:keys [name delay props queue priority max-retries key]
         :or {delay 0 props {} queue "default" priority 100 max-retries 3}
         :as options}]
  (us/verify ::task-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (db/tjson props)
        id        (uuid/next)]
    (log/info (str/format "Submit task '%s' to be executed in '%s'." name (str duration)))
    (db/exec-one! conn [sql:insert-new-task id name props queue priority max-retries interval])
    id))

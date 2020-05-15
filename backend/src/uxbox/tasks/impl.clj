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
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [promesa.exec :as px]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
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
                 :status "failed"}
                {:id (:id task)})
    nil))

(defn- mark-as-completed
  [conn task]
  (db/update! conn :task
              {:completed-at (dt/now)
               :status "completed"}
              {:id (:id task)})
  nil)

(def ^:private
  sql:select-next-task
  "select * from task as t
    where t.scheduled_at <= now()
      and t.queue = ?
      and (t.status = 'new' or (t.status = 'retry' and t.retry_num <= ?))
    order by t.scheduled_at
    limit 1
      for update skip locked")

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      props (assoc :props (blob/decode props)))))

(defn- log-task-error
  [item err]
  (log/error "Unhandled exception on task '" (:name item)
             "' (retry:" (:retry-num item) ") \n"
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

(defn- event-loop-fn
  [{:keys [tasks] :as options}]
  (let [queue (:queue options "default")
        max-retries (:max-retries options 3)]
    (db/with-atomic [conn db/pool]
      (let [item (-> (db/exec-one! conn [sql:select-next-task queue max-retries])
                     (decode-task-row))]
        (when item
          (log/info "Execute task" (:name item))
          (try
            (handle-task tasks item)
            (mark-as-completed conn item)
            ::handled
            (catch Throwable e
              (log-task-error item e)
              (if (>= (:retry-num item) max-retries)
                (mark-as-failed conn item e)
                (mark-as-retry conn item e)))))))))

(defn- execute-worker-task
  [{:keys [::stop ::xtor poll-interval]
    :or {poll-interval 5000}
    :as opts}]
  (try
    (when-not @stop
      (let [res (event-loop-fn opts)]
        (if (= res ::handled)
          (px/schedule! xtor 0 (partial execute-worker-task opts))
          (px/schedule! xtor poll-interval (partial execute-worker-task opts)))))
    (catch Throwable e
      (log/error "unexpected exception:" e)
      (px/schedule! xtor poll-interval (partial execute-worker-task opts)))))

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

    (catch Throwable e
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::id string?)
(s/def ::name string?)
(s/def ::cron dt/cron?)
(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::props (s/nilable map?))
(s/def ::xtor #(instance? ScheduledExecutorService %))

(s/def ::scheduled-task
  (s/keys :req-un [::id ::cron ::fn]
          :opt-un [::props]))

(s/def ::tasks (s/map-of string? ::fn))
(s/def ::schedule (s/coll-of ::scheduled-task))

(defn start-scheduler-worker!
  [{:keys [schedule xtor] :as opts}]
  (us/assert ::xtor xtor)
  (us/assert ::schedule schedule)
  (let [stop (atom false)]
    (synchronize-schedule! schedule)
    (run! (partial schedule-task! xtor) schedule)
    (reify
      java.lang.AutoCloseable
      (close [_]
        (reset! stop true)))))

(defn start-worker!
  [{:keys [tasks xtor poll-interval]
    :or {poll-interval 5000}
    :as opts}]
  (us/assert ::tasks tasks)
  (us/assert ::xtor xtor)
  (us/assert number? poll-interval)
  (let [stop (atom false)
        opts (assoc opts
                    ::xtor xtor
                    ::stop stop)]
    (px/schedule! xtor poll-interval (partial execute-worker-task opts))
    (reify
      java.lang.AutoCloseable
      (close [_]
        (reset! stop true)))))

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
  "insert into task (id, name, props, queue, scheduled_at)
   values (?, ?, ?, ?, clock_timestamp()+cast(?::text as interval))
   returning id")

(defn- duration->pginterval
  [^Duration d]
  (->> (/ (.toMillis d) 1000.0)
       (format "%s seconds")))

(defn submit!
  [conn {:keys [name delay props queue key]
         :or {delay 0 props {} queue "default"}
         :as options}]
  (us/verify ::task-options options)
  (let [duration   (dt/duration delay)
        pginterval (duration->pginterval duration)
        props      (blob/encode props)
        id         (uuid/next)]
    (log/info "Submit task" name "to be executed in" (str duration))
    (db/exec-one! conn [sql:insert-new-task
                        id name props queue pginterval])
    id))

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
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.util.blob :as blob]
   [uxbox.util.time :as dt])
  (:import
   java.time.Duration
   java.time.Instant
   java.util.Date))

(defrecord Worker [stop]
  java.lang.AutoCloseable
  (close [_] (a/close! stop)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tasks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- string-strack-trace
  [^Throwable err]
  (with-out-str
    (.printStackTrace err (java.io.PrintWriter. *out*))))

(def ^:private sql:mark-as-retry
  "update task
      set scheduled_at = clock_timestamp() + '5 seconds'::interval,
          error = ?,
          status = 'retry',
          retry_num = retry_num + 1
    where id = ?")

(defn- reschedule
  [conn task error]
  (let [explain (ex-message error)
        sqlv [sql:mark-as-retry explain (:id task)]]
    (db/exec-one! conn sqlv)
    nil))

(def ^:private sql:mark-as-failed
  "update task
      set scheduled_at = clock_timestamp() + '5 seconds'::interval,
          error = ?,
          status = 'failed'
    where id = ?;")

(defn- mark-as-failed
  [conn task error]
  (let [explain (ex-message error)
        sqlv [sql:mark-as-failed explain (:id task)]]
    (db/exec-one! conn sqlv)
    nil))

(def ^:private sql:mark-as-completed
  "update task
      set completed_at = clock_timestamp(),
          status = 'completed'
    where id = ?")

(defn- mark-as-completed
  [conn task]
  (db/exec-one! conn [sql:mark-as-completed (:id task)])
  nil)

(defn- handle-task
  [tasks {:keys [name] :as item}]
  (let [task-fn (get tasks name)]
    (if task-fn
      (task-fn item)
      (do
        (log/warn "no task handler found for" (pr-str name))
        nil))))

(def ^:private sql:select-next-task
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
                (reschedule conn item e)))))))))

(defn- start-worker-eventloop!
  [options]
  (let [stop (::stop options)
        mbs  (:max-batch-size options 10)]
    (a/go-loop []
      (let [timeout    (a/timeout 5000)
            [val port] (a/alts! [stop timeout])]
        (when (= port timeout)
          (a/<! (a/thread
                  ;; Tasks batching in one event loop execution.
                  (loop [cnt 1
                         res (event-loop-fn options)]
                    (when (and (= res ::handled)
                               (> mbs cnt))
                      (recur (inc 1)
                             (event-loop-fn options))))))
          (recur))))))

(defn- duration->pginterval
  [^Duration d]
  (->> (/ (.toMillis d) 1000.0)
       (format "%s seconds")))

(defn start-worker!
  [options]
  (let [stop (a/chan)]
    (start-worker-eventloop! (assoc options ::stop stop))
    (->Worker stop)))

(defn stop!
  [worker]
  (.close ^java.lang.AutoCloseable worker))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduled Tasks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (def ^:privatr sql:upsert-scheduled-task
;;   "insert into scheduled_task (id, cron_expr)
;;    values ($1, $2)
;;        on conflict (id)
;;        do update set cron_expr=$2")

;; (defn- synchronize-schedule-item
;;   [conn {:keys [id cron]}]
;;   (-> (db/query-one conn [sql:upsert-scheduled-task id (str cron)])
;;       (p/then' (constantly nil))))

;; (defn- synchronize-schedule
;;   [schedule]
;;   (db/with-atomic [conn db/pool]
;;     (p/run! (partial synchronize-schedule-item conn) schedule)))

;; (def ^:private sql:lock-scheduled-task
;;   "select id from scheduled_task where id=$1 for update skip locked")

;; (declare schedule-task)

;; (defn- log-scheduled-task-error
;;   [item err]
;;   (log/error "Unhandled exception on scheduled task '" (:id item) "' \n"
;;              (with-out-str
;;                (.printStackTrace ^Throwable err (java.io.PrintWriter. *out*)))))

;; (defn- execute-scheduled-task
;;   [{:keys [id cron] :as stask}]
;;   (db/with-atomic [conn db/pool]
;;     ;; First we try to lock the task in the database, if locking us
;;     ;; successful, then we execute the scheduled task; if locking is
;;     ;; not possible (because other instance is already locked id) we
;;     ;; just skip it and schedule to be executed in the next slot.
;;     (-> (db/query-one conn [sql:lock-scheduled-task id])
;;         (p/then (fn [result]
;;                   (when result
;;                     (-> (p/do! ((:fn stask) stask))
;;                         (p/catch (fn [e]
;;                                    (log-scheduled-task-error stask e)
;;                                    nil))))))
;;         (p/finally (fn [v e]
;;                      (-> (vu/current-context)
;;                          (schedule-task stask)))))))
;; (defn ms-until-valid
;;   [cron]
;;   (s/assert dt/cron? cron)
;;   (let [^Instant now (dt/now)
;;         ^Instant next (dt/next-valid-instant-from cron now)
;;         ^Duration duration (Duration/between now next)]
;;     (.toMillis duration)))

;; (defn- schedule-task
;;   [ctx {:keys [cron] :as stask}]
;;   (let [ms (ms-until-valid cron)]
;;     (vt/schedule! ctx (assoc stask
;;                              :ctx ctx
;;                              ::vt/once true
;;                              ::vt/delay ms
;;                              ::vt/fn execute-scheduled-task))))

;; (defn- on-scheduler-start
;;   [ctx {:keys [schedule] :as options}]
;;   (-> (synchronize-schedule schedule)
;;       (p/then' (fn [_]
;;                  (run! #(schedule-task ctx %) schedule)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Worker Verticle

;; (s/def ::callable (s/or :fn fn? :var var?))
;; (s/def ::max-batch-size ::us/integer)
;; (s/def ::max-retries ::us/integer)
;; (s/def ::tasks (s/map-of string? ::callable))

;; (s/def ::worker-verticle-options
;;   (s/keys :req-un [::tasks]
;;           :opt-un [::queue ::max-batch-size]))

;; (defn worker-verticle
;;   [options]
;;   (s/assert ::worker-verticle-options options)
;;   (let [on-start #(on-worker-start % options)]
;;     (vc/verticle {:on-start on-start})))

;; --- Scheduler Verticle

;; (s/def ::id string?)
;; (s/def ::cron dt/cron?)
;; (s/def ::fn ::callable)
;; (s/def ::props (s/nilable map?))

;; (s/def ::scheduled-task
;;   (s/keys :req-un [::id ::cron ::fn]
;;           :opt-un [::props]))

;; (s/def ::schedule (s/coll-of ::scheduled-task))

;; (s/def ::scheduler-verticle-options
;;   (s/keys :opt-un [::schedule]))

;; (defn scheduler-verticle
;;   [options]
;;   (s/assert ::scheduler-verticle-options options)
;;   (let [on-start #(on-scheduler-start % options)]
;;     (vc/verticle {:on-start on-start})))

;; --- Schedule API

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

(defn schedule!
  [conn {:keys [name delay props queue key]
         :or {delay 0 props {} queue "default"}
         :as options}]
  (us/verify ::task-options options)
  (let [duration   (dt/duration delay)
        pginterval (duration->pginterval duration)
        props      (blob/encode props)
        id         (uuid/next)]
    (log/info "Schedule task" name
              ;; "with props" (pr-str props)
              "to be executed in" (str duration))
    (db/exec-one! conn [sql:insert-new-task
                        id name props queue pginterval])
    id))

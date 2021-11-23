;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker
  "Async tasks abstraction (impl)."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.async :as aa]
   [app.util.time :as dt]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   org.eclipse.jetty.util.thread.QueuedThreadPool
   java.util.concurrent.ExecutorService
   java.util.concurrent.Executors
   java.util.concurrent.Executor))

(s/def ::executor #(instance? Executor %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::name keyword?)
(s/def ::min-threads ::us/integer)
(s/def ::max-threads ::us/integer)
(s/def ::idle-timeout ::us/integer)

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :req-un [::min-threads ::max-threads ::idle-timeout ::name]))

(defmethod ig/init-key ::executor
  [_ {:keys [min-threads max-threads idle-timeout name]}]
  (doto (QueuedThreadPool. (int max-threads)
                           (int min-threads)
                           (int idle-timeout))
    (.setStopTimeout 500)
    (.setName (d/name name))
    (.start)))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (.stop ^QueuedThreadPool instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare event-loop-fn)
(declare instrument-tasks)

(s/def ::queue keyword?)
(s/def ::parallelism ::us/integer)
(s/def ::batch-size ::us/integer)
(s/def ::tasks (s/map-of keyword? fn?))
(s/def ::poll-interval ::dt/duration)

(defmethod ig/pre-init-spec ::worker [_]
  (s/keys :req-un [::executor
                   ::mtx/metrics
                   ::db/pool
                   ::batch-size
                   ::name
                   ::poll-interval
                   ::queue
                   ::tasks]))

(defmethod ig/prep-key ::worker
  [_ cfg]
  (d/merge {:batch-size 2
            :name :worker
            :poll-interval (dt/duration {:seconds 5})
            :queue :default}
           (d/without-nils cfg)))

(defmethod ig/init-key ::worker
  [_ {:keys [pool poll-interval name queue] :as cfg}]
  (l/info :action "start worker"
          :name (d/name name)
          :queue (d/name queue))
  (let [close-ch (a/chan 1)
        poll-ms  (inst-ms poll-interval)]
    (a/go-loop []
      (let [[val port] (a/alts! [close-ch (event-loop-fn cfg)] :priority true)]
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port close-ch) (nil? val))
          (l/debug :hint "stop condition found")

          (db/pool-closed? pool)
          (do
            (l/debug :hint "eventloop aborted because pool is closed")
            (a/close! close-ch))

          (and (instance? java.sql.SQLException val)
               (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState ^java.sql.SQLException val)))
          (do
            (l/error :hint "connection error, trying resume in some instants")
            (a/<! (a/timeout poll-interval))
            (recur))

          (and (instance? java.sql.SQLException val)
               (= "40001" (.getSQLState ^java.sql.SQLException val)))
          (do
            (l/debug :hint "serialization failure (retrying in some instants)")
            (a/<! (a/timeout poll-ms))
            (recur))

          (instance? Exception val)
          (do
            (l/error :cause val
                     :hint "unexpected error ocurried on polling the database (will resume in some instants)")
            (a/<! (a/timeout poll-ms))
            (recur))

          (= ::handled val)
          (recur)

          (= ::empty val)
          (do
            (a/<! (a/timeout poll-ms))
            (recur)))))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (a/close! close-ch)))))


(defmethod ig/halt-key! ::worker
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))

;; --- SUBMIT

(s/def ::task keyword?)
(s/def ::delay (s/or :int ::us/integer :duration dt/duration?))
(s/def ::conn some?)
(s/def ::priority ::us/integer)
(s/def ::max-retries ::us/integer)

(s/def ::submit-options
  (s/keys :req [::task ::conn]
          :opt [::delay ::queue ::priority ::max-retries]))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, clock_timestamp() + ?)
   returning id")

(defn- extract-props
  [options]
  (persistent!
   (reduce-kv (fn [res k v]
                (cond-> res
                  (not (qualified-keyword? k))
                  (assoc! k v)))
              (transient {})
              options)))

(defn submit!
  [{:keys [::task ::delay ::queue ::priority ::max-retries ::conn]
    :or {delay 0 queue :default priority 100 max-retries 3}
    :as options}]
  (us/verify ::submit-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (-> options extract-props db/tjson)
        id        (uuid/next)]
    (l/debug :action "submit task"
             :name (d/name task)
             :in duration)
    (db/exec-one! conn [sql:insert-new-task id (d/name task) props (d/name queue) priority max-retries interval])
    id))


;; --- RUNNER

(def ^:private
  sql:mark-as-retry
  "update task
      set scheduled_at = clock_timestamp() + ?::interval,
          modified_at = clock_timestamp(),
          error = ?,
          status = 'retry',
          retry_num = retry_num + ?
    where id = ?")

(def default-delay
  (dt/duration {:seconds 10}))

(defn- mark-as-retry
  [conn {:keys [task error inc-by delay]
         :or {inc-by 1 delay default-delay}}]
  (let [explain (ex-message error)
        delay   (db/interval delay)
        sqlv    [sql:mark-as-retry delay explain inc-by (:id task)]]
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
  [conn {:keys [task] :as cfg}]
  (let [now (dt/now)]
    (db/update! conn :task
                {:completed-at now
                 :modified-at now
                 :status "completed"}
                {:id (:id task)})
    nil))

(defn- decode-task-row
  [{:keys [props name] :as row}]
  (when row
    (cond-> row
      (db/pgobject? props) (assoc :props (db/decode-transit-pgobject props))
      (string? name)       (assoc :name  (keyword name)))))

(defn- handle-task
  [tasks {:keys [name] :as item}]
  (let [task-fn (get tasks name)]
    (if task-fn
      (task-fn item)
      (l/warn :hint "no task handler found"
              :name (d/name name)))
    {:status :completed :task item}))

(defn get-error-context
  [error item]
  (let [edata (ex-data error)]
    {:id      (uuid/next)
     :data    edata
     :params  item}))

(defn- handle-exception
  [error item]
  (let [edata (ex-data error)]
    (if (and (< (:retry-num item)
                (:max-retries item))
             (= ::retry (:type edata)))
      (cond-> {:status :retry :task item :error error}
        (dt/duration? (:delay edata))
        (assoc :delay (:delay edata))

        (= ::noop (:strategy edata))
        (assoc :inc-by 0))

      (let [cdata (get-error-context error item)]
        (l/update-thread-context! cdata)
        (l/error :cause error
                 :hint "unhandled exception on task"
                 :id (:id cdata))

        (if (>= (:retry-num item) (:max-retries item))
          {:status :failed :task item :error error}
          {:status :retry :task item :error error})))))

(defn- run-task
  [{:keys [tasks]} item]
  (let [name (d/name (:name item))]
    (try
      (l/debug :action "execute task"
               :id (:id item)
               :name name
               :retry (:retry-num item))
      (handle-task tasks item)
      (catch Exception e
        (handle-exception e item)))))

(def sql:select-next-tasks
  "select * from task as t
    where t.scheduled_at <= now()
      and t.queue = ?
      and (t.status = 'new' or t.status = 'retry')
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defn- event-loop-fn*
  [{:keys [pool executor batch-size] :as cfg}]
  (db/with-atomic [conn pool]
    (let [queue (name (:queue cfg))
          items (->> (db/exec! conn [sql:select-next-tasks queue batch-size])
                     (map decode-task-row)
                     (seq))
          cfg  (assoc cfg :conn conn)]

      (if (nil? items)
        ::empty
        (let [proc-xf (comp (map #(partial run-task cfg %))
                            (map #(px/submit! executor %)))]
          (->> (into [] proc-xf items)
               (map deref)
               (run! (fn [res]
                       (case (:status res)
                         :retry (mark-as-retry conn res)
                         :failed (mark-as-failed conn res)
                         :completed (mark-as-completed conn res)))))
          ::handled)))))

(defn- event-loop-fn
  [{:keys [executor] :as cfg}]
  (aa/thread-call executor #(event-loop-fn* cfg)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare schedule-task)
(declare synchronize-schedule)

(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::id keyword?)
(s/def ::cron dt/cron?)
(s/def ::props (s/nilable map?))
(s/def ::task keyword?)

(s/def ::scheduled-task
  (s/keys :req-un [::cron ::task]
          :opt-un [::props ::id]))

(s/def ::schedule (s/coll-of (s/nilable ::scheduled-task)))

(defmethod ig/pre-init-spec ::scheduler [_]
  (s/keys :req-un [::executor ::db/pool ::schedule ::tasks]))

(defmethod ig/init-key ::scheduler
  [_ {:keys [schedule tasks] :as cfg}]
  (let [scheduler (Executors/newScheduledThreadPool (int 1))
        schedule  (->> schedule
                       (filter some?)
                       ;; If id is not defined, use the task as id.
                       (map (fn [{:keys [id task] :as item}]
                              (if (some? id)
                                (assoc item :id (d/name id))
                                (assoc item :id (d/name task)))))
                       (map (fn [{:keys [task] :as item}]
                              (let [f (get tasks task)]
                                (when-not f
                                  (ex/raise :type :internal
                                            :code :task-not-found
                                            :hint (str/fmt "task %s not configured" task)))
                                (-> item
                                    (dissoc :task)
                                    (assoc :fn f))))))
        cfg       (assoc cfg
                         :scheduler scheduler
                         :schedule schedule)]

    (synchronize-schedule cfg)
    (run! (partial schedule-task cfg)
          (filter some? schedule))

    (reify
      java.lang.AutoCloseable
      (close [_]
        (.shutdownNow ^ExecutorService scheduler)))))

(defmethod ig/halt-key! ::scheduler
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))

(def sql:upsert-scheduled-task
  "insert into scheduled_task (id, cron_expr)
   values (?, ?)
       on conflict (id)
       do update set cron_expr=?")

(defn- synchronize-schedule-item
  [conn {:keys [id cron]}]
  (let [cron (str cron)]
    (l/debug :action "initialize scheduled task" :id id :cron cron)
    (db/exec-one! conn [sql:upsert-scheduled-task id cron cron])))

(defn- synchronize-schedule
  [{:keys [pool schedule]}]
  (db/with-atomic [conn pool]
    (run! (partial synchronize-schedule-item conn) schedule)))

(def sql:lock-scheduled-task
  "select id from scheduled_task where id=? for update skip locked")

(defn exception->string
  [error]
  (with-out-str
    (.printStackTrace ^Throwable error (java.io.PrintWriter. *out*))))

(defn- execute-scheduled-task
  [{:keys [executor pool] :as cfg} {:keys [id] :as task}]
  (letfn [(run-task [conn]
            (try
              (when (db/exec-one! conn [sql:lock-scheduled-task (d/name id)])
                (l/debug :action "execute scheduled task" :id id)
                ((:fn task) task))
              (catch Throwable e
                e)))

          (handle-task []
            (db/with-atomic [conn pool]
              (let [result (run-task conn)]
                (when (ex/exception? result)
                  (l/error :cause result
                           :hint "unhandled exception on scheduled task"
                           :id id)))))]

    (try
      (px/run! executor handle-task)
      (finally
        (schedule-task cfg task)))))

(defn- ms-until-valid
  [cron]
  (s/assert dt/cron? cron)
  (let [now  (dt/now)
        next (dt/next-valid-instant-from cron now)]
    (inst-ms (dt/diff now next))))

(defn- schedule-task
  [{:keys [scheduler] :as cfg} {:keys [cron] :as task}]
  (let [ms (ms-until-valid cron)]
    (px/schedule! scheduler ms (partial execute-scheduled-task cfg task))))

;; --- INSTRUMENTATION

(defn instrument!
  [registry]
  (mtx/instrument-vars!
   [#'submit!]
   {:registry registry
    :type :counter
    :labels ["name"]
    :name "tasks_submit_total"
    :help "A counter of task submissions."
    :wrap (fn [rootf mobj]
            (let [mdata (meta rootf)
                  origf (::original mdata rootf)]
              (with-meta
                (fn [conn params]
                  (let [tname (:name params)]
                    (mobj :inc [tname])
                    (origf conn params)))
                {::original origf})))})

  (mtx/instrument-vars!
   [#'app.worker/run-task]
   {:registry registry
    :type :summary
    :quantiles []
    :name "tasks_checkout_timing"
    :help "Latency measured between scheduled_at and execution time."
    :wrap (fn [rootf mobj]
            (let [mdata (meta rootf)
                  origf (::original mdata rootf)]
              (with-meta
                (fn [tasks item]
                  (let [now (inst-ms (dt/now))
                        sat (inst-ms (:scheduled-at item))]
                    (mobj :observe (- now sat))
                    (origf tasks item)))
                {::original origf})))}))


(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req-un [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [metrics tasks]}]
  (let [mobj (mtx/create
              {:registry (:registry metrics)
               :type :summary
               :labels ["name"]
               :quantiles []
               :name "tasks_timing"
               :help "Background task execution timing."})]
    (reduce-kv (fn [res k v]
                 (let [tname (name k)]
                   (l/debug :action "register task" :name tname)
                   (assoc res k (mtx/wrap-summary v mobj [tname]))))
               {}
               tasks)))

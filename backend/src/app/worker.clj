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
   java.util.concurrent.ExecutorService
   java.util.concurrent.ForkJoinPool
   java.util.concurrent.ForkJoinWorkerThread
   java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
   java.util.concurrent.atomic.AtomicLong
   java.util.concurrent.Executors))

(set! *warn-on-reflection* true)

(s/def ::executor #(instance? ExecutorService %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::prefix keyword?)
(s/def ::parallelism ::us/integer)
(s/def ::min-threads ::us/integer)
(s/def ::max-threads ::us/integer)
(s/def ::idle-timeout ::us/integer)

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :req-un [::prefix ::parallelism]))

(defn- get-thread-factory
  ^ForkJoinPool$ForkJoinWorkerThreadFactory
  [prefix counter]
  (reify ForkJoinPool$ForkJoinWorkerThreadFactory
    (newThread [_ pool]
      (let [^ForkJoinWorkerThread thread (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)
            ^String thread-name (str (name prefix) "-" (.getAndIncrement ^AtomicLong counter))]
        (.setName thread thread-name)
        thread))))

(defmethod ig/init-key ::executor
  [_ {:keys [parallelism prefix]}]
  (let [counter (AtomicLong. 0)]
    (ForkJoinPool. (int parallelism) (get-thread-factory prefix counter) nil false)))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (.shutdown ^ForkJoinPool instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor Monitor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::executors (s/map-of keyword? ::executor))

(defmethod ig/pre-init-spec ::executors-monitor [_]
  (s/keys :req-un [::executors ::mtx/metrics]))

(defmethod ig/init-key ::executors-monitor
  [_ {:keys [executors metrics interval] :or {interval 2500}}]
  (letfn [(log-stats [scheduler]
            (doseq [[key ^ForkJoinPool executor] executors]
              (let [labels (into-array String [(name key)])]
                (mtx/run! metrics {:id :executors-active-threads
                                   :labels labels
                                   :val (.getPoolSize executor)})
                (mtx/run! metrics {:id :executors-running-threads
                                   :labels labels
                                   :val (.getRunningThreadCount executor)})
                (mtx/run! metrics {:id :executors-queued-submissions
                                   :labels labels
                                   :val (.getQueuedSubmissionCount executor)})))

            (when-not (.isShutdown scheduler)
              (px/schedule! scheduler interval (partial log-stats scheduler))))]

    (let [scheduler (px/scheduled-pool 1)]
      (px/schedule! scheduler interval (partial log-stats scheduler))
      scheduler)))

(defmethod ig/halt-key! ::executors-monitor
  [_ instance]
  (.shutdown ^ExecutorService instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare event-loop-fn)
(declare event-loop)

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

(defn- event-loop
  "Main, worker eventloop"
  [{:keys [pool poll-interval close-ch] :as cfg}]
  (let [poll-ms (inst-ms poll-interval)]
    (a/go-loop []
      (let [[val port] (a/alts! [close-ch (event-loop-fn cfg)] :priority true)]
        (cond
          ;; Terminate the loop if close channel is closed or
          ;; event-loop-fn returns nil.
          (or (= port close-ch) (nil? val))
          (l/debug :hint "stop condition found")

          (db/closed? pool)
          (do
            (l/debug :hint "eventloop aborted because pool is closed")
            (a/close! close-ch))

          (and (instance? java.sql.SQLException val)
               (contains? #{"08003" "08006" "08001" "08004"} (.getSQLState ^java.sql.SQLException val)))
          (do
            (l/warn :hint "connection error, trying resume in some instants")
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
            (l/warn :cause val
                    :hint "unexpected error ocurried on polling the database (will resume in some instants)")
            (a/<! (a/timeout poll-ms))
            (recur))

          (= ::handled val)
          (recur)

          (= ::empty val)
          (do
            (a/<! (a/timeout poll-ms))
            (recur)))))))

(defmethod ig/init-key ::worker
  [_ {:keys [pool name queue] :as cfg}]
  (let [close-ch (a/chan 1)
        cfg      (assoc cfg :close-ch close-ch)]
    (if (db/read-only? pool)
      (l/warn :hint "worker not started, db is read-only"
              :name (d/name name)
              :queue (d/name queue))
      (do
        (l/info :hint "worker started"
                :name (d/name name)
                :queue (d/name queue))
        (event-loop cfg)))

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
  (let [data (ex-data error)]
    (merge
     {:hint          (ex-message error)
      :spec-problems (some->> data ::s/problems (take 10) seq vec)
      :spec-value    (some->> data ::s/value)
      :data          (some-> data (dissoc ::s/problems ::s/value ::s/spec))
      :params        item}
     (when (and data (::s/problems data))
       {:spec-explain (us/pretty-explain data)}))))

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
      (do
        (l/error :hint "unhandled exception on task"
                 ::l/context (get-error-context error item)
                 :cause error)
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
  [_ {:keys [schedule tasks pool] :as cfg}]
  (let [scheduler (Executors/newScheduledThreadPool (int 1))]
    (if (db/read-only? pool)
      (l/warn :hint "scheduler not started, db is read-only")
      (let [schedule  (->> schedule
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
        (l/info :hint "scheduler started"
                :registred-tasks (count schedule))

        (synchronize-schedule cfg)
        (run! (partial schedule-task cfg)
              (filter some? schedule))))

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

(defn- execute-scheduled-task
  [{:keys [executor pool] :as cfg} {:keys [id] :as task}]
  (letfn [(run-task [conn]
            (when (db/exec-one! conn [sql:lock-scheduled-task (d/name id)])
              (l/debug :action "execute scheduled task" :id id)
              ((:fn task) task)))

          (handle-task []
            (try
              (db/with-atomic [conn pool]
                (run-task conn))
              (catch Throwable cause
                (l/error :hint "unhandled exception on scheduled task"
                         ::l/context (get-error-context cause task)
                         :task-id id
                         :cause cause))))]
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

(defn- wrap-task-handler
  [metrics tname f]
  (let [labels (into-array String [tname])]
    (fn [params]
      (let [start (System/nanoTime)]
        (try
          (f params)
          (finally
            (mtx/run! metrics
                      {:id :tasks-timing
                       :val (/ (- (System/nanoTime) start) 1000000)
                       :labels labels})))))))

(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req-un [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [metrics tasks]}]
  (reduce-kv (fn [res k v]
               (let [tname (name k)]
                 (l/debug :hint "register task" :name tname)
                 (assoc res k (wrap-task-handler metrics tname v))))
             {}
             tasks))

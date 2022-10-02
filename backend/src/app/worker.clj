;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
   java.util.concurrent.Executors
   java.util.concurrent.ForkJoinPool
   java.util.concurrent.Future
   java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
   java.util.concurrent.ForkJoinWorkerThread
   java.util.concurrent.ScheduledExecutorService
   java.util.concurrent.ThreadFactory
   java.util.concurrent.atomic.AtomicLong))

(set! *warn-on-reflection* true)

(s/def ::executor #(instance? ExecutorService %))
(s/def ::scheduler #(instance? ScheduledExecutorService %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private get-fj-thread-factory)
(declare ^:private get-thread-factory)

(s/def ::parallelism ::us/integer)

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :opt-un [::parallelism]))

(defmethod ig/init-key ::executor
  [skey {:keys [parallelism]}]
  (let [prefix (if (vector? skey) (-> skey first name keyword) :default)]
    (if parallelism
      (ForkJoinPool. (int parallelism) (get-fj-thread-factory prefix) nil false)
      (Executors/newCachedThreadPool (get-thread-factory prefix)))))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (.shutdown ^ExecutorService instance))

(defmethod ig/pre-init-spec ::scheduler [_]
  (s/keys :req-un [::prefix]
          :opt-un [::parallelism]))

(defmethod ig/init-key ::scheduler
  [_ {:keys [parallelism prefix] :or {parallelism 1}}]
  (px/scheduled-pool parallelism (get-thread-factory prefix)))

(defmethod ig/halt-key! ::scheduler
  [_ instance]
  (.shutdown ^ExecutorService instance))

(defn- get-fj-thread-factory
  ^ForkJoinPool$ForkJoinWorkerThreadFactory
  [prefix]
  (let [^AtomicLong counter (AtomicLong. 0)]
    (reify ForkJoinPool$ForkJoinWorkerThreadFactory
      (newThread [_ pool]
        (let [thread (.newThread ForkJoinPool/defaultForkJoinWorkerThreadFactory pool)
              tname  (str "penpot/" (name prefix) "-" (.getAndIncrement counter))]
          (.setName ^ForkJoinWorkerThread thread ^String tname)
          thread)))))

(defn- get-thread-factory
  ^ThreadFactory
  [prefix]
  (let [^AtomicLong counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable)
          (.setDaemon true)
          (.setName (str "penpot/" (name prefix) "-" (.getAndIncrement counter))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor Monitor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::executors
  (s/map-of keyword? ::executor))

(defmethod ig/pre-init-spec ::executor-monitor [_]
  (s/keys :req-un [::executors ::mtx/metrics]))

(defmethod ig/init-key ::executor-monitor
  [_ {:keys [executors metrics interval] :or {interval 3000}}]
  (letfn [(monitor! [state skey ^ForkJoinPool executor]
            (let [prev-steals (get state skey 0)
                  running     (.getRunningThreadCount executor)
                  queued      (.getQueuedSubmissionCount executor)
                  active      (.getPoolSize executor)
                  steals      (.getStealCount executor)
                  labels      (into-array String [(name skey)])

                  steals-increment (- steals prev-steals)
                  steals-increment (if (neg? steals-increment) 0 steals-increment)]

              (mtx/run! metrics
                        :id :executor-active-threads
                        :labels labels
                        :val active)
              (mtx/run! metrics
                        :id :executor-running-threads
                        :labels labels :val running)
              (mtx/run! metrics
                        :id :executors-queued-submissions
                        :labels labels
                        :val queued)
              (mtx/run! metrics
                          :id :executors-completed-tasks
                          :labels labels
                          :inc steals-increment)

              (aa/thread-sleep interval)
              (if (.isShutdown executor)
                (l/debug :hint "stopping monitor; cause: executor is shutdown")
                (assoc state skey steals))))

          (monitor-fn []
            (try
              (loop [items (into (d/queue) executors)
                     state {}]
                (when-let [[skey executor :as item] (peek items)]
                  (if-let [state (monitor! state skey executor)]
                    (recur (conj items item) state)
                    (recur items state))))
              (catch InterruptedException _cause
                (l/debug :hint "stopping monitor; interrupted"))))]

    (let [thread (Thread. monitor-fn)]
      (.setDaemon thread true)
      (.setName thread "penpot/executor-monitor")
      (.start thread)

      thread)))

(defmethod ig/halt-key! ::executor-monitor
  [_ thread]
  (.interrupt ^Thread thread))

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
            (l/warn :hint "unexpected error ocurried on polling the database (will resume in some instants)" :cause val)
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
        (l/info :hint "worker initialized"
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

(defn- extract-props
  [options]
  (persistent!
   (reduce-kv (fn [res k v]
                (cond-> res
                  (not (qualified-keyword? k))
                  (assoc! k v)))
              (transient {})
              options)))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, now() + ?)
   returning id")

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

    (db/exec-one! conn [sql:insert-new-task id (d/name task) props
                        (d/name queue) priority max-retries interval])
    id))

;; --- RUNNER

(def ^:private
  sql:mark-as-retry
  "update task
      set scheduled_at = now() + ?::interval,
          modified_at = now(),
          error = ?,
          status = 'retry',
          retry_num = ?
    where id = ?")

(defn- mark-as-retry
  [conn {:keys [task error inc-by delay]
         :or {inc-by 1 delay 1000}}]
  (let [explain (ex-message error)
        nretry  (+ (:retry-num task) inc-by)
        delay   (->> (iterate #(* % 2) delay) (take nretry) (last))
        sqlv    [sql:mark-as-retry (db/interval delay) explain nretry (:id task)]]
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
      (l/trace :action "execute task"
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
                         :retry     (mark-as-retry conn res)
                         :failed    (mark-as-failed conn res)
                         :completed (mark-as-completed conn res)))))
          ::handled)))))

(defn- event-loop-fn
  [{:keys [executor] :as cfg}]
  (aa/thread-call executor #(event-loop-fn* cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare schedule-cron-task)
(declare synchronize-cron-entries!)

(s/def ::fn (s/or :var var? :fn fn?))
(s/def ::id keyword?)
(s/def ::cron dt/cron?)
(s/def ::props (s/nilable map?))
(s/def ::task keyword?)

(s/def ::cron-task
  (s/keys :req-un [::cron ::task]
          :opt-un [::props ::id]))

(s/def ::entries (s/coll-of (s/nilable ::cron-task)))

(defmethod ig/pre-init-spec ::cron [_]
  (s/keys :req-un [::executor ::scheduler ::db/pool ::entries ::tasks]))

(defmethod ig/init-key ::cron
  [_ {:keys [entries tasks pool] :as cfg}]
  (if (db/read-only? pool)
    (l/warn :hint "scheduler not started, db is read-only")
    (let [running (atom #{})
          entries (->> entries
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

          cfg     (assoc cfg :entries entries :running running)]

        (l/info :hint "cron initialized" :tasks (count entries))
        (synchronize-cron-entries! cfg)

        (->> (filter some? entries)
             (run! (partial schedule-cron-task cfg)))

        (reify
          clojure.lang.IDeref
          (deref [_] @running)

          java.lang.AutoCloseable
          (close [_]
            (doseq [item @running]
              (when-not (.isDone ^Future item)
                (.cancel ^Future item true))))))))


(defmethod ig/halt-key! ::cron
  [_ instance]
  (when instance
    (.close ^java.lang.AutoCloseable instance)))

(def sql:upsert-cron-task
  "insert into scheduled_task (id, cron_expr)
   values (?, ?)
       on conflict (id)
       do update set cron_expr=?")

(defn- synchronize-cron-entries!
  [{:keys [pool entries]}]
  (db/with-atomic [conn pool]
    (doseq [{:keys [id cron]} entries]
      (l/trace :hint "register cron task" :id id :cron (str cron))
      (db/exec-one! conn [sql:upsert-cron-task id (str cron) (str cron)]))))

(def sql:lock-cron-task
  "select id from scheduled_task where id=? for update skip locked")

(defn- execute-cron-task
  [{:keys [executor pool] :as cfg} {:keys [id] :as task}]
  (letfn [(run-task [conn]
            (when (db/exec-one! conn [sql:lock-cron-task (d/name id)])
              (l/trace :hint "execute cron task" :id id)
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

      (px/run! executor handle-task)
      (px/run! executor #(schedule-cron-task cfg task))
      nil))

(defn- ms-until-valid
  [cron]
  (s/assert dt/cron? cron)
  (let [now  (dt/now)
        next (dt/next-valid-instant-from cron now)]
    (inst-ms (dt/diff now next))))

(def ^:private
  xf-without-done
  (remove #(.isDone ^Future %)))

(defn- schedule-cron-task
  [{:keys [scheduler running] :as cfg} {:keys [cron] :as task}]
  (let [ft (px/schedule! scheduler
                         (ms-until-valid cron)
                         (partial execute-cron-task cfg task))]
    (swap! running #(into #{ft} xf-without-done %))))

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
  (l/info :hint "registry initialized" :tasks (count tasks))
  (reduce-kv (fn [res k v]
               (let [tname (name k)]
                 (l/debug :hint "register task" :name tname)
                 (assoc res k (wrap-task-handler metrics tname v))))
             {}
             tasks))

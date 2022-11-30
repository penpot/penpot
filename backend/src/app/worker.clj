;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker
  "Async tasks abstraction (impl)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   java.util.concurrent.ExecutorService
   java.util.concurrent.ForkJoinPool
   java.util.concurrent.Future
   java.util.concurrent.ScheduledExecutorService))

(set! *warn-on-reflection* true)

(s/def ::executor #(instance? ExecutorService %))
(s/def ::scheduled-executor #(instance? ScheduledExecutorService %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::parallelism ::us/integer)

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :req [::parallelism]))

(defmethod ig/init-key ::executor
  [skey {:keys [::parallelism]}]
  (let [prefix  (if (vector? skey) (-> skey first name keyword) :default)
        tname   (str "penpot/" prefix "/%s")
        factory (px/forkjoin-thread-factory :name tname)]
    (px/forkjoin-executor
     :factory factory
     :parallelism parallelism
     :async? true)))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (px/shutdown! instance))

(defmethod ig/pre-init-spec ::scheduled-executor [_]
  (s/keys :req [::parallelism]))

(defmethod ig/init-key ::scheduled-executor
  [_ {:keys [::parallelism]}]
  (px/scheduled-executor
   :parallelism parallelism
   :factory (px/thread-factory :name "penpot/scheduled-executor/%s")))

(defmethod ig/halt-key! ::scheduled-executor
  [_ instance]
  (px/shutdown! instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASKS REGISTRY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wrap-task-handler
  [metrics tname f]
  (let [labels (into-array String [tname])]
    (fn [params]
      (let [tp (dt/tpoint)]
        (try
          (f params)
          (finally
            (mtx/run! metrics
                      {:id :tasks-timing
                       :val (inst-ms (tp))
                       :labels labels})))))))

(s/def ::registry (s/map-of ::us/string fn?))

(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req-un [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [metrics tasks]}]
  (l/info :hint "registry initialized" :tasks (count tasks))
  (reduce-kv (fn [registry k v]
               (let [tname (name k)]
                 (l/debug :hint "register task" :name tname)
                 (assoc registry tname (wrap-task-handler metrics tname v))))
             {}
             tasks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXECUTOR MONITOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::name ::us/keyword)

(defmethod ig/pre-init-spec ::monitor [_]
  (s/keys :req [::name ::executor ::mtx/metrics]))

(defmethod ig/prep-key ::monitor
  [_ cfg]
  (merge {::interval (dt/duration "2s")}
         (d/without-nils cfg)))

(defmethod ig/init-key ::monitor
  [_ {:keys [::executor ::mtx/metrics ::interval ::name]}]
  (letfn [(monitor! [^ForkJoinPool executor prev-steals]
            (let [running   (.getRunningThreadCount executor)
                  queued    (.getQueuedSubmissionCount executor)
                  active    (.getPoolSize executor)
                  steals    (.getStealCount executor)
                  labels    (into-array String [(d/name name)])

                  steals-inc (- steals prev-steals)
                  steals-inc (if (neg? steals-inc) 0 steals-inc)]

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
                        :inc steals-inc)

              steals))]

    (px/thread
      {:name "penpot/executors-monitor"}
      (l/info :hint "monitor: started" :name name)
      (try
        (loop [steals 0]
          (when-not (px/shutdown? executor)
            (px/sleep interval)
            (recur (long (monitor! executor steals)))))
        (catch InterruptedException _cause
          (l/debug :hint "monitor: interrupted" :name name))
        (catch Throwable cause
          (l/error :hint "monitor: unexpected error" :name name :cause cause))
        (finally
          (l/info :hint "monitor: terminated" :name name))))))

(defmethod ig/halt-key! ::monitor
  [_ thread]
  (px/interrupt! thread))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEDULER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- decode-task-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))))

(s/def ::queue ::us/string)
(s/def ::wait-duration ::dt/duration)

(defmethod ig/pre-init-spec ::scheduler [_]
  (s/keys :req [::mtx/metrics
                ::db/pool
                ::rds/redis]
          :opt [::wait-duration
                ::batch-size]))

(defmethod ig/prep-key ::scheduler
  [_ cfg]
  (merge {::batch-size 1
          ::wait-duration (dt/duration "2s")}
         (d/without-nils cfg)))

(def ^:private sql:select-next-tasks
  "select * from task as t
    where t.scheduled_at <= now()
      and (t.status = 'new' or t.status = 'retry')
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defn- format-queue
  [queue]
  (str/ffmt "penpot-tasks-queue:%" queue))

(defmethod ig/init-key ::scheduler
  [_ {:keys [::db/pool ::rds/redis ::batch-size] :as cfg}]
  (letfn [(get-tasks-batch [conn]
            (->> (db/exec! conn [sql:select-next-tasks batch-size])
                 (map decode-task-row)
                 (seq)))

          (queue-task [conn rconn {:keys [id queue] :as task}]
            (db/update! conn :task {:status "ready"} {:id id})
            (let [queue   (format-queue queue)
                  payload (t/encode id)
                  result  (rds/rpush! rconn queue payload)]
              (l/debug :hist "scheduler: task pushed to redis"
                       :task-id id
                       :key queue
                       :queued result)))

          (run-batch [rconn]
            (db/with-atomic [conn pool]
              (when-let [tasks (get-tasks-batch conn)]
                (run! (partial queue-task conn rconn) tasks)
                true)))
          ]

    (if (db/read-only? pool)
      (l/warn :hint "scheduler: not started (db is read-only)")
      (px/thread
        {:name "penpot/scheduler"}
        (l/info :hint "scheduler: started")
        (try
          (dm/with-open [rconn (rds/connect redis)]
            (loop []
              (when (px/interrupted?)
                (throw (InterruptedException. "interrumpted")))

              (try
                (when-not (run-batch rconn)
                  (px/sleep (::wait-duration cfg)))
                (catch InterruptedException cause
                  (throw cause))
                (catch Exception cause
                  (cond
                    (rds/exception? cause)
                    (do
                      (l/warn :hint "scheduler: redis exception (will retry in an instant)" :cause cause)
                      (px/sleep (::rds/timeout rconn)))

                    (db/sql-exception? cause)
                    (do
                      (l/warn :hint "scheduler: database exception (will retry in an instant)" :cause cause)
                      (px/sleep (::rds/timeout rconn)))

                    :else
                    (do
                      (l/error :hint "scheduler: unhandled exception (will retry in an instant)" :cause cause)
                      (px/sleep (::rds/timeout rconn))))))

              (recur)))

          (catch InterruptedException _
            (l/debug :hint "scheduler: interrupted"))
          (catch Throwable cause
            (l/error :hint "scheduler: unexpected exception" :cause cause))
          (finally
            (l/info :hint "scheduler: terminated")))))))

(defmethod ig/halt-key! ::scheduler
  [_ thread]
  (some-> thread px/interrupt!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WORKER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private run-worker-loop!)
(declare ^:private start-worker!)
(declare ^:private get-error-context)

(defmethod ig/pre-init-spec ::worker [_]
  (s/keys :req [::parallelism
                ::mtx/metrics
                ::db/pool
                ::rds/redis
                ::queue
                ::registry]))

(defmethod ig/prep-key ::worker
  [_ cfg]
  (merge {::queue "default"
          ::parallelism 1}
         (d/without-nils cfg)))

(defmethod ig/init-key ::worker
  [_ {:keys [::db/pool ::queue ::parallelism] :as cfg}]
  (if (db/read-only? pool)
    (l/warn :hint "workers: not started (db is read-only)" :queue queue)
    (doall
     (->> (range parallelism)
          (map #(assoc cfg ::worker-id %))
          (map start-worker!)))))

(defmethod ig/halt-key! ::worker
  [_ threads]
  (run! px/interrupt! threads))

(defn- start-worker!
  [{:keys [::rds/redis ::worker-id] :as cfg}]
  (px/thread
    {:name (format "penpot/worker/%s" worker-id)}
    (l/info :hint "worker: started" :worker-id worker-id)
    (try
      (dm/with-open [rconn (rds/connect redis)]
        (let [cfg (-> cfg
                      (update ::queue format-queue)
                      (assoc ::rds/rconn rconn)
                      (assoc ::timeout (dt/duration "5s")))]
          (loop []
            (when (px/interrupted?)
              (throw (InterruptedException. "interrupted")))

            (run-worker-loop! cfg)
            (recur))))

      (catch InterruptedException _
        (l/debug :hint "worker: interrupted"
                 :worker-id worker-id))
      (catch Throwable cause
        (l/error :hint "worker: unexpected exception"
                 :worker-id worker-id
                 :cause cause))
      (finally
        (l/info :hint "worker: terminated" :worker-id worker-id)))))

(defn- run-worker-loop!
  [{:keys [::db/pool ::rds/rconn ::timeout ::queue ::registry ::worker-id]}]
  (letfn [(handle-task-retry [{:keys [task error inc-by delay] :or {inc-by 1 delay 1000}}]
            (let [explain (ex-message error)
                  nretry  (+ (:retry-num task) inc-by)
                  now     (dt/now)
                  delay   (->> (iterate #(* % 2) delay) (take nretry) (last))]
              (db/update! pool :task
                          {:error explain
                           :status "retry"
                           :modified-at now
                           :scheduled-at (dt/plus now delay)
                           :retry-num nretry}
                          {:id (:id task)})
              nil))

          (handle-task-failure [{:keys [task error]}]
            (let [explain (ex-message error)]
              (db/update! pool :task
                          {:error explain
                           :modified-at (dt/now)
                           :status "failed"}
                          {:id (:id task)})
              nil))

          (handle-task-completion [{:keys [task]}]
            (let [now (dt/now)]
              (db/update! pool :task
                          {:completed-at now
                           :modified-at now
                           :status "completed"}
                          {:id (:id task)})
              nil))

          (decode-payload [^bytes payload]
            (try
              (let [task-id (t/decode payload)]
                (if (uuid? task-id)
                  task-id
                  (l/error :hint "worker: received unexpected payload (uuid expected)"
                           :payload task-id)))
              (catch Throwable cause
                (l/error :hint "worker: unable to decode payload"
                         :payload payload
                         :length (alength payload)
                         :cause cause))))

          (handle-task [{:keys [name] :as task}]
            (let [task-fn (get registry name)]
              (if task-fn
                (task-fn task)
                (l/warn :hint "no task handler found" :name name))
              {:status :completed :task task}))

          (handle-task-exception [cause task]
            (let [edata (ex-data cause)]
              (if (and (< (:retry-num task)
                          (:max-retries task))
                       (= ::retry (:type edata)))
                (cond-> {:status :retry :task task :error cause}
                  (dt/duration? (:delay edata))
                  (assoc :delay (:delay edata))

                  (= ::noop (:strategy edata))
                  (assoc :inc-by 0))
                (do
                  (l/error :hint "worker: unhandled exception on task"
                           ::l/context (get-error-context cause task)
                           :cause cause)
                  (if (>= (:retry-num task) (:max-retries task))
                    {:status :failed :task task :error cause}
                    {:status :retry :task task :error cause})))))

          (get-task [task-id]
            (ex/try!
             (some-> (db/get* pool :task {:id task-id})
                     (decode-task-row))))

          (run-task [task-id]
            (loop [task (get-task task-id)]
              (cond
                (ex/exception? task)
                (if (or (db/connection-error? task)
                        (db/serialization-error? task))
                  (do
                    (l/warn :hint "worker: connection error on retrieving task from database (retrying in some instants)"
                            :worker-id worker-id
                            :cause task)
                    (px/sleep (::rds/timeout rconn))
                    (recur (get-task task-id)))
                  (do
                    (l/error :hint "worker: unhandled exception on retrieving task from database (retrying in some instants)"
                             :worker-id worker-id
                             :cause task)
                    (px/sleep (::rds/timeout rconn))
                    (recur (get-task task-id))))

                (nil? task)
                (l/warn :hint "worker: no task found on the database"
                        :worker-id worker-id
                        :task-id task-id)

                :else
                (try
                  (l/trace :hint "worker: executing task"
                           :worker-id worker-id
                           :task-id (:id task)
                           :task-name (:name task)
                           :task-retry (:retry-num task))
                  (handle-task task)
                  (catch InterruptedException cause
                    (throw cause))
                  (catch Throwable cause
                    (handle-task-exception cause task))))))

          (process-result [{:keys [status] :as result}]
            (ex/try!
             (case status
               :retry (handle-task-retry result)
               :failed (handle-task-failure result)
               :completed (handle-task-completion result))))

          (run-task-loop [task-id]
            (loop [result (run-task task-id)]
              (when-let [cause (process-result result)]
                (if (or (db/connection-error? cause)
                        (db/serialization-error? cause))
                  (do
                    (l/warn :hint "worker: database exeption on processing task result (retrying in some instants)"
                            :cause cause)
                    (px/sleep (::rds/timeout rconn))
                    (recur result))
                  (do
                    (l/error :hint "worker: unhandled exception on processing task result (retrying in some instants)"
                             :cause cause)
                    (px/sleep (::rds/timeout rconn))
                    (recur result))))))]

    (try
      (let [[_ payload] (rds/blpop! rconn timeout queue)]
        (some-> payload
                decode-payload
                run-task-loop))

      (catch InterruptedException cause
        (throw cause))

      (catch Exception cause
        (if (rds/timeout-exception? cause)
          (do
            (l/error :hint "worker: redis pop operation timeout, consider increasing redis timeout (will retry in some instants)"
                     :timeout timeout
                     :cause cause)
            (px/sleep timeout))

          (l/error :hint "worker: unhandled exception" :cause cause))))))

(defn- get-error-context
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CRON
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
  (s/keys :req [::scheduled-executor ::db/pool ::entries ::registry]))

(defmethod ig/init-key ::cron
  [_ {:keys [::entries ::registry ::db/pool] :as cfg}]
  (if (db/read-only? pool)
    (l/warn :hint "cron: not started (db is read-only)")
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

        (l/info :hint "cron: started" :tasks (count entries))
        (synchronize-cron-entries! cfg)

        (->> (filter some? entries)
             (run! (partial schedule-cron-task cfg)))

        (reify
          clojure.lang.IDeref
          (deref [_] @running)

          java.lang.AutoCloseable
          (close [_]
            (l/info :hint "cron: terminated")
            (doseq [item @running]
              (when-not (.isDone ^Future item)
                (.cancel ^Future item true))))))))

(defmethod ig/halt-key! ::cron
  [_ instance]
  (some-> instance d/close!))

(def sql:upsert-cron-task
  "insert into scheduled_task (id, cron_expr)
   values (?, ?)
       on conflict (id)
       do update set cron_expr=?")

(defn- synchronize-cron-entries!
  [{:keys [::db/pool ::entries]}]
  (db/with-atomic [conn pool]
    (doseq [{:keys [id cron]} entries]
      (l/trace :hint "register cron task" :id id :cron (str cron))
      (db/exec-one! conn [sql:upsert-cron-task id (str cron) (str cron)]))))

(def sql:lock-cron-task
  "select id from scheduled_task where id=? for update skip locked")

(defn- execute-cron-task
  [{:keys [::db/pool] :as cfg} {:keys [id] :as task}]
  (try
    (db/with-atomic [conn pool]
      (when (db/exec-one! conn [sql:lock-cron-task (d/name id)])
        (l/trace :hint "cron: execute task" :task-id id)
        ((:fn task) task)))
    (catch InterruptedException _
      (px/interrupt! (px/current-thread))
      (l/debug :hint "cron: task interrupted" :task-id id))
    (catch Throwable cause
      (l/error :hint "cron: unhandled exception on running task"
               ::l/context (get-error-context cause task)
               :task-id id
               :cause cause))
    (finally
      (when-not (px/interrupted? :current)
        (schedule-cron-task cfg task)))))

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
  [{:keys [::scheduled-executor ::running] :as cfg} {:keys [cron] :as task}]
  (let [ft (px/schedule! scheduled-executor
                         (ms-until-valid cron)
                         (partial execute-cron-task cfg task))]
    (swap! running #(into #{ft} xf-without-done %))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  [& {:keys [::task ::delay ::queue ::priority ::max-retries ::conn]
      :or {delay 0 queue "default" priority 100 max-retries 3}
      :as options}]
  (us/verify ::submit-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (-> options extract-props db/tjson)
        id        (uuid/next)]

    (l/debug :action "submit task"
             :name (d/name task)
             :queue queue
             :in duration)

    (db/exec-one! conn [sql:insert-new-task id (d/name task) props
                        queue priority max-retries interval])
    id))

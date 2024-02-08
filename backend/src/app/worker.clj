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
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   java.util.concurrent.Executor
   java.util.concurrent.Future
   java.util.concurrent.ThreadPoolExecutor))

(set! *warn-on-reflection* true)

(s/def ::executor #(instance? Executor %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Executor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::executor [_]
  (s/keys :req []))

(defmethod ig/init-key ::executor
  [_ _]
  (let [factory  (px/thread-factory :prefix "penpot/default/")
        executor (px/cached-executor :factory factory :keepalive 60000)]
    (l/inf :hint "starting executor")
    (reify
      java.lang.AutoCloseable
      (close [_]
        (l/inf :hint "stoping executor")
        (px/shutdown! executor))

      clojure.lang.IDeref
      (deref [_]
        {:active (.getPoolSize ^ThreadPoolExecutor executor)
         :running (.getActiveCount ^ThreadPoolExecutor executor)
         :completed (.getCompletedTaskCount ^ThreadPoolExecutor executor)})

      Executor
      (execute [_ runnable]
        (.execute ^Executor executor ^Runnable runnable)))))

(defmethod ig/halt-key! ::executor
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))

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
  (s/keys :req [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [::mtx/metrics ::tasks]}]
  (l/inf :hint "registry initialized" :tasks (count tasks))
  (reduce-kv (fn [registry k v]
               (let [tname (name k)]
                 (l/trc :hint "register task" :name tname)
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
  (letfn [(monitor! [executor prev-completed]
            (let [labels        (into-array String [(d/name name)])
                  stats         (deref executor)

                  completed     (:completed stats)
                  completed-inc (- completed prev-completed)
                  completed-inc (if (neg? completed-inc) 0 completed-inc)]

              (mtx/run! metrics
                        :id :executor-active-threads
                        :labels labels
                        :val (:active stats))

              (mtx/run! metrics
                        :id :executor-running-threads
                        :labels labels
                        :val (:running stats))

              (mtx/run! metrics
                        :id :executors-completed-tasks
                        :labels labels
                        :inc completed-inc)

              completed-inc))]

    (px/thread
      {:name "penpot/executors-monitor" :virtual true}
      (l/inf :hint "monitor: started" :name name)
      (try
        (loop [completed 0]
          (px/sleep interval)
          (recur (long (monitor! executor completed))))
        (catch InterruptedException _cause
          (l/trc :hint "monitor: interrupted" :name name))
        (catch Throwable cause
          (l/err :hint "monitor: unexpected error" :name name :cause cause))
        (finally
          (l/inf :hint "monitor: terminated" :name name))))))

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

(s/def ::wait-duration ::dt/duration)

(defmethod ig/pre-init-spec ::dispatcher [_]
  (s/keys :req [::mtx/metrics
                ::db/pool
                ::rds/redis]
          :opt [::wait-duration
                ::batch-size]))

(defmethod ig/prep-key ::dispatcher
  [_ cfg]
  (merge {::batch-size 100
          ::wait-duration (dt/duration "5s")}
         (d/without-nils cfg)))

(def ^:private sql:select-next-tasks
  "select id, queue from task as t
    where t.scheduled_at <= now()
      and (t.status = 'new' or t.status = 'retry')
      and queue ~~* ?::text
    order by t.priority desc, t.scheduled_at
    limit ?
      for update skip locked")

(defmethod ig/init-key ::dispatcher
  [_ {:keys [::db/pool ::rds/redis ::batch-size] :as cfg}]
  (letfn [(get-tasks [conn]
            (let [prefix (str (cf/get :tenant) ":%")]
              (seq (db/exec! conn [sql:select-next-tasks prefix batch-size]))))

          (push-tasks! [conn rconn [queue tasks]]
            (let [ids (mapv :id tasks)
                  key (str/ffmt "taskq:%" queue)
                  res (rds/rpush! rconn key (mapv t/encode ids))
                  sql [(str "update task set status = 'scheduled'"
                            " where id = ANY(?)")
                       (db/create-array conn "uuid" ids)]]

              (db/exec-one! conn sql)
              (l/trc :hist "dispatcher: queue tasks"
                     :queue queue
                     :tasks (count ids)
                     :queued res)))

          (run-batch! [rconn]
            (try
              (db/with-atomic [conn pool]
                (if-let [tasks (get-tasks conn)]
                  (->> (group-by :queue tasks)
                       (run! (partial push-tasks! conn rconn)))
                  (px/sleep (::wait-duration cfg))))
              (catch InterruptedException cause
                (throw cause))
              (catch Exception cause
                (cond
                  (rds/exception? cause)
                  (do
                    (l/wrn :hint "dispatcher: redis exception (will retry in an instant)" :cause cause)
                    (px/sleep (::rds/timeout rconn)))

                  (db/sql-exception? cause)
                  (do
                    (l/wrn :hint "dispatcher: database exception (will retry in an instant)" :cause cause)
                    (px/sleep (::rds/timeout rconn)))

                  :else
                  (do
                    (l/err :hint "dispatcher: unhandled exception (will retry in an instant)" :cause cause)
                    (px/sleep (::rds/timeout rconn)))))))

          (dispatcher []
            (l/inf :hint "dispatcher: started")
            (try
              (dm/with-open [rconn (rds/connect redis)]
                (loop []
                  (run-batch! rconn)
                  (recur)))
              (catch InterruptedException _
                (l/trc :hint "dispatcher: interrupted"))
              (catch Throwable cause
                (l/err :hint "dispatcher: unexpected exception" :cause cause))
              (finally
                (l/inf :hint "dispatcher: terminated"))))]

    (if (db/read-only? pool)
      (l/wrn :hint "dispatcher: not started (db is read-only)")
      (px/fn->thread dispatcher :name "penpot/worker/dispatcher" :virtual true))))

(defmethod ig/halt-key! ::dispatcher
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
  (merge {::parallelism 1}
         (d/without-nils cfg)))

(defmethod ig/init-key ::worker
  [_ {:keys [::db/pool ::queue ::parallelism] :as cfg}]
  (let [queue (d/name queue)
        cfg   (assoc cfg ::queue queue)]
    (if (db/read-only? pool)
      (l/wrn :hint "worker: not started (db is read-only)" :queue queue :parallelism parallelism)
      (doall
       (->> (range parallelism)
            (map #(assoc cfg ::worker-id %))
            (map start-worker!))))))

(defmethod ig/halt-key! ::worker
  [_ threads]
  (run! px/interrupt! threads))

(defn- start-worker!
  [{:keys [::rds/redis ::worker-id ::queue] :as cfg}]
  (px/thread
    {:name (format "penpot/worker/runner:%s" worker-id)}
    (l/inf :hint "worker: started" :worker-id worker-id :queue queue)
    (try
      (dm/with-open [rconn (rds/connect redis)]
        (let [tenant (cf/get :tenant "main")
              cfg    (-> cfg
                         (assoc ::queue (str/ffmt "taskq:%:%" tenant queue))
                         (assoc ::rds/rconn rconn)
                         (assoc ::timeout (dt/duration "5s")))]
          (loop []
            (when (px/interrupted?)
              (throw (InterruptedException. "interrupted")))

            (run-worker-loop! cfg)
            (recur))))

      (catch InterruptedException _
        (l/debug :hint "worker: interrupted"
                 :worker-id worker-id
                 :queue queue))
      (catch Throwable cause
        (l/err :hint "worker: unexpected exception"
               :worker-id worker-id
               :queue queue
               :cause cause))
      (finally
        (l/inf :hint "worker: terminated"
               :worker-id worker-id
               :queue queue)))))

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
                  (l/err :hint "worker: received unexpected payload (uuid expected)"
                         :payload task-id)))
              (catch Throwable cause
                (l/err :hint "worker: unable to decode payload"
                       :payload payload
                       :length (alength payload)
                       :cause cause))))

          (handle-task [{:keys [name] :as task}]
            (let [task-fn (get registry name)]
              (if task-fn
                (task-fn task)
                (l/wrn :hint "no task handler found" :name name))
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
                  (l/err :hint "worker: unhandled exception on task"
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
                    (l/wrn :hint "worker: connection error on retrieving task from database (retrying in some instants)"
                           :worker-id worker-id
                           :cause task)
                    (px/sleep (::rds/timeout rconn))
                    (recur (get-task task-id)))
                  (do
                    (l/err :hint "worker: unhandled exception on retrieving task from database (retrying in some instants)"
                           :worker-id worker-id
                           :cause task)
                    (px/sleep (::rds/timeout rconn))
                    (recur (get-task task-id))))

                (nil? task)
                (l/wrn :hint "worker: no task found on the database"
                       :worker-id worker-id
                       :task-id task-id)

                :else
                (try
                  (l/trc :hint "executing task"
                         :name (:name task)
                         :id (str (:id task))
                         :queue queue
                         :worker-id worker-id
                         :retry (:retry-num task))
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
               :completed (handle-task-completion result)
               nil)))

          (run-task-loop [task-id]
            (loop [result (run-task task-id)]
              (when-let [cause (process-result result)]
                (if (or (db/connection-error? cause)
                        (db/serialization-error? cause))
                  (do
                    (l/wrn :hint "worker: database exeption on processing task result (retrying in some instants)"
                           :cause cause)
                    (px/sleep (::rds/timeout rconn))
                    (recur result))
                  (do
                    (l/err :hint "worker: unhandled exception on processing task result (retrying in some instants)"
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
            (l/err :hint "worker: redis pop operation timeout, consider increasing redis timeout (will retry in some instants)"
                   :timeout timeout
                   :cause cause)
            (px/sleep timeout))

          (l/err :hint "worker: unhandled exception" :cause cause))))))

(defn- get-error-context
  [_ item]
  {:params item})

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
  (s/keys :req [::db/pool ::entries ::registry]))

(defmethod ig/init-key ::cron
  [_ {:keys [::entries ::registry ::db/pool] :as cfg}]
  (if (db/read-only? pool)
    (l/wrn :hint "cron: not started (db is read-only)")
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

      (l/inf :hint "cron: started" :tasks (count entries))
      (synchronize-cron-entries! cfg)

      (->> (filter some? entries)
           (run! (partial schedule-cron-task cfg)))

      (reify
        clojure.lang.IDeref
        (deref [_] @running)

        java.lang.AutoCloseable
        (close [_]
          (l/inf :hint "cron: terminated")
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
      (l/trc :hint "register cron task" :id id :cron (str cron))
      (db/exec-one! conn [sql:upsert-cron-task id (str cron) (str cron)]))))

(defn- lock-scheduled-task!
  [conn id]
  (let [sql (str "SELECT id FROM scheduled_task "
                 " WHERE id=? FOR UPDATE SKIP LOCKED")]
    (some? (db/exec-one! conn [sql (d/name id)]))))

(defn- execute-cron-task
  [{:keys [::db/pool] :as cfg} {:keys [id] :as task}]
  (px/thread
    {:name (str "penpot/cront-task/" id)}
    (try
      (db/with-atomic [conn pool]
        (db/exec-one! conn ["SET statement_timeout=0;"])
        (db/exec-one! conn ["SET idle_in_transaction_session_timeout=0;"])
        (when (lock-scheduled-task! conn id)
          (l/dbg :hint "cron: execute task" :task-id id)
          ((:fn task) task))
        (db/rollback! conn))

      (catch InterruptedException _
        (l/debug :hint "cron: task interrupted" :task-id id))

      (catch Throwable cause
        (binding [l/*context* (get-error-context cause task)]
          (l/err :hint "cron: unhandled exception on running task"
                 :task-id id
                 :cause cause)))
      (finally
        (when-not (px/interrupted? :current)
          (schedule-cron-task cfg task))))))

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

    (l/dbg :hint "cron: schedule task" :task-id id
           :ts (dt/format-duration ts)
           :at (dt/format-instant (dt/in-future ts)))
    (swap! running #(into #{ft} (filter p/pending?) %))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-props
  [options]
  (let [cns (namespace ::sample)]
    (persistent!
     (reduce-kv (fn [res k v]
                  (cond-> res
                    (not= (namespace k) cns)
                    (assoc! k v)))
                (transient {})
                options))))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, label, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, ?, now() + ?)
   returning id")

(def ^:private
  sql:remove-not-started-tasks
  "DELETE FROM task
    WHERE name=?
      AND queue=?
      AND label=?
      AND status = 'new'
      AND scheduled_at > now()")

(s/def ::label string?)
(s/def ::task (s/or :kw keyword? :str string?))
(s/def ::queue (s/or :kw keyword? :str string?))
(s/def ::delay (s/or :int integer? :duration dt/duration?))
(s/def ::conn (s/or :pool ::db/pool :connection some?))
(s/def ::priority integer?)
(s/def ::max-retries integer?)
(s/def ::dedupe boolean?)

(s/def ::submit-options
  (s/and
   (s/keys :req [::task ::conn]
           :opt [::label ::delay ::queue ::priority ::max-retries ::dedupe])
   (fn [{:keys [::dedupe ::label] :or {label ""}}]
     (if dedupe
       (not= label "")
       true))))

(defn submit!
  [& {:keys [::task ::delay ::queue ::priority ::max-retries ::conn ::dedupe ::label]
      :or {delay 0 queue :default priority 100 max-retries 3 label ""}
      :as options}]
  (us/verify! ::submit-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (-> options extract-props db/tjson)
        id        (uuid/next)
        tenant    (cf/get :tenant)
        task      (d/name task)
        queue     (str/ffmt "%:%" tenant (d/name queue))
        deleted   (when dedupe
                    (-> (db/exec-one! conn [sql:remove-not-started-tasks task queue label])
                        :next.jdbc/update-count))]

    (l/trc :hint "submit task"
           :name task
           :queue queue
           :label label
           :dedupe (boolean dedupe)
           :deleted (or deleted 0)
           :in (dt/format-duration duration))

    (db/exec-one! conn [sql:insert-new-task id task props queue
                        label priority max-retries interval])
    id))

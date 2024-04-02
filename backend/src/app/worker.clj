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
   [promesa.exec :as px]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASKS REGISTRY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wrap-with-metrics
  [f metrics tname]
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
(s/def ::tasks (s/map-of keyword? fn?))

(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [::mtx/metrics ::tasks]}]
  (l/inf :hint "registry initialized" :tasks (count tasks))
  (reduce-kv (fn [registry k f]
               (let [tname (name k)]
                 (l/trc :hint "register task" :name tname)
                 (assoc registry tname (wrap-with-metrics f metrics tname))))
             {}
             tasks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WORKER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- decode-task-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))))

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

(defn get-error-context
  [_ item]
  {:params item})

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

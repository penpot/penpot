;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.runner
  "Async tasks abstraction (impl)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.worker :as wrk]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(set! *warn-on-reflection* true)

(def schema:task
  [:map {:title "Task"}
   [:id ::sm/uuid]
   [:queue :string]
   [:name :string]
   [:created-at ::ct/inst]
   [:modified-at ::ct/inst]
   [:scheduled-at {:optional true} ::ct/inst]
   [:completed-at {:optional true} ::ct/inst]
   [:error {:optional true} :string]
   [:max-retries :int]
   [:retry-num :int]
   [:priority :int]
   [:status [:enum "scheduled" "completed" "new" "retry" "failed"]]
   [:label {:optional true} :string]
   [:props :map]])

(def schema:result
  [:map {:title "TaskResult"}
   [:status [:enum "retry" "failed" "completed"]]
   [:error {:optional true} [:fn ex/exception?]]
   [:inc-by {:optional true} :int]
   [:delay {:optional true} :int]])

(def valid-task-result?
  (sm/validator schema:result))

(defn- decode-task-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))))

(defn get-error-context
  [_ item]
  {:params item})

(defn- get-task
  [{:keys [::db/pool]} task-id]
  (ex/try!
   (some-> (db/get* pool :task {:id task-id})
           (decode-task-row))))

(defn- run-task
  [{:keys [::wrk/registry ::id ::queue] :as cfg} task]
  (try
    (l/dbg :hint "start"
           :name (:name task)
           :task-id (str (:id task))
           :queue queue
           :runner-id id
           :retry (:retry-num task))
    (let [tpoint  (ct/tpoint)
          task-fn (wrk/get-task registry (:name task))
          result  (when task-fn (task-fn task))
          elapsed (ct/format-duration (tpoint))
          result  (if (valid-task-result? result)
                    result
                    {:status "completed"})]

      (when-not task-fn
        (l/wrn :hint "no task handler found" :name (:name task)))

      (l/dbg :hint "end"
             :name (:name task)
             :task-id (str (:id task))
             :queue queue
             :runner-id id
             :retry (:retry-num task)
             :elapsed elapsed)

      result)

    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (let [edata (ex-data cause)]
        (if (and (< (:retry-num task)
                    (:max-retries task))
                 (= ::retry (:type edata)))
          (cond-> {:status "retry" :error cause}
            (ct/duration? (:delay edata))
            (assoc :delay (:delay edata))

            (= ::noop (:strategy edata))
            (assoc :inc-by 0))
          (do
            (l/err :hint "unhandled exception on task"
                   ::l/context (get-error-context cause task)
                   :cause cause)
            (if (>= (:retry-num task) (:max-retries task))
              {:status "failed" :error cause}
              {:status "retry" :error cause})))))))

(defn- run-task!
  [{:keys [::id ::timeout] :as cfg} task-id]
  (loop [task (get-task cfg task-id)]
    (cond
      (ex/exception? task)
      (if (or (db/connection-error? task)
              (db/serialization-error? task))
        (do
          (l/wrn :hint "connection error on retrieving task from database (retrying in some instants)"
                 :id id
                 :cause task)
          (px/sleep timeout)
          (recur (get-task cfg task-id)))
        (do
          (l/err :hint "unhandled exception on retrieving task from database (retrying in some instants)"
                 :id id
                 :cause task)
          (px/sleep timeout)
          (recur (get-task cfg task-id))))

      (nil? task)
      (l/wrn :hint "no task found on the database"
             :id id
             :task-id task-id)

      :else
      (let [result (run-task cfg task)]
        (with-meta result
          {::task task})))))

(defn- run-worker-loop!
  [{:keys [::db/pool ::rds/rconn ::timeout ::queue] :as cfg}]
  (letfn [(handle-task-retry [{:keys [error inc-by delay] :or {inc-by 1 delay 1000} :as result}]
            (let [explain (if (ex/exception? error)
                            (ex-message error)
                            (str error))
                  task    (-> result meta ::task)
                  nretry  (+ (:retry-num task) inc-by)
                  now     (ct/now)
                  delay   (->> (iterate #(* % 2) delay) (take nretry) (last))]
              (db/update! pool :task
                          {:error explain
                           :status "retry"
                           :modified-at now
                           :scheduled-at (ct/plus now delay)
                           :retry-num nretry}
                          {:id (:id task)})
              nil))

          (handle-task-failure [{:keys [error] :as result}]
            (let [task    (-> result meta ::task)
                  explain (ex-message error)]
              (db/update! pool :task
                          {:error explain
                           :modified-at (ct/now)
                           :status "failed"}
                          {:id (:id task)})
              nil))

          (handle-task-completion [result]
            (let [task (-> result meta ::task)
                  now  (ct/now)]
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
                  (l/err :hint "received unexpected payload (uuid expected)"
                         :payload task-id)))
              (catch Throwable cause
                (l/err :hint "unable to decode payload"
                       :payload payload
                       :length (alength payload)
                       :cause cause))))

          (process-result [{:keys [status] :as result}]
            (ex/try!
             (case status
               "retry"     (handle-task-retry result)
               "failed"    (handle-task-failure result)
               "completed" (handle-task-completion result)
               (throw (IllegalArgumentException.
                       (str "invalid status received: " status))))))

          (run-task-loop [task-id]
            (loop [result (run-task! cfg task-id)]
              (when-let [cause (process-result result)]
                (if (or (db/connection-error? cause)
                        (db/serialization-error? cause))
                  (do
                    (l/wrn :hint "database exeption on processing task result (retrying in some instants)"
                           :cause cause)
                    (px/sleep timeout)
                    (recur result))
                  (do
                    (l/err :hint "unhandled exception on processing task result (retrying in some instants)"
                           :cause cause)
                    (px/sleep timeout)
                    (recur result))))))]

    (try
      (let [key       (str/ffmt "taskq:%" queue)
            [_ payload] (rds/blpop rconn timeout [key])]
        (some-> payload
                decode-payload
                run-task-loop))

      (catch InterruptedException cause
        (throw cause))

      (catch Exception cause
        (if (rds/timeout-exception? cause)
          (do
            (l/err :hint "redis pop operation timeout, consider increasing redis timeout (will retry in some instants)"
                   :timeout timeout
                   :cause cause)
            (px/sleep timeout))

          (l/err :hint "unhandled exception" :cause cause))))))

(defn- start-thread!
  [{:keys [::rds/redis ::id ::queue ::wrk/tenant] :as cfg}]
  (px/thread
    {:name (format "penpot/worker/runner:%s" id)}
    (l/inf :hint "started" :id id :queue queue)
    (try
      (dm/with-open [rconn (rds/connect redis)]
        (let [cfg    (-> cfg
                         (assoc ::rds/rconn rconn)
                         (assoc ::queue (str/ffmt "%:%" tenant queue))
                         (assoc ::timeout (ct/duration "5s")))]
          (loop []
            (when (px/interrupted?)
              (throw (InterruptedException. "interrupted")))

            (run-worker-loop! cfg)
            (recur))))

      (catch InterruptedException _
        (l/dbg :hint "interrupted"
               :id id
               :queue queue))
      (catch Throwable cause
        (l/err :hint "unexpected exception"
               :id id
               :queue queue
               :cause cause))
      (finally
        (l/inf :hint "terminated"
               :id id
               :queue queue)))))

(def ^:private schema:params
  [:map
   [::wrk/parallelism {:optional true} ::sm/int]
   [::wrk/queue :keyword]
   [::wrk/tenant ::sm/text]
   ::wrk/registry
   ::mtx/metrics
   ::db/pool
   ::rds/redis])

(defmethod ig/assert-key ::wrk/runner
  [_ params]
  (assert (sm/check schema:params params)))

(defmethod ig/expand-key ::wrk/runner
  [k v]
  {k (merge {::wrk/parallelism 1} (d/without-nils v))})

(defmethod ig/init-key ::wrk/runner
  [_ {:keys [::db/pool ::wrk/queue ::wrk/parallelism] :as cfg}]
  (let [queue (d/name queue)
        cfg   (assoc cfg ::queue queue)]
    (if (db/read-only? pool)
      (l/wrn :hint "not started (db is read-only)" :queue queue :parallelism parallelism)
      (doall
       (->> (range parallelism)
            (map #(assoc cfg ::id %))
            (map start-thread!))))))

(defmethod ig/halt-key! ::wrk/runner
  [_ threads]
  (run! px/interrupt! threads))

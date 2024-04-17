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
   [app.common.transit :as t]
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as rds]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px]))

(set! *warn-on-reflection* true)

(defn- decode-task-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props)
    (assoc :props (db/decode-transit-pgobject props))))

(defn get-error-context
  [_ item]
  {:params item})

(defn- run-worker-loop!
  [{:keys [::db/pool ::rds/rconn ::wrk/registry ::timeout ::queue ::id]}]
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
                  (l/err :hint "received unexpected payload (uuid expected)"
                         :payload task-id)))
              (catch Throwable cause
                (l/err :hint "unable to decode payload"
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
                  (l/err :hint "unhandled exception on task"
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
                    (l/wrn :hint "connection error on retrieving task from database (retrying in some instants)"
                           :id id
                           :cause task)
                    (px/sleep (::rds/timeout rconn))
                    (recur (get-task task-id)))
                  (do
                    (l/err :hint "unhandled exception on retrieving task from database (retrying in some instants)"
                           :id id
                           :cause task)
                    (px/sleep (::rds/timeout rconn))
                    (recur (get-task task-id))))

                (nil? task)
                (l/wrn :hint "no task found on the database"
                       :id id
                       :task-id task-id)

                :else
                (try
                  (l/dbg :hint "start"
                         :name (:name task)
                         :task-id (str task-id)
                         :queue queue
                         :runner-id id
                         :retry (:retry-num task))
                  (let [tpoint  (dt/tpoint)
                        result  (handle-task task)
                        elapsed (dt/format-duration (tpoint))]

                    (l/dbg :hint "end"
                           :name (:name task)
                           :task-id (str task-id)
                           :queue queue
                           :runner-id id
                           :retry (:retry-num task)
                           :elapsed elapsed)

                    result)

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
                    (l/wrn :hint "database exeption on processing task result (retrying in some instants)"
                           :cause cause)
                    (px/sleep (::rds/timeout rconn))
                    (recur result))
                  (do
                    (l/err :hint "unhandled exception on processing task result (retrying in some instants)"
                           :cause cause)
                    (px/sleep (::rds/timeout rconn))
                    (recur result))))))]

    (try
      (let [queue       (str/ffmt "taskq:%" queue)
            [_ payload] (rds/blpop! rconn timeout queue)]
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
  [{:keys [::rds/redis ::id ::queue] :as cfg}]
  (px/thread
    {:name (format "penpot/worker/runner:%s" id)}
    (l/inf :hint "started" :id id :queue queue)
    (try
      (dm/with-open [rconn (rds/connect redis)]
        (let [tenant (cf/get :tenant "main")
              cfg    (-> cfg
                         (assoc ::queue (str/ffmt "%:%" tenant queue))
                         (assoc ::rds/rconn rconn)
                         (assoc ::timeout (dt/duration "5s")))]
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

(s/def ::wrk/queue keyword?)

(defmethod ig/pre-init-spec ::runner [_]
  (s/keys :req [::wrk/parallelism
                ::mtx/metrics
                ::db/pool
                ::rds/redis
                ::wrk/queue
                ::wrk/registry]))

(defmethod ig/prep-key ::wrk/runner
  [_ cfg]
  (merge {::wrk/parallelism 1}
         (d/without-nils cfg)))

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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.executor
  "Async tasks abstraction (impl)."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [integrant.core :as ig]
   [promesa.exec :as px])
  (:import
   java.util.concurrent.ThreadPoolExecutor))

(set! *warn-on-reflection* true)

(sm/register!
 {:type ::wrk/executor
  :pred #(instance? ThreadPoolExecutor %)
  :type-properties
  {:title "executor"
   :description "Instance of ThreadPoolExecutor"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXECUTOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::wrk/executor
  [_ _]
  (let [factory  (px/thread-factory :prefix "penpot/default/")
        executor (px/cached-executor :factory factory :keepalive 60000)]
    (l/inf :hint "executor started")
    executor))

(defmethod ig/halt-key! ::wrk/executor
  [_ instance]
  (px/shutdown! instance))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MONITOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-stats
  [^ThreadPoolExecutor executor]
  {:active (.getPoolSize ^ThreadPoolExecutor executor)
   :running (.getActiveCount ^ThreadPoolExecutor executor)
   :completed (.getCompletedTaskCount ^ThreadPoolExecutor executor)})

(defmethod ig/expand-key ::wrk/monitor
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::interval (dt/duration "2s")))})

(defmethod ig/init-key ::wrk/monitor
  [_ {:keys [::wrk/executor ::mtx/metrics ::interval ::wrk/name]}]
  (letfn [(monitor! [executor prev-completed]
            (let [labels        (into-array String [(d/name name)])
                  stats         (get-stats executor)

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
      (l/inf :hint "monitor started" :name name)
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

(defmethod ig/halt-key! ::wrk/monitor
  [_ thread]
  (px/interrupt! thread))

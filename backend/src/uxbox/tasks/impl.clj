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
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [mount.core :as mount :refer [defstate]]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.core :refer [system]]
   [uxbox.db :as db]
   [uxbox.util.blob :as blob]
   [uxbox.util.time :as tm]
   [vertx.core :as vc]
   [vertx.timers :as vt])
  (:import java.time.Duration))

(defn- string-strack-trace
  [err]
  (with-out-str
    (.printStackTrace err (java.io.PrintWriter. *out*))))

(def ^:private sql:mark-as-retry
  "update tasks
      set scheduled_at = clock_timestamp() + '5 seconds'::interval,
          error = $1,
          status = 'retry',
          retry_num = retry_num + 1
    where id = $2;")

(defn- reschedule
  [conn task error]
  (let [explain (string-strack-trace error)
        sqlv [sql:mark-as-retry explain (:id task)]]
    (-> (db/query-one conn sqlv)
        (p/then' (constantly nil)))))


(def ^:private sql:mark-as-failed
  "update tasks
      set scheduled_at = clock_timestamp() + '5 seconds'::interval,
          error = $1,
          status = 'failed'
    where id = $2;")

(defn- mark-as-failed
  [conn task error]
  (let [error (string-strack-trace error)
        sqlv [sql:mark-as-failed error (:id task)]]
    (-> (db/query-one conn sqlv)
        (p/then' (constantly nil)))))

(def ^:private sql:mark-as-completed
  "update tasks
      set completed_at = clock_timestamp(),
          status = 'completed'
    where id = $1")

(defn- mark-as-completed
  [conn task]
  (-> (db/query-one conn [sql:mark-as-completed (:id task)])
      (p/then' (constantly nil))))

(defn- handle-task
  [handlers {:keys [name] :as task}]
  (let [task-fn (get handlers name)]
    (if task-fn
      (task-fn task)
      (do
        (log/warn "no task handler found for" (pr-str name))
        nil))))

(def ^:private sql:select-next-task
  "select * from tasks as t
    where t.scheduled_at <= now()
      and t.queue = $1
      and (t.status = 'new' or (t.status = 'retry' and t.retry_num <= $2))
    order by t.scheduled_at
    limit 1
      for update skip locked")

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      props (assoc :props (blob/decode props)))))

(defn- event-loop
  [{:keys [handlers] :as options}]
  (let [queue (:queue options "default")
        max-retries (:max-retries options 3)]
    (db/with-atomic [conn db/pool]
      (-> (db/query-one conn [sql:select-next-task queue max-retries])
          (p/then decode-task-row)
          (p/then (fn [item]
                    (when item
                      (-> (p/do! (handle-task handlers item))
                          (p/handle (fn [v e]
                                      (if e
                                        (if (>= (:retry-num item) max-retries)
                                          (mark-as-failed conn item e)
                                          (reschedule conn item e))
                                        (mark-as-completed conn item))))
                          (p/then' (constantly ::handled))))))))))

(defn- event-loop-handler
  [options]
  (let [counter (::counter options 1)
        mbs     (:max-batch-size options 10)]
    (-> (event-loop options)
        (p/then (fn [result]
                  (when (and (= result ::handled)
                             (> mbs counter))
                    (event-loop-handler (assoc options ::counter (inc counter)))))))))

(def ^:private sql:insert-new-task
  "insert into tasks (name, props, queue, scheduled_at)
   values ($1, $2, $3, clock_timestamp()+cast($4::text as interval))
   returning id")

(s/def ::name ::us/string)
(s/def ::delay ::us/integer)
(s/def ::props map?)
(s/def ::queue ::us/string)
(s/def ::task-options
  (s/keys :req-un [::name ::delay]
          :opt-un [::props ::queue]))

(defn- duration->pginterval
  [^Duration d]
  (->> (/ (.toMillis d) 1000.0)
       (format "%s seconds")))

(defn schedule!
  [conn {:keys [name delay props queue key] :as options}]
  (us/assert ::task-options options)
  (let [queue (if (string? queue) queue "default")
        duration (-> (tm/duration delay)
                     (duration->pginterval))
        props (blob/encode props)]
    (-> (db/query-one conn  [sql:insert-new-task name props queue duration])
        (p/then' (fn [task] (:id task))))))

(defn- on-start
  [ctx handlers options]
  (vt/schedule! ctx (assoc options
                           ::vt/fn #'event-loop-handler
                           ::vt/delay 3000
                           ::vt/repeat true
                           :handlers handlers)))

(defn- resolve-handlers
  [tasks]
  (s/assert (s/coll-of ::callable) tasks)
  (reduce (fn [acc f]
            (let [task-name (:uxbox.tasks/name (meta f))]
              (if task-name
                (assoc acc task-name f)
                (do
                  (log/warn "skiping task, no name provided in metadata" (pr-str f))
                  acc))))
          {}
          tasks))

(s/def ::callable (s/or :fn fn? :var var?))
(s/def ::max-batch-size ::us/integer)
(s/def ::max-retries ::us/integer)

(s/def ::verticle-tasks
  (s/coll-of ::callable))

(s/def ::verticle-options
  (s/keys :opt-un [::queue ::max-batch-size]))

(defn verticle
  [tasks options]
  (s/assert ::verticle-tasks tasks)
  (s/assert ::verticle-options options)
  (let [handlers (resolve-handlers tasks)
        on-start #(on-start % handlers options)]
    (vc/verticle {:on-start on-start})))


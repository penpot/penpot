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

(def ^:private num-cpus
  (delay (.availableProcessors (Runtime/getRuntime))))

(def ^:private sql:update-failed-task
  "update tasks
      set scheduled_at = now() + cast($1::text as interval),
          status = 'error'
          retry_num = retry_num + 1
    where id = $2;")

(defn- reschedule
  [conn task]
  (let [duration (io.vertx.pgclient.data.Interval/of 0 0 0 0 0 5)
        sqlv [sql:update-failed-task duration (:id task)]]
    (-> (db/query-one conn sqlv)
        (p/then' (constantly nil)))))

(def ^:private sql:update-completed-task
  "update tasks
      set completed_at = clock_timestamp(),
          status = 'completed'
    where id = $1")

(defn- mark-as-completed
  [conn task]
  (-> (db/query-one conn [sql:update-completed-task (:id task)])
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
      and (t.status = 'new' or (t.status = 'error' and t.retry_num < 3))
    order by t.scheduled_at
    limit 1
      for update skip locked")

(defn- decode-task-row
  [{:keys [props] :as row}]
  (when row
    (cond-> row
      props (assoc :props (blob/decode props)))))

(defn- event-loop
  [{:keys [handlers] :as opts}]
  (db/with-atomic [conn db/pool]
    (-> (db/query-one conn sql:select-next-task)
        (p/then decode-task-row)
        (p/then (fn [item]
                  (when item
                    (-> (p/do! (handle-task handlers item))
                        (p/handle (fn [v e]
                                    (if e
                                      (reschedule conn item)
                                      (mark-as-completed conn item))))
                        (p/then' (constantly ::handled)))))))))

(defn- event-loop-handler
  [{:keys [::counter max-barch-size]
    :or {counter 1 max-barch-size 10}
    :as opts}]
  (-> (event-loop opts)
      (p/then (fn [result]
                (when (and (= result ::handled)
                           (> max-barch-size counter))
                  (event-loop-handler (assoc opts ::counter (inc counter))))))))

(def ^:private sql:insert-new-task
  "insert into tasks (name, props, scheduled_at)
   values ($1, $2, now()+cast($3::text as interval)) returning id")

(defn schedule!
  [conn {:keys [name delay props] :as task}]
  (let [delay (tm/duration delay)
        duration (->> (/ (.toMillis ^Duration delay) 1000.0)
                      (format "%s seconds"))
        props (blob/encode props)]
    (-> (db/query-one conn  [sql:insert-new-task name props duration])
        (p/then' (fn [task] (:id task))))))

(defn- on-start
  [ctx handlers]
  (vt/schedule! ctx {::vt/fn #'event-loop-handler
                     ::vt/delay 1000
                     ::vt/repeat true
                     :max-batch-size 10
                     :handlers handlers}))

(defn verticle
  [tasks]
  (s/assert (s/coll-of (s/or :fn fn? :var var?)) tasks)
  (let [handlers (reduce (fn [acc f]
                           (let [task-name (:uxbox.tasks/name (meta f))]
                             (if task-name
                               (assoc acc task-name f)
                               (do
                                 (log/warn "skiping task, no name provided in metadata" (pr-str f))
                                 acc))))
                         {}
                         tasks)
        on-start #(on-start % handlers)]
    (vc/verticle {:on-start on-start})))


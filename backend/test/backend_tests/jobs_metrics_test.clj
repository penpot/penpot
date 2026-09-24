;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.jobs-metrics-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.jobs.metrics :as jobs-metrics]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child
   io.prometheus.client.Gauge
   io.prometheus.client.Gauge$Child))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private metric-ids
  [:jobs-submitted
   :jobs-dispatched
   :jobs-completed
   :jobs-retries
   :jobs-orphaned
   :jobs-rescheduled
   :jobs-queue-wait-timing
   :jobs-execution-timing
   :jobs-total-timing
   :jobs-dispatcher-timing
   :jobs-dispatcher-batch-size
   :jobs-backlog
   :jobs-oldest-pending-age
   :jobs-gc-rows
   :jobs-gc-timing
   :jobs-cron-total
   :jobs-requests-total
   :jobs-request-timing])

(defn- make-metrics []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics metric-ids)}))

(defn- counter-value [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(defn- gauge-value [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Gauge instance (into-array String labels))]
    (.get ^Gauge$Child child)))

(t/deftest default-metrics-cover-the-jobs-observability-contract
  (doseq [id metric-ids]
    (let [definition (get main/default-metrics id)]
      (t/is (some? definition) id)
      (t/is (re-find #"^penpot_jobs_" (::mdef/name definition)) id)))
  (t/is (= ["name" "queue" "outcome"]
           (::mdef/labels (:jobs-completed main/default-metrics))))
  (t/is (= ["status"]
           (::mdef/labels (:jobs-backlog main/default-metrics)))))

(t/deftest submit-and-terminal-writers-record-production-events
  (let [metrics (make-metrics)
        defs    {:echo {::jobs/name      :echo
                        ::jobs/schema    [:map]
                        ::jobs/handler   identity
                        ::jobs/decoder   identity
                        ::jobs/validator (constantly true)}}
        cfg     {::jobs/defs  defs
                 ::db/pool    th/*pool*
                 ::mtx/metrics metrics}
        job-id  (jobs/submit cfg {::jobs/name :echo ::jobs/params {}})]
    (jobs/claim cfg job-id (:scheduled-at (jobs/get-job cfg job-id)))
    (jobs/complete cfg job-id {:ok true})
    (t/is (= 1.0 (counter-value metrics :jobs-submitted ["echo" "default"])))
    (t/is (= 1.0 (counter-value metrics :jobs-completed ["echo" "default" "completed"])))))

(t/deftest lifecycle-helpers-use-bounded-labels
  (let [metrics (make-metrics)]
    (jobs-metrics/record-submitted metrics "echo" "tenant:default")
    (jobs-metrics/record-dispatched metrics "echo" "tenant:default" 2)
    (jobs-metrics/record-outcome metrics "echo" "tenant:default" :completed)
    (jobs-metrics/record-retry metrics "echo" "tenant:default" :backoff)
    (jobs-metrics/record-orphan metrics "tenant:webhooks")
    (jobs-metrics/record-rescheduled metrics "tenant:custom")
    (jobs-metrics/record-gc-rows metrics :expired :deleted 3)
    (jobs-metrics/record-cron metrics :submitted :none)
    (jobs-metrics/record-request metrics :replied 12)

    (t/is (= 1.0 (counter-value metrics :jobs-submitted ["echo" "default"])))
    (t/is (= 2.0 (counter-value metrics :jobs-dispatched ["echo" "default"])))
    (t/is (= 1.0 (counter-value metrics :jobs-completed ["echo" "default" "completed"])))
    (t/is (= 1.0 (counter-value metrics :jobs-retries ["echo" "default" "backoff"])))
    (t/is (= 1.0 (counter-value metrics :jobs-orphaned ["webhooks"])))
    (t/is (= 1.0 (counter-value metrics :jobs-rescheduled ["other"])))
    (t/is (= 3.0 (counter-value metrics :jobs-gc-rows ["expired" "deleted"])))
    (t/is (= 1.0 (counter-value metrics :jobs-cron-total ["submitted" "none"])))
    (t/is (= 1.0 (counter-value metrics :jobs-requests-total ["replied"])))))

(t/deftest unknown-outcomes-and-queues-are-bounded
  (t/is (= "other" (jobs-metrics/queue-label "tenant:unexpected")))
  (t/is (= "default" (jobs-metrics/queue-label :default)))
  (let [metrics (make-metrics)]
    (jobs-metrics/record-outcome metrics "echo" "tenant:unexpected" :unexpected)
    (t/is (= 1.0 (counter-value metrics :jobs-completed ["echo" "other" "failed"])))))

(t/deftest backlog-sampler-updates-status-and-age-gauges
  (let [metrics (make-metrics)
        cfg     {::db/pool th/*pool*
                 ::mtx/metrics metrics}
        now     (ct/now)]
    (th/db-insert! :job {:id           (uuid/next)
                         :name         "echo"
                         :queue        "tenant:default"
                         :props        (db/json {})
                         :priority     100
                         :max-retries  3
                         :retry-num    0
                         :status       "new"
                         :scheduled-at now
                         :created-at   (ct/in-past {:minutes 2})
                         :modified-at  now})
    (jobs-metrics/sample-backlog! cfg)
    (t/is (= 1.0 (gauge-value metrics :jobs-backlog ["new"])))
    (t/is (= 0.0 (gauge-value metrics :jobs-backlog ["completed"])))
    (t/is (>= (gauge-value metrics :jobs-oldest-pending-age []) 120.0))))

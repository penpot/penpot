;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.worker-dispatcher-test
  (:require
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.main :as main]
   [app.metrics :as-alias mtx]
   [app.metrics.definition :as-alias mdef]
   [app.redis :as rds]
   [app.worker :as wrk]
   [app.worker.dispatcher :as wdisp]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Collector$MetricFamilySamples
   io.prometheus.client.Collector$MetricFamilySamples$Sample
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child))

(t/use-fixtures :once th/state-init)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-cfg
  []
  {::db/pool     th/*pool*
   ::rds/client  (get th/*system* :app.redis/client)
   ::mtx/metrics (get th/*system* :app.metrics/metrics)
   ::wrk/tenant  (cf/get :tenant)
   ::batch-size  100
   ::lease       (cf/get-jobs-lease)
   ::timeout     (ct/duration "10s")})

(defn- make-metrics []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics
                                      [:jobs-dispatcher-batch-timing])}))

(defn- make-orphan-metrics []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics
                                      [:jobs-total-timing])}))

(defn- counter-value [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(defn- histogram-sample-count [metrics labels]
  (->> (enumeration-seq
        (.metricFamilySamples ^io.prometheus.client.CollectorRegistry
         (mtx/get-registry metrics)))
       (mapcat (fn [^Collector$MetricFamilySamples family]
                 (.samples family)))
       (filter (fn [^Collector$MetricFamilySamples$Sample sample]
                 (and (str/ends-with? (.-name sample) "_count")
                      (= labels (vec (.-labelValues sample))))))
       (map (fn [^Collector$MetricFamilySamples$Sample sample]
              (long (.-value sample))))
       (reduce + 0)))

(defn- mk-job
  [{:keys [name queue status scheduled-at modified-at tenant dedupe label]
    :or   {name "test-job"
           queue "test"
           tenant (cf/get :tenant)
           status "new"
           scheduled-at (ct/now)
           modified-at (ct/now)}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id            id
                         :name          name
                         :tenant        tenant
                         :queue         queue
                         :label         label
                         :dedupe        (boolean dedupe)
                         :params        (db/json {})
                         :priority      100
                         :max-retries   3
                         :retry-num     0
                         :status        status
                         :scheduled-at  scheduled-at
                         :created-at    (ct/now)
                         :modified-at   modified-at})
    id))

(defn- get-row
  [id]
  (-> (th/db-get :job {:id id} :status :modified-at :scheduled-at :error)
      (update :error jobs/decode-job-error)
      (assoc :events (->> (th/db-exec! ["SELECT kind, payload FROM job_event
                                         WHERE job_id = ? ORDER BY id" id])
                          (mapv (fn [{:keys [kind payload]}]
                                  {:kind kind
                                   :payload (db/decode-json-pgobject payload)}))))))

(defn- queue-key
  [queue-name]
  (wrk/queue-key (cf/get :tenant) queue-name))

(defn- test-fixture [next]
  (th/database-reset
   (fn []
     ;; clear the redis hand-off list so tests don't see stale payloads
     (let [conn (rds/connect (mk-cfg))]
       (try
         (rds/del conn (queue-key "test"))
         (finally
           (rds/close conn)))
       (next)))))

(t/use-fixtures :each test-fixture)

(defn- fill-queue
  "Put one dummy payload in a queue, as a worker that has not drained it."
  [queue-name]
  (let [conn (rds/connect (mk-cfg))]
    (try
      (rds/rpush conn (queue-key queue-name)
                 [(json/encode [(str (uuid/next)) (ct/format-inst (ct/now))])])
      (finally
        (rds/close conn)))))

(defn- drain-queue
  [queue-name]
  (let [conn (rds/connect (mk-cfg))]
    (try
      (let [cmd (.-cmd conn)
            res (.lrange ^io.lettuce.core.api.sync.RedisCommands
                 cmd (queue-key queue-name) 0 -1)]
        (vec res))
      (finally
        (rds/del conn (queue-key queue-name))
        (rds/close conn)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest dispatcher-claims-due-jobs-and-pushes-json-payload
  (let [cfg  (mk-cfg)
        id   (mk-job {})
        _    (wdisp/run-batch cfg)
        row  (get-row id)
        key  (queue-key "test")]

    (t/testing "claimed job is marked scheduled"
      (t/is (= "scheduled" (:status row))))

    (t/testing "payload is plain JSON [uuid, iso-8601]"
      (let [payloads (drain-queue "test")]
        (t/is (= 1 (count payloads)))
        (let [[job-id scheduled-at :as payload] (json/decode (first payloads))]
          (t/is (string? job-id))
          (t/is (= (str id) job-id))
          (t/is (string? scheduled-at))
          (t/testing "round-trip: inst-ms are equal after ct/inst"
            (let [expected  (:scheduled-at row)
                  actual    (ct/inst scheduled-at)]
              (t/is (= (inst-ms expected) (inst-ms actual))))))))))

(t/deftest dispatcher-claims-retry-jobs-too
  (let [cfg (mk-cfg)
        id  (mk-job {:status "retry"})]
    (wdisp/run-batch cfg)
    (t/is (= "scheduled" (:status (get-row id))))))

(t/deftest dispatcher-does-not-claim-future-or-terminal-jobs
  (let [cfg     (mk-cfg)
        future  (mk-job {:scheduled-at (ct/plus (ct/now)
                                                (ct/duration {:minutes 10}))})
        running (mk-job {:status "running"})]
    (wdisp/run-batch cfg)
    (t/is (= "new" (:status (get-row future))))
    (t/is (= "running" (:status (get-row running))))))

(t/deftest dispatcher-aborts-a-lost-job-whose-queue-is-empty
  ;; `scheduled` means the job was handed to a queue. When the queue has
  ;; nothing waiting and the row has been there longer than the grace, the
  ;; message is gone and the job is terminal. Known limit: the grace is
  ;; measured from the push, so a message that waited longer in a backlog
  ;; and is popped while the sweep runs (the queue empty at that instant)
  ;; can lose the race with its claim; the window is the worker's
  ;; pop-to-claim round trip.
  (let [cfg  (mk-cfg)
        lost (mk-job {:status      "scheduled"
                      :modified-at (ct/minus (ct/now)
                                             (ct/duration {:minutes 6}))})]
    (wdisp/run-batch cfg)
    (let [row (get-row lost)]
      (t/is (= "aborted" (:status row)))
      (t/is (= jobs/lost-error (:error row)))
      (t/is (= [{:kind "end" :payload {:outcome "aborted"}}] (:events row)))
      (t/testing "an aborted job is terminal: it is never retried"
        (t/is (zero? (jobs/retry-job cfg (:id row) 1 (ct/now) jobs/lost-error :backoff)))))))

(t/deftest dispatcher-leaves-a-fresh-scheduled-job-alone
  (let [cfg   (mk-cfg)
        fresh (mk-job {:status      "scheduled"
                       :modified-at (ct/minus (ct/now)
                                              (ct/duration {:minutes 4}))})]
    (wdisp/run-batch cfg)
    (let [row (get-row fresh)]
      (t/is (= "scheduled" (:status row)))
      (t/is (nil? (:error row)))
      (t/is (= [] (:events row))))))

(t/deftest dispatcher-leaves-a-scheduled-job-that-is-still-in-its-queue
  ;; The queue has a message waiting: the job may be behind a backlog, so
  ;; it is not lost. Aborting on time alone would kill legitimate work.
  (let [cfg  (mk-cfg)
        lost (mk-job {:status      "scheduled"
                      :modified-at (ct/minus (ct/now)
                                             (ct/duration {:minutes 6}))})]
    (fill-queue "test")
    (wdisp/run-batch cfg)
    (let [row (get-row lost)]
      (t/is (= "scheduled" (:status row)))
      (t/is (nil? (:error row)))
      (t/is (= [] (:events row))))))

(t/deftest dispatcher-aborts-a-scheduled-job-past-the-cap
  ;; A message lost behind a backlog (or a queue with no consumer) never
  ;; drains: past the cap the job is aborted anyway.
  (let [cfg  (mk-cfg)
        lost (mk-job {:status      "scheduled"
                      :modified-at (ct/minus (ct/now)
                                             (ct/duration {:hours 25}))})]
    (fill-queue "test")
    (wdisp/run-batch cfg)
    (t/is (= "aborted" (:status (get-row lost))))))

(t/deftest lost-sweep-pages-the-cap-branch
  ;; A row past the cap is lost whatever its queue holds, and the read is
  ;; bounded: with a batch of one, each pass aborts the oldest candidate
  ;; and the next pass continues. Only the grace rows pay the LLEN.
  (let [cfg (mk-cfg)
        q   "test-lost-cap"
        a   (mk-job {:status      "scheduled"
                     :queue       q
                     :modified-at (ct/minus (ct/now) (ct/duration {:hours 30}))})
        b   (mk-job {:status      "scheduled"
                     :queue       q
                     :modified-at (ct/minus (ct/now) (ct/duration {:hours 29}))})]
    (fill-queue q)
    (with-redefs-fn {#'wdisp/lost-batch-size 1}
      #(wdisp/run-batch cfg))
    (t/is (= 1 (count (filter #(= "aborted" (:status (get-row %))) [a b]))))
    (with-redefs-fn {#'wdisp/lost-batch-size 1}
      #(wdisp/run-batch cfg))
    (t/is (every? #(= "aborted" (:status (get-row %))) [a b]))))

(t/deftest lost-sweep-pages-the-grace-window
  ;; Inside the grace window the read is bounded and the queue decides: an
  ;; empty queue makes the row lost, and with a batch of one the next pass
  ;; continues where the previous one stopped.
  (let [cfg (mk-cfg)
        q   "test-lost-grace"
        a   (mk-job {:status      "scheduled"
                     :queue       q
                     :modified-at (ct/minus (ct/now) (ct/duration {:minutes 7}))})
        b   (mk-job {:status      "scheduled"
                     :queue       q
                     :modified-at (ct/minus (ct/now) (ct/duration {:minutes 6}))})]
    (with-redefs-fn {#'wdisp/lost-batch-size 1}
      #(wdisp/run-batch cfg))
    (t/is (= 1 (count (filter #(= "aborted" (:status (get-row %))) [a b]))))
    (with-redefs-fn {#'wdisp/lost-batch-size 1}
      #(wdisp/run-batch cfg))
    (t/is (every? #(= "aborted" (:status (get-row %))) [a b]))))

(t/deftest dispatcher-does-not-abort-a-job-whose-heartbeat-renews-it
  ;; The heartbeat accepts a `scheduled` row (a previous attempt may still
  ;; beat): it refreshes `modified_at`, so the outer WHERE must re-check it.
  (let [cfg  (mk-cfg)
        lost (mk-job {:status      "scheduled"
                      :modified-at (ct/minus (ct/now)
                                             (ct/duration {:minutes 6}))})]
    (with-redefs [rds/llen (fn [_ _]
                             (th/db-update! :job {:modified-at (ct/now)} {:id lost})
                             0)]
      (wdisp/run-batch cfg))
    (let [row (get-row lost)]
      (t/is (= "scheduled" (:status row)))
      (t/is (nil? (:error row)))
      (t/is (= [] (:events row))))))

(t/deftest dispatcher-llens-only-its-tenant-queue
  ;; The queue key carries the tenant: a message waiting for another
  ;; environment cannot protect a lost row of this one.
  (let [cfg     (mk-cfg)
        foreign (wrk/queue-key "other-tenant" "test")
        conn    (rds/connect (mk-cfg))
        lost    (mk-job {:status      "scheduled"
                         :modified-at (ct/minus (ct/now)
                                                (ct/duration {:minutes 6}))})]
    (try
      (rds/rpush conn foreign
                 [(json/encode [(str (uuid/next))
                                (ct/format-inst (ct/now))])])
      (wdisp/run-batch cfg)
      (t/is (= "aborted" (:status (get-row lost))))
      (finally
        (rds/del conn foreign)
        (rds/close conn)))))

(t/deftest dispatcher-aborts-two-lost-dedupe-jobs-with-the-same-key
  ;; A Redis restart loses every message at once: two deduped jobs with the
  ;; same key can be `scheduled` together. Neither returns to `new` (that
  ;; would violate `job__new_dedupe__uniq`); both are aborted and the batch
  ;; keeps going.
  (let [cfg (mk-cfg)
        a   (mk-job {:status      "scheduled"
                     :dedupe      true
                     :label       "k"
                     :modified-at (ct/minus (ct/now)
                                            (ct/duration {:minutes 6}))})
        b   (mk-job {:status      "scheduled"
                     :dedupe      true
                     :label       "k"
                     :modified-at (ct/minus (ct/now)
                                            (ct/duration {:minutes 7}))})]
    (wdisp/run-batch cfg)
    (t/is (= "aborted" (:status (get-row a))))
    (t/is (= "aborted" (:status (get-row b))))
    (t/is (empty? (th/db-query :job {:status "new"})))
    (t/is (empty? (drain-queue "test")))))

(t/deftest dispatcher-abort-lost-records-the-terminal-counter
  (let [metrics (make-orphan-metrics)
        cfg     (assoc (mk-cfg) ::mtx/metrics metrics)]
    (mk-job {:status      "scheduled"
             :modified-at (ct/minus (ct/now)
                                    (ct/duration {:minutes 6}))})
    (wdisp/run-batch cfg)
    (t/testing "a job lost in transit counts as an aborted terminal outcome"
      (t/is (= 1 (histogram-sample-count metrics ["test-job" "aborted"]))))))

(t/deftest dispatcher-does-not-abort-a-job-claimed-before-the-sweep
  ;; The candidate is read first and the UPDATE runs later: a runner can
  ;; claim the row in between. The outer WHERE must see the new status and
  ;; affect no row, or the job would be aborted while running.
  (let [cfg  (mk-cfg)
        lost (mk-job {:status      "scheduled"
                      :modified-at (ct/minus (ct/now)
                                             (ct/duration {:minutes 6}))})]
    (with-redefs [rds/llen (fn [_ _]
                             (th/db-update! :job {:status "running"
                                                  :modified-at (ct/now)}
                                            {:id lost})
                             0)]
      (wdisp/run-batch cfg))
    (let [row (get-row lost)]
      (t/is (= "running" (:status row)))
      (t/is (nil? (:error row)))
      (t/is (= [] (:events row))))))

(t/deftest dispatcher-aborts-a-lost-job-once
  ;; Two dispatchers of the same tenant read the same candidate: only the
  ;; first transition wins, so there is one end event and one sample.
  (let [metrics (make-orphan-metrics)
        cfg     (assoc (mk-cfg) ::mtx/metrics metrics)
        lost    (mk-job {:status      "scheduled"
                         :modified-at (ct/minus (ct/now)
                                                (ct/duration {:minutes 6}))})]
    (run! deref (doall (repeatedly 2 #(future (wdisp/run-batch cfg)))))
    (t/is (= "aborted" (:status (get-row lost))))
    (t/is (= 1 (count (filter #(= "end" (:kind %))
                              (:events (get-row lost))))))
    (t/is (= 1 (histogram-sample-count metrics ["test-job" "aborted"])))))

(t/deftest dispatcher-marks-stale-running-jobs-as-aborted-by-lease
  (let [cfg    (mk-cfg)
        stale  (mk-job {:status     "running"
                        :modified-at (ct/minus (ct/now)
                                               (ct/plus (cf/get-jobs-lease)
                                                        (ct/duration {:minutes 1})))})
        fresh  (mk-job {:status "running"})]
    (wdisp/run-batch cfg)
    (let [stale-row (get-row stale)]
      (t/is (= "aborted" (:status stale-row)))
      (t/testing "the error is the structured one, decoded back to keywords"
        (t/is (= jobs/orphan-error (:error stale-row))))
      (t/testing "the sweep stores the end event with the aborted outcome"
        (t/is (= [{:kind "end" :payload {:outcome "aborted"}}]
                 (:events stale-row))))
      (t/testing "an aborted job is terminal: it is never retried nor reclaimed"
        (t/is (zero? (jobs/retry-job cfg
                                     (:id stale-row)
                                     1 (ct/now) jobs/orphan-error :backoff)))))

    (t/testing "recent running job is not touched"
      (t/is (= "running" (:status (get-row fresh)))))))

(t/deftest dispatcher-orphan-sweep-records-the-terminal-counter
  (let [metrics (make-orphan-metrics)
        cfg     (assoc (mk-cfg) ::mtx/metrics metrics)
        _       (mk-job {:status     "running"
                         :modified-at (ct/minus (ct/now)
                                                (ct/plus (cf/get-jobs-lease)
                                                         (ct/duration {:minutes 1})))})]
    (wdisp/run-batch cfg)
    (t/testing "the _count of the aborted outcome is the orphan count"
      (t/is (= 1 (histogram-sample-count metrics ["test-job" "aborted"]))))))

(t/deftest dispatcher-batch-without-pending-jobs-signals-wait
  (let [cfg (mk-cfg)]
    (t/is (= ::wdisp/wait (wdisp/run-batch cfg)))))

(t/deftest dispatcher-failed-mark-leaves-no-orphan-payload
  ;; Mark runs before push: when the mark fails, the push never runs, so
  ;; a rolled-back batch must leave neither a marked row nor a payload.
  (let [cfg   (assoc (mk-cfg) ::wdisp/timeout (ct/duration {:millis 10}))
        id    (mk-job {})
        orig  @#'wdisp/mark-as-scheduled
        calls (atom 0)]
    (alter-var-root #'wdisp/mark-as-scheduled
                    (constantly (fn [& args]
                                  (when (= 1 (swap! calls inc))
                                    (throw (ex-info "boom" {})))
                                  (apply orig args))))
    (try
      (wdisp/run-batch cfg)
      (t/testing "failed mark rolls back with no payload pushed"
        (t/is (= "new" (:status (get-row id))))
        (t/is (empty? (drain-queue "test"))))
      (finally
        (alter-var-root #'wdisp/mark-as-scheduled (constantly orig))))
    (t/testing "next batch delivers exactly once"
      (wdisp/run-batch cfg)
      (t/is (= "scheduled" (:status (get-row id))))
      (t/is (= 1 (count (drain-queue "test")))))))

(t/deftest dispatcher-records-one-redis-failure
  (let [metrics (make-metrics)
        cfg     (assoc (mk-cfg) ::mtx/metrics metrics
                       ::wdisp/timeout (ct/duration {:millis 10}))
        _       (mk-job {})]
    (with-redefs [rds/rpush (fn [& _] (throw (ex-info "redis down" {})))]
      (wdisp/run-batch cfg))
    (t/is (= 1 (histogram-sample-count metrics ["execution" "failed"])))
    (t/is (= 0 (histogram-sample-count metrics ["dispatch" "failed"])))))

(t/deftest dispatcher-push-failure-rolls-back-without-throwing
  (let [cfg  (assoc (mk-cfg) ::wdisp/timeout (ct/duration {:millis 10}))
        id   (mk-job {})
        orig @#'rds/rpush]
    (alter-var-root #'rds/rpush
                    (constantly (fn [& _] (throw (ex-info "redis down" {})))))
    (try
      ;; run-batch catches the failure (sleep path) instead of throwing;
      ;; a throw would error this test by itself
      (wdisp/run-batch cfg)
      (t/testing "failed push rolls back the batch"
        (t/is (= "new" (:status (get-row id))))
        (t/is (empty? (drain-queue "test"))))
      (finally
        (alter-var-root #'rds/rpush (constantly orig))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TENANT ISOLATION
;; A batch is this tenant's: it must not read, claim or kill the rows of
;; another instance sharing the same database.

(t/deftest dispatcher-does-not-claim-another-tenants-jobs
  (let [cfg     (mk-cfg)
        foreign (mk-job {:tenant "other-tenant"})]
    (wdisp/run-batch cfg)
    (t/testing "the other tenant's job is not claimed"
      (t/is (= "new" (:status (get-row foreign)))))
    (t/testing "and nothing was pushed to this tenant's Redis list"
      (t/is (empty? (drain-queue "test"))))))

(t/deftest dispatcher-does-not-abort-another-tenants-lost-jobs
  (let [cfg     (mk-cfg)
        foreign (mk-job {:status      "scheduled"
                         :tenant      "other-tenant"
                         :modified-at (ct/minus (ct/now)
                                                (ct/duration {:minutes 61}))})]
    (wdisp/run-batch cfg)
    (t/testing "the other tenant's scheduled job keeps its status and its date"
      (let [row (get-row foreign)]
        (t/is (= "scheduled" (:status row)))
        (t/is (nil? (:error row)))
        (t/is (= [] (:events row)))))))

(t/deftest dispatcher-does-not-orphan-another-tenants-running-jobs
  (let [cfg     (mk-cfg)
        ;; older than any lease: this tenant's sweep would kill it
        foreign (mk-job {:status      "running"
                         :tenant      "other-tenant"
                         :modified-at (ct/minus (ct/now)
                                                (ct/plus (cf/get-jobs-lease)
                                                         (ct/duration {:minutes 1})))})]
    (wdisp/run-batch cfg)
    (t/testing "the other tenant's running job is still running"
      (let [row (get-row foreign)]
        (t/is (= "running" (:status row)))
        (t/is (nil? (:error row)))
        (t/is (= [] (:events row)))))))

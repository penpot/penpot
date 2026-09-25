;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.worker-runner-test
  (:require
   [app.common.json :as json]
   [app.common.schema :as sm]
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
   [app.worker.runner :as wrkr]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.Collector$MetricFamilySamples
   io.prometheus.client.Collector$MetricFamilySamples$Sample))

(t/use-fixtures :once th/state-init)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def received (atom []))

(def received-contexts (atom []))

(defn echo-handler
  "Handler implementation: the context comes from the job-def wrapper and
  the params are what the runner decoded from the row."
  [cfg context params]
  (swap! received conj params)
  (swap! received-contexts conj context)
  params)

(def schema:echo-params
  [:map
   [:object :keyword]
   [:deleted-at ::ct/inst]
   [:id ::sm/uuid]])

(defn- echo-job-def
  "Job-def with the standard wrapper: [context params], with the component
  deps closed over at init time."
  []
  {::jobs/name      :echo-runner
   ::jobs/schema    schema:echo-params
   ::jobs/handler   (fn [context params]
                      (echo-handler nil context params))
   ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:echo-params)})

(defn- get-defs
  []
  {:echo-runner (echo-job-def)})

(defn- mk-cfg
  [{:keys [defs]
    :or   {defs (get-defs)}}]
  {::db/pool     th/*pool*
   ::rds/conn    (rds/connect {::rds/client (get th/*system* :app.redis/client)
                               ::mtx/metrics (get th/*system* :app.metrics/metrics)})
   ::jobs/defs   defs
   ::mtx/metrics (get th/*system* :app.metrics/metrics)
   ::wrkr/id     "test-runner"
   ::wrkr/queue  (str (cf/get :tenant) ":test")
   ::wrkr/timeout (ct/duration "5s")})

(defn- make-metrics []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics
                                      [:jobs-queue-wait-timing
                                       :jobs-execution-timing
                                       :jobs-total-timing
                                       :tasks-timing])}))

(defn- histogram-sample-count [metrics id labels]
  (->> (enumeration-seq
        (.metricFamilySamples ^io.prometheus.client.CollectorRegistry
         (mtx/get-registry metrics)))
       (filter (fn [^Collector$MetricFamilySamples family]
                 (= (.-name family) (::mdef/name (main/default-metrics id)))))
       (mapcat (fn [^Collector$MetricFamilySamples family]
                 (.samples family)))
       (filter (fn [^Collector$MetricFamilySamples$Sample sample]
                 (and (str/ends-with? (.-name sample) "_count")
                      (= labels (vec (.-labelValues sample))))))
       (map (fn [^Collector$MetricFamilySamples$Sample sample]
              (long (.-value sample))))
       (reduce + 0)))

(defn- queue-key
  []
  (str "penpot.worker.queue:" (cf/get :tenant) ":test"))

(defn- clear-queue
  []
  (let [conn (rds/connect {::rds/client (get th/*system* :app.redis/client)
                           ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
    (try
      (rds/del conn (queue-key))
      (finally
        (rds/close conn)))))

(defn- test-fixture [next]
  (th/database-reset
   (fn []
     (reset! received [])
     (reset! received-contexts [])
     (clear-queue)
     (next))))

(t/use-fixtures :each test-fixture)

(defn- mk-job
  [{:keys [name status scheduled-at params max-retries label]
    :or   {name        "echo-runner"
           status      "new"
           ;; Valid echo params by default: direct inserts bypass the
           ;; submit! contract, and the runner validates decoded params.
           params      {:object     :snapshot
                        :deleted-at (ct/now)
                        :id         (uuid/next)}
           max-retries 3}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id           id
                         :name         name
                         :label        label
                         :queue        (str (cf/get :tenant) ":test")
                         :params       (db/json params)
                         :priority     100
                         :max-retries  max-retries
                         :retry-num    0
                         :status       status
                         :scheduled-at scheduled-at
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    id))

(defn- push-payload
  [job-id scheduled-at]
  (let [conn (rds/connect {::rds/client (get th/*system* :app.redis/client)
                           ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
    (try
      (rds/rpush conn (queue-key) [(json/encode [(str job-id)
                                                 (ct/format-inst scheduled-at)])])
      (finally
        (rds/close conn)))))

(defn- get-row
  [id]
  (-> (th/db-get :job {:id id})
      (as-> row
            (-> row
                (update :error #(cond-> % (db/pgobject? %) db/decode-json-pgobject))
                (update :result #(cond-> % (db/pgobject? %) db/decode-json-pgobject))))))

(defn- run-one
  [cfg]
  (@#'wrkr/run-worker-loop cfg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest runner-executes-handler-with-decoded-typed-params
  (let [params       {:object    :snapshot
                      :deleted-at (ct/now)
                      :id        (uuid/next)}
        scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at :params params})]

    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {}))

    (t/testing "handler received the decoded params"
      (t/is (= 1 (count @received)))
      (let [params' (first @received)]
        (t/is (keyword? (:object params')))
        (t/is (= :snapshot (:object params')))
        (t/is (uuid? (:id params')))
        (t/is (ct/inst? (:deleted-at params')))))

    (t/testing "job row is completed with the handler result"
      (let [row (get-row job-id)]
        (t/is (= "completed" (:status row)))
        (t/is (some? (:started-at row)))
        (t/is (some? (:completed-at row)))
        (t/is (nil? (:error row)))))))

(t/deftest runner-builds-the-handler-context-from-the-row
  (let [params       {:object    :snapshot
                      :deleted-at (ct/now)
                      :id         (uuid/next)}
        scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at
                              :params      params
                              :label       "runner-label"})]

    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {}))

    (t/testing "the handler receives exactly the four context keys"
      (t/is (= 1 (count @received-contexts)))
      (let [context (first @received-contexts)]
        (t/is (= #{:id :name :label :resource-id} (set (keys context))))
        (t/is (= job-id (:id context)))
        (t/is (= "echo-runner" (:name context)))
        (t/is (= "runner-label" (:label context)))
        (t/is (nil? (:resource-id context)))

        (t/testing "and nothing of the row leaks into it"
          (t/is (not (contains? context :params)))
          (t/is (not (contains? context :status)))
          (t/is (not (contains? context :result)))
          (t/is (not (contains? context :retry-num)))
          (t/is (not (contains? context :max-retries)))
          (t/is (not (contains? context :attempt)))
          (t/is (not (contains? context :queue))
                "the tenant-prefixed queue is a routing detail, not context"))))))

(t/deftest runner-records-queue-wait-execution-and-total
  (let [metrics (make-metrics)
        cfg     (assoc (mk-cfg {}) ::mtx/metrics metrics)
        at      (ct/truncate (ct/now) :millisecond)
        job-id  (mk-job {:scheduled-at at})]
    (push-payload job-id at)
    (run-one cfg)
    (t/is (= 1 (histogram-sample-count metrics
                                       :jobs-queue-wait-timing
                                       ["other" "other"])))
    (t/is (= 1 (histogram-sample-count metrics
                                       :jobs-execution-timing
                                       ["other" "other"])))
    (t/is (= 1 (histogram-sample-count metrics
                                       :jobs-total-timing
                                       ["other" "other" "completed"])))))

(t/deftest runner-skips-cancelled-jobs
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at})
        _            (th/db-update! :job {:status "cancelled"} {:id job-id})]

    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {}))

    (t/testing "conditional claim found 0 rows: state untouched"
      (let [row (get-row job-id)]
        (t/is (= "cancelled" (:status row)))
        (t/is (nil? (:started-at row)))
        (t/is (nil? (:completed-at row)))))
    (t/testing "handler never invoked"
      (t/is (empty? @received)))))

(t/deftest runner-retry-with-backoff-respects-max-retries
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at :max-retries 1})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_context _params]
                               (throw (ex-info "transient failure"
                                               {:type ::wrk/retry
                                                :delay (ct/duration {:millis 1000})}))))}]
    (push-payload job-id scheduled-at)

    (t/testing "first retry attempt"
      (run-one (mk-cfg {:defs defs}))
      (let [row (get-row job-id)]
        (t/is (= "retry" (:status row)))
        (t/is (= 1 (:retry-num row)))
        (t/is (= {:code "failed" :ex-type "retry" :message "transient failure"} (:error row)))
        (t/testing "scheduled_at respects the backoff delay"
          (t/is (> (inst-ms (:scheduled-at row)) (inst-ms (ct/now)))))))

    (t/testing "second attempt exhausts max-retries and fails"
      (push-payload job-id (:scheduled-at (get-row job-id)))
      (run-one (mk-cfg {:defs defs}))
      (let [row (get-row job-id)]
        (t/is (= "failed" (:status row)))
        (t/is (= 1 (:retry-num row)))
        (t/is (= {:code "failed" :ex-type "retry" :message "transient failure"} (:error row)))))))

(t/deftest runner-retry-with-millis-delay-schedules-backoff
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_context _params]
                               (throw (ex-info "transient failure"
                                               {:type ::wrk/retry
                                                ;; plain Long literal, not a Duration
                                                :delay 5000}))))}]
    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {:defs defs}))
    (let [row (get-row job-id)]
      (t/testing "Long millis delay is honored (int? covers Long)"
        (t/is (= "retry" (:status row)))
        (t/is (= 1 (:retry-num row)))
        (t/testing "scheduled_at is ~5s in the future"
          (let [delta (- (inst-ms (:scheduled-at row)) (inst-ms (ct/now)))]
            (t/is (> delta 4000))
            (t/is (< delta 6000))))))))

(t/deftest runner-noop-retry-at-zero-schedules-base-delay
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_context _params]
                               (throw (ex-info "noop"
                                               {:type ::wrk/retry
                                                :strategy ::wrk/noop
                                                :delay 5000}))))}]
    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {:defs defs}))
    (let [row (get-row job-id)]
      (t/testing "noop retry schedules instead of NPEing on take 0"
        (t/is (= "retry" (:status row)))
        (t/is (= 0 (:retry-num row)))
        (t/testing "scheduled_at is ~5s in the future"
          (let [delta (- (inst-ms (:scheduled-at row)) (inst-ms (ct/now)))]
            (t/is (> delta 4000))
            (t/is (< delta 6000))))))))

(t/deftest runner-unhandled-exception-fails-when-no-retries-left
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at :max-retries 0})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_context _params] (throw (ex-info "fatal" {}))))}]
    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {:defs defs}))
    (let [row (get-row job-id)]
      (t/is (= "failed" (:status row)))
      (t/is (= {:code "failed" :ex-type "ex-info" :message "fatal"} (:error row))))))

(t/deftest runner-terminal-write-does-not-overwrite-orphan-failure
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_context _params]
                               ;; simulate the dispatcher marking the
                               ;; running job as orphan while the handler
                               ;; is executing
                               (th/db-update! :job
                                              {:status "failed"
                                               :error  (db/json {:code "orphan"})}
                                              {:id jobs/*job-id*})
                               :ok))}]
    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {:defs defs}))

    (let [row (get-row job-id)]
      (t/testing "the orphan failure is preserved (first-terminal-wins)"
        (t/is (= "failed" (:status row)))
        (t/is (= {:code "orphan"} (:error row)))
        (t/is (nil? (:completed-at row)))))))

(t/deftest runner-claim-requires-current-scheduled-at
  (let [at     (ct/truncate (ct/now) :millisecond)
        job-id (mk-job {:status "scheduled" :scheduled-at at})
        stale  (ct/minus at (ct/duration {:minutes 6}))]
    (t/testing "stale scheduled_at claims nothing"
      (t/is (zero? (#'wrkr/claim-job (mk-cfg {}) job-id stale)))
      (t/is (= "scheduled" (:status (get-row job-id)))))
    (t/testing "current scheduled_at claims the row"
      (t/is (= 1 (#'wrkr/claim-job (mk-cfg {}) job-id at)))
      (t/is (= "running" (:status (get-row job-id)))))))

(t/deftest runner-skips-stale-payload-after-reschedule
  (let [stale-at (ct/truncate (ct/now) :millisecond)
        job-id   (mk-job {:scheduled-at stale-at})
        fresh-at (ct/plus stale-at (ct/duration {:minutes 6}))]
    ;; dispatcher reschedules: new scheduled_at + fresh payload
    (th/db-update! :job {:status "scheduled" :scheduled-at fresh-at} {:id job-id})

    (t/testing "stale payload is skipped, handler never invoked"
      (push-payload job-id stale-at)
      (run-one (mk-cfg {}))
      (t/is (empty? @received))
      (let [row (get-row job-id)]
        (t/is (= "scheduled" (:status row)))
        (t/is (nil? (:started-at row)))))

    (t/testing "fresh payload still executes"
      (push-payload job-id fresh-at)
      (run-one (mk-cfg {}))
      (t/is (= 1 (count @received)))
      (t/is (= "completed" (:status (get-row job-id)))))))

(t/deftest runner-unknown-job-name-fails-fast
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:name "no-such-job"
                              :scheduled-at scheduled-at
                              :max-retries 3})]
    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {}))
    (let [row (get-row job-id)]
      (t/testing "no retries burned on a permanently-unknown name"
        (t/is (= "failed" (:status row)))
        (t/is (= 0 (:retry-num row)))
        (t/is (nil? (:completed-at row)))
        (t/is (= "not-found" (:ex-type (:error row))))))))

(t/deftest runner-schema-violation-fails-fast-without-retry
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job {:scheduled-at scheduled-at
                              :max-retries 3
                              :params {:object :snapshot
                                       :deleted-at (ct/now)
                                       :id "not-a-uuid"}})]
    (push-payload job-id scheduled-at)
    (run-one (mk-cfg {}))
    (let [row (get-row job-id)]
      (t/testing "permanent schema violation fails fast"
        (t/is (= "failed" (:status row)))
        (t/is (= 0 (:retry-num row)))
        (t/is (nil? (:completed-at row)))
        (t/is (= "assertion" (:ex-type (:error row))))))))

(t/deftest invoke-executes-handlers-in-process-with-decoded-params
  (let [raw-params {:object "snapshot"
                    :deleted-at "2026-01-01T00:00:00Z"
                    :id (str (uuid/next))}]
    (reset! received [])
    (let [result (jobs/invoke (merge (mk-cfg {})
                                     {::jobs/name   :echo-runner
                                      ::jobs/params raw-params}))]
      (t/is (= 1 (count @received)))
      (let [params' (first @received)]
        (t/is (keyword? (:object params')))
        (t/is (= :snapshot (:object params')))
        (t/is (uuid? (:id params')))
        (t/is (ct/inst? (:deleted-at params'))))
      (t/testing "invoke! returns the handler result"
        (t/is (= (first @received) result))))))

(t/deftest runner-skips-malformed-payloads
  (let [conn (rds/connect {::rds/client (get th/*system* :app.redis/client)
                           ::mtx/metrics (get th/*system* :app.metrics/metrics)})]
    (try
      (rds/rpush conn (queue-key) ["not-json"
                                   (json/encode ["not-a-uuid" "not-an-inst"])])
      (finally
        (rds/close conn))))
  (t/testing "non-JSON payload is skipped, handler never invoked"
    (run-one (mk-cfg {}))
    (t/is (empty? @received)))
  (t/testing "JSON payload with wrong shape is skipped too"
    (run-one (mk-cfg {}))
    (t/is (empty? @received))))

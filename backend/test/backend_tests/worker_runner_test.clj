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
   [app.metrics :as-alias mtx]
   [app.redis :as rds]
   [app.worker :as wrk]
   [app.worker.runner :as wrkr]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def received (atom []))

(defn echo-handler
  [cfg params]
  (swap! received conj params)
  params)

(def schema:echo-params
  [:map
   [:object :keyword]
   [:deleted-at ::ct/inst]
   [:id ::sm/uuid]])

(defn- echo-job-def
  []
  {::jobs/name      :echo-runner
   ::jobs/schema    schema:echo-params
   ::jobs/handler   (partial echo-handler nil)
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

(defn- queue-key
  []
  (str "penpot.worker.queue:" (cf/get :tenant) ":test"))

(defn- clear-queue!
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
     (clear-queue!)
     (next))))

(t/use-fixtures :each test-fixture)

(defn- mk-job!
  [{:keys [name status scheduled-at props max-retries]
    :or   {name        "echo-runner"
           status      "new"
           props       {}
           max-retries 3}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id           id
                         :name         name
                         :queue        (str (cf/get :tenant) ":test")
                         :props        (db/json props)
                         :priority     100
                         :max-retries  max-retries
                         :retry-num    0
                         :status       status
                         :scheduled-at scheduled-at
                         :created-at   (ct/now)
                         :modified-at  (ct/now)})
    id))

(defn- push-payload!
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

(defn- run-one!
  [cfg]
  (@#'wrkr/run-worker-loop! cfg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest runner-executes-handler-with-decoded-typed-params
  (let [params       {:object    :snapshot
                      :deleted-at (ct/now)
                      :id        (uuid/next)}
        scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job! {:scheduled-at scheduled-at :props params})]

    (push-payload! job-id scheduled-at)
    (run-one! (mk-cfg {}))

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

(t/deftest runner-skips-cancelled-jobs
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job! {:scheduled-at scheduled-at})
        _            (th/db-update! :job {:status "cancelled"} {:id job-id})]

    (push-payload! job-id scheduled-at)
    (run-one! (mk-cfg {}))

    (t/testing "conditional claim found 0 rows: state untouched"
      (let [row (get-row job-id)]
        (t/is (= "cancelled" (:status row)))
        (t/is (nil? (:started-at row)))
        (t/is (nil? (:completed-at row)))))
    (t/testing "handler never invoked"
      (t/is (empty? @received)))))

(t/deftest runner-retry-with-backoff-respects-max-retries
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job! {:scheduled-at scheduled-at :max-retries 1})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_params]
                               (throw (ex-info "transient failure"
                                               {:type ::wrk/retry
                                                :delay (ct/duration {:millis 1000})}))))}]
    (push-payload! job-id scheduled-at)

    (t/testing "first retry attempt"
      (run-one! (mk-cfg {:defs defs}))
      (let [row (get-row job-id)]
        (t/is (= "retry" (:status row)))
        (t/is (= 1 (:retry-num row)))
        (t/is (= {:code "failed" :message "transient failure"} (:error row)))
        (t/testing "scheduled_at respects the backoff delay"
          (t/is (> (inst-ms (:scheduled-at row)) (inst-ms (ct/now)))))))

    (t/testing "second attempt exhausts max-retries and fails"
      (push-payload! job-id (:scheduled-at (get-row job-id)))
      (run-one! (mk-cfg {:defs defs}))
      (let [row (get-row job-id)]
        (t/is (= "failed" (:status row)))
        (t/is (= 1 (:retry-num row)))
        (t/is (= {:code "failed" :message "transient failure"} (:error row)))))))

(t/deftest runner-unhandled-exception-fails-when-no-retries-left
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job! {:scheduled-at scheduled-at :max-retries 0})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_params] (throw (ex-info "fatal" {}))))}]
    (push-payload! job-id scheduled-at)
    (run-one! (mk-cfg {:defs defs}))
    (let [row (get-row job-id)]
      (t/is (= "failed" (:status row)))
      (t/is (= {:code "failed" :message "fatal"} (:error row))))))

(t/deftest runner-terminal-write-does-not-overwrite-orphan-failure
  (let [scheduled-at (ct/truncate (ct/now) :millisecond)
        job-id       (mk-job! {:scheduled-at scheduled-at})
        defs         {:echo-runner
                      (assoc (echo-job-def)
                             ::jobs/handler
                             (fn [_params]
                               ;; simulate the dispatcher marking the
                               ;; running job as orphan while the handler
                               ;; is executing
                               (th/db-update! :job
                                              {:status "failed"
                                               :error  (db/json {:code "orphan"})}
                                              {:id jobs/*job-id*})
                               :ok))}]
    (push-payload! job-id scheduled-at)
    (run-one! (mk-cfg {:defs defs}))

    (let [row (get-row job-id)]
      (t/testing "the orphan failure is preserved (first-terminal-wins)"
        (t/is (= "failed" (:status row)))
        (t/is (= {:code "orphan"} (:error row)))
        (t/is (nil? (:completed-at row)))))))

(t/deftest invoke-executes-handlers-in-process-with-decoded-params
  (let [raw-params {:object "snapshot"
                    :deleted-at "2026-01-01T00:00:00Z"
                    :id (str (uuid/next))}]
    (reset! received [])
    (let [result (wrk/invoke! (merge (mk-cfg {})
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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-management-jobs-test
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(defn- make-cfg []
  {::db/pool     th/*pool*
   ::mtx/metrics (get th/*system* :app.metrics/metrics)})

(defn- test-fixture [next]
  (th/database-reset next))

(t/use-fixtures :each test-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-job
  [{:keys [name status params scheduled-at]
    :or   {name "media-process"
           status "new"
           params {:x 1}
           scheduled-at (ct/now)}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id            id
                         :name          name
                         :queue         "test:media"
                         :params        (db/json params)
                         :priority      100
                         :max-retries   3
                         :retry-num     0
                         :status        status
                         :scheduled-at  scheduled-at
                         :created-at    (ct/now)
                         :modified-at   (ct/now)})
    id))

(defn- get-row
  [id]
  (let [row (th/db-get :job {:id id}
                       :status :result :error
                       :started-at :completed-at :modified-at)]
    (reduce (fn [row key]
              (update row key #(cond-> % (db/pgobject? %) db/decode-json-pgobject)))
            row
            [:result :error])))

(def ^:private test-error
  {:type :internal
   :code :boom
   :hint "boom"})

(defn- get-progresss
  "Progress events of a job, oldest first, with decoded payloads."
  [id]
  (->> (th/db-exec! ["SELECT payload FROM job_event
                      WHERE job_id = ? AND kind = 'progress'
                      ORDER BY created_at ASC, id ASC"
                     id])
       (mapv #(db/decode-json-pgobject (:payload %)))))

(defn- mgmt
  [type params]
  (th/management-command! (assoc params ::th/type type)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest claim-job-returns-run-with-name-and-params
  (let [scheduled-at (ct/now)
        job-id       (mk-job {:scheduled-at scheduled-at
                              :params {:command :process :file-id (str (uuid/next))}})
        out          (mgmt :claim-job {:job-id job-id
                                       :scheduled-at scheduled-at})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run
              :name   "media-process"
              :params {:command "process"
                       :file-id (get-in out [:result :params :file-id])}}
             (:result out)))
    (let [row (get-row job-id)]
      (t/is (= "running" (:status row)))
      (t/is (some? (:started-at row))))))

(t/deftest claim-job-skips-on-stale-scheduled-at
  (let [job-id (mk-job {})
        out    (mgmt :claim-job {:job-id      job-id
                                 :scheduled-at (ct/in-future {:minutes 5})})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip} (:result out)))
    (t/is (= "new" (:status (get-row job-id))))))

(t/deftest claim-job-skips-terminal-and-cancelled-rows
  (let [completed-id (mk-job {:status "completed"})
        cancelled-id (mk-job {:status "cancelled"})]
    (t/is (= {:action :skip}
             (:result (mgmt :claim-job {:job-id completed-id
                                        :scheduled-at (ct/now)}))))
    (t/is (= {:action :skip}
             (:result (mgmt :claim-job {:job-id cancelled-id
                                        :scheduled-at (ct/now)}))))
    (t/is (= "completed" (:status (get-row completed-id))))
    (t/is (= "cancelled" (:status (get-row cancelled-id))))))

(t/deftest claim-job-skips-missing-row
  (t/is (= {:action :skip}
           (:result (mgmt :claim-job {:job-id      (uuid/next)
                                      :scheduled-at (ct/now)})))))

(t/deftest claim-job-validates-params
  (let [out (mgmt :claim-job {:job-id (uuid/next)})]
    (t/is (= :validation (th/ex-type (:error out))))))

(t/deftest report-job-progress-appends-a-progress-event
  (let [job-id (mk-job {})
        _      (jobs/claim (make-cfg) job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:total 100 :current 50}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (t/is (= [{:total 100 :current 50}] (get-progresss job-id)))
    (t/testing "the job row has no progress column anymore"
      (t/is (not (contains? (get-row job-id) :progress))))))

(t/deftest report-job-progress-noop-on-terminal-row
  (let [job-id (mk-job {:status "completed"})
        out    (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:total 100 :current 100}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip} (:result out)))
    (t/is (= [] (get-progresss job-id)))))

(t/deftest report-job-progress-persists-rapid-reports
  (let [job-id (mk-job {})
        _      (jobs/claim (make-cfg) job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        _      (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:total 10 :current 3}})
        _      (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:total 10 :current 4}})]
    (t/testing "the second immediate report is not throttled away"
      (t/is (= [{:total 10 :current 3}
                {:total 10 :current 4}]
               (get-progresss job-id))))))

(t/deftest report-job-progress-validates-the-payload
  (let [job-id (mk-job {})
        _      (jobs/claim (make-cfg) job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))]
    (t/testing "current is mandatory"
      (t/is (= :validation (th/ex-type (:error (mgmt :report-job-progress
                                                     {:job-id job-id
                                                      :progress {:total 10}}))))))
    (t/testing "no extra key is accepted"
      (t/is (= :validation (th/ex-type (:error (mgmt :report-job-progress
                                                     {:job-id job-id
                                                      :progress {:current 1
                                                                 :message "boom"}}))))))
    (t/is (= [] (get-progresss job-id)))))

(t/deftest complete-job-marks-completed-with-result
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt :complete-job {:job-id job-id
                                    :result {:value 42}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (= {:value 42} (:result row)))
      (t/is (some? (:completed-at row))))))

(t/deftest complete-job-accepts-nested-result
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        result {:value 42 :nested {:items [1 2 {:three 3}] :ok true}}
        out    (mgmt :complete-job {:job-id job-id :result result})]
    (t/is (nil? (:error out)))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (= result (:result row))))))

(t/deftest complete-job-without-result-stores-null
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt :complete-job {:job-id job-id :result nil})]
    (t/is (nil? (:error out)))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (nil? (:result row))))))

(t/deftest complete-job-with-unserializable-result-stores-null
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))]
    (jobs/complete cfg :job-id job-id :result (Object.))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (nil? (:result row))))))

(t/deftest complete-job-with-unserializable-result-logs-job-name
  (let [cfg      (make-cfg)
        job-id   (mk-job {:name "media-process"})
        _        (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        captured (atom nil)]
    (with-redefs [l/emit-log (fn [props _cause _ctx _logger _level _sync?]
                               (reset! captured (into {} @props)))]
      (jobs/complete cfg :job-id job-id :result (Object.)))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (nil? (:result row)))
      (t/testing "the serialization warning carries the job name"
        (t/is (= "media-process" (:job-name @captured)))))))

(t/deftest complete-and-fail-clean-throttle-state
  (let [cfg     (make-cfg)
        job-id1 (mk-job {})
        job-id2 (mk-job {})]
    (jobs/claim cfg job-id1 (:scheduled-at (th/db-get :job {:id job-id1} :id :scheduled-at)))
    (jobs/claim cfg job-id2 (:scheduled-at (th/db-get :job {:id job-id2} :id :scheduled-at)))
    (jobs/heartbeat cfg :job-id job-id1)
    (jobs/heartbeat cfg :job-id job-id2 :progress {:current 1})
    (jobs/complete cfg :job-id job-id1)
    (jobs/fail cfg job-id2 test-error)
    (t/is (not (contains? @@#'jobs/heartbeats job-id1)))
    (t/is (not (contains? @@#'jobs/progresses job-id2)))))

(t/deftest complete-and-fail-respect-first-terminal-wins
  (let [cfg        (make-cfg)
        running-id (mk-job {})
        orphan-id  (mk-job {:status "failed"})
        _          (jobs/claim cfg running-id
                               (:scheduled-at (th/db-get :job {:id running-id} :id :scheduled-at)))]
    ;; a running job completes; a later complete/fail on the same row is a no-op
    (t/is (= 1 (jobs/complete cfg :job-id running-id :result {:value 1})))
    (t/is (= 0 (jobs/complete cfg :job-id running-id :result {:value 2})))
    (t/is (= 0 (jobs/fail cfg running-id test-error)))
    (t/is (= {:value 1} (:result (get-row running-id))))

    ;; an orphan (failed by the dispatcher) is never overwritten
    (t/is (= 0 (jobs/complete cfg :job-id orphan-id :result {:value 3})))
    (t/is (= 0 (jobs/fail cfg orphan-id test-error)))))

(t/deftest fail-job-marks-failed-with-error
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt :fail-job {:job-id job-id
                                :error  {:type :internal
                                         :code "processing-error"
                                         :hint "bad image"}})]
    (t/is (nil? (:error out)))
    (let [row (get-row job-id)]
      (t/is (= "failed" (:status row)))
      (t/is (= {:type "internal"
                :code "processing-error"
                :hint "bad image"}
               (:error row))))))

(t/deftest fail-job-validates-params
  (let [job-id (mk-job {})]
    (t/is (= :validation (th/ex-type (:error (mgmt :fail-job {:job-id job-id})))))))

(t/deftest terminal-writers-report-skip-on-terminal-rows
  (let [done-id (mk-job {:status "completed"})]
    (t/testing "complete on a terminal row reports skip"
      (let [out (mgmt :complete-job {:job-id done-id :result {:x 1}})]
        (t/is (nil? (:error out)))
        (t/is (= {:action :skip} (:result out)))))
    (t/testing "fail on a terminal row reports skip"
      (let [out (mgmt :fail-job {:job-id done-id
                                 :error  {:type :internal
                                          :code "processing-error"
                                          :hint "bad image"}})]
        (t/is (nil? (:error out)))
        (t/is (= {:action :skip} (:result out)))))
    (t/testing "progress on a terminal row reports skip"
      (let [out (mgmt :report-job-progress {:job-id  done-id
                                            :progress {:total 10 :current 4}})]
        (t/is (nil? (:error out)))
        (t/is (= {:action :skip} (:result out))))))
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))]
    (t/testing "live paths report run"
      (t/is (= {:action :run}
               (:result (mgmt :report-job-progress {:job-id  job-id
                                                    :progress {:total 10 :current 4}}))))
      (t/is (= {:action :run}
               (:result (mgmt :complete-job {:job-id job-id
                                             :result {:x 1}})))))))

(t/deftest complete-job-associates-a-resource-id
  (let [cfg         (make-cfg)
        resource-id (uuid/next)]
    (th/db-insert! :storage-object {:id resource-id :backend "test"})
    (let [job-id (mk-job {})]
      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
      (t/is (nil? (:error (mgmt :complete-job {:job-id job-id
                                               :result {:v 1}
                                               :resource-id resource-id}))))
      (t/is (= resource-id (:resource-id (get-row job-id)))))))

(t/deftest complete-job-refuses-a-second-resource-id
  (let [cfg    (make-cfg)
        first  (uuid/next)
        second (uuid/next)]
    (doseq [id [first second]]
      (th/db-insert! :storage-object {:id id :backend "test"}))
    (let [job-id (mk-job {})]
      (th/db-update! :job {:resource-id first} {:id job-id})
      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
      (let [out (mgmt :complete-job {:job-id job-id
                                     :result {:v 1}
                                     :resource-id second})]
        (t/is (= :validation (th/ex-type (:error out)))))
      (t/testing "the job keeps the resource it had and is still running"
        (let [row (get-row job-id)]
          (t/is (= first (:resource-id row)))
          (t/is (= "running" (:status row))))))))

(t/deftest complete-job-validates-the-resource-id
  (let [cfg    (make-cfg)
        job-id (mk-job {})]
    (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
    (t/is (= :validation
             (th/ex-type (:error (mgmt :complete-job {:job-id job-id
                                                      :result {:v 1}
                                                      :resource-id "not-a-uuid"})))))))

(t/deftest fail-job-validates-the-error-shape
  (let [cfg (make-cfg)
        run (fn [error]
              (let [job-id (mk-job {})]
                (jobs/claim cfg job-id
                            (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
                [(mgmt :fail-job {:job-id job-id :error error}) job-id]))]

    (t/testing "hint is required"
      (t/is (= :validation
               (th/ex-type (:error (first (run {:type :internal
                                                :code :processing-error})))))))

    (t/testing "a string code over the wire decodes into the keyword"
      (let [[out job-id] (run {:type    :internal
                               :code    "processing-error"
                               :hint    "bad image"})]
        (t/is (nil? (:error out)))
        (t/is (= {:type :internal :code :processing-error :hint "bad image"}
                 (jobs/decode-job-error (:error (get-row job-id)))))))

    (t/testing "a keyword error is stored and decodes back untouched"
      (let [[out job-id] (run {:type :internal
                               :code :processing-error
                               :hint "bad image"})]
        (t/is (nil? (:error out)))
        (t/is (= {:type :internal :code :processing-error :hint "bad image"}
                 (jobs/decode-job-error (:error (get-row job-id)))))))

    (t/testing "worker-defined extra details are kept"
      (let [[out job-id] (run {:type    :internal
                               :code    :processing-error
                               :hint    "bad image"
                               :attempt 3})]
        (t/is (nil? (:error out)))
        (t/is (= 3 (:attempt (jobs/decode-job-error (:error (get-row job-id))))))))))

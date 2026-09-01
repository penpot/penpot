;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-management-jobs-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.jobs :as jobs]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(defn- test-fixture [next]
  (th/database-reset next))

(t/use-fixtures :each test-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-job!
  [{:keys [name status props scheduled-at]
    :or   {name "media-process"
           status "new"
           props {:x 1}
           scheduled-at (ct/now)}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id            id
                         :name          name
                         :queue         "test:media"
                         :props         (db/json props)
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
                       :status :result :error :progress
                       :started-at :completed-at :modified-at :id)]
    (reduce (fn [row key]
              (update row key #(cond-> % (db/pgobject? %) db/decode-json-pgobject)))
            row
            [:result :error :progress])))

(defn- mgmt!
  [type params]
  (th/management-command! (assoc params ::th/type type)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest claim-job-returns-run-with-name-and-props
  (let [scheduled-at (ct/now)
        job-id       (mk-job! {:scheduled-at scheduled-at
                               :props {:command :process :file-id (str (uuid/next))}})
        out          (mgmt! :claim-job {:job-id job-id
                                        :scheduled-at scheduled-at})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run
              :name   "media-process"
              :props  {:command "process"
                       :file-id (get-in out [:result :props :file-id])}}
             (:result out)))
    (let [row (get-row job-id)]
      (t/is (= "running" (:status row)))
      (t/is (some? (:started-at row))))))

(t/deftest claim-job-skips-on-stale-scheduled-at
  (let [job-id (mk-job! {})
        out    (mgmt! :claim-job {:job-id      job-id
                                  :scheduled-at (ct/in-future {:minutes 5})})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip} (:result out)))
    (t/is (= "new" (:status (get-row job-id))))))

(t/deftest claim-job-skips-terminal-and-cancelled-rows
  (let [completed-id (mk-job! {:status "completed"})
        cancelled-id (mk-job! {:status "cancelled"})]
    (t/is (= {:action :skip}
             (:result (mgmt! :claim-job {:job-id completed-id
                                         :scheduled-at (ct/now)}))))
    (t/is (= {:action :skip}
             (:result (mgmt! :claim-job {:job-id cancelled-id
                                         :scheduled-at (ct/now)}))))
    (t/is (= "completed" (:status (get-row completed-id))))
    (t/is (= "cancelled" (:status (get-row cancelled-id))))))

(t/deftest claim-job-skips-missing-row
  (t/is (= {:action :skip}
           (:result (mgmt! :claim-job {:job-id      (uuid/next)
                                       :scheduled-at (ct/now)})))))

(t/deftest claim-job-validates-params
  (let [out (mgmt! :claim-job {:job-id (uuid/next)})]
    (t/is (= :validation (th/ex-type (:error out))))))

(t/deftest report-job-progress-persists-progress
  (let [job-id (mk-job! {})
        _      (jobs/claim! {::db/pool th/*pool*} job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        _      (mgmt! :report-job-progress {:job-id  job-id
                                            :progress {:stage "render" :percent 50}})
        row    (get-row job-id)]
    (t/is (= {:stage "render" :percent 50} (:progress row)))))

(t/deftest report-job-progress-noop-on-terminal-row
  (let [job-id (mk-job! {:status "completed"})]
    (t/is (nil? (:error (mgmt! :report-job-progress
                               {:job-id  job-id
                                :progress {:stage "render"}}))))
    (t/is (nil? (:progress (get-row job-id))))))

(t/deftest complete-job-marks-completed-with-result
  (let [cfg    {::db/pool th/*pool*}
        job-id (mk-job! {})
        _      (jobs/claim! cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt! :complete-job {:job-id job-id
                                     :result {:value 42}})]
    (t/is (nil? (:error out)))
    (t/is (= {} (:result out)))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (= {:value 42} (:result row)))
      (t/is (some? (:completed-at row))))))

(t/deftest complete-job-without-result-stores-null
  (let [cfg    {::db/pool th/*pool*}
        job-id (mk-job! {})
        _      (jobs/claim! cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt! :complete-job {:job-id job-id})]
    (t/is (nil? (:error out)))
    (let [row (get-row job-id)]
      (t/is (= "completed" (:status row)))
      (t/is (nil? (:result row))))))

(t/deftest complete-and-fail-respect-first-terminal-wins
  (let [cfg        {::db/pool th/*pool*}
        running-id (mk-job! {})
        orphan-id  (mk-job! {:status "failed"})
        _          (jobs/claim! cfg running-id
                                (:scheduled-at (th/db-get :job {:id running-id} :id :scheduled-at)))]
    ;; a running job completes; a later complete/fail on the same row is a no-op
    (t/is (= 1 (jobs/complete! cfg running-id {:value 1})))
    (t/is (= 0 (jobs/complete! cfg running-id {:value 2})))
    (t/is (= 0 (jobs/fail! cfg running-id {:code "boom"})))
    (t/is (= {:value 1} (:result (get-row running-id))))

    ;; an orphan (failed by the dispatcher) is never overwritten
    (t/is (= 0 (jobs/complete! cfg orphan-id {:value 3})))
    (t/is (= 0 (jobs/fail! cfg orphan-id {:code "late"})))))

(t/deftest fail-job-marks-failed-with-error
  (let [cfg    {::db/pool th/*pool*}
        job-id (mk-job! {})
        _      (jobs/claim! cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt! :fail-job {:job-id job-id
                                 :error  {:code "processing-error" :hint "bad image"}})]
    (t/is (nil? (:error out)))
    (let [row (get-row job-id)]
      (t/is (= "failed" (:status row)))
      (t/is (= {:code "processing-error" :hint "bad image"} (:error row))))))

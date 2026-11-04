;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-management-jobs-test
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [app.rpc :as rpc]
   [app.setup :as-alias setup]
   [app.setup.keys :as keys]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]))

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
  [{:keys [name status params scheduled-at profile-id tenant]
    :or   {name "media-process"
           status "new"
           params {:x 1}
           scheduled-at (ct/now)}}]
  (let [id (uuid/next)]
    (th/db-insert! :job {:id            id
                         :name          name
                         :tenant        (or tenant (cf/get :tenant))
                         :queue         "media"
                         :params        (db/json params)
                         :priority      100
                         :max-retries   3
                         :retry-num     0
                         :status        status
                         :profile-id    profile-id
                         :scheduled-at  scheduled-at
                         :created-at    (ct/now)
                         :modified-at    (ct/now)})
    id))

(defn- get-row
  [id]
  (let [row (th/db-get :job {:id id}
                       :status :result :error :resource-id
                       :started-at :completed-at :modified-at :name)]
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
       (mapv #(jobs/decode-progress (:payload %)))))

(defn- count-job-events
  [id]
  (-> (th/db-exec! ["SELECT count(*) AS count FROM job_event WHERE job_id = ?" id])
      first
      :count))

(defn- mgmt
  [type params]
  ;; The legacy key id, pinned explicitly: the jobs methods declare a
  ;; predicate instead of a set now, and the shared fallback cannot pick
  ;; a first id from it.
  (th/management-command! (merge {::th/type       type
                                  ::rpc/auth-key-id :exporter}
                                 params)))

(defn- mk-profile
  ([] (mk-profile 1))
  ([i]
   ;; the created profile is inactive until the email is verified, and
   ;; a session can only be minted for an owner the login would accept
   (th/create-profile* i {:is-active true})))

(defn- get-session-row
  [id]
  (th/db-get :http-session-v2 {:id id}))

(defn- decode-token
  [token]
  (session/decode-token {::setup/props (get th/*system* :app.setup/props)} token))

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
    (t/is (= {:action :skip :status "new"} (:result out)))
    (t/is (= "new" (:status (get-row job-id))))))

(t/deftest claim-job-skips-terminal-and-cancelled-rows
  ;; the skip says the state of the row that won: a worker that carries
  ;; a stale payload sees cancelled/aborted, not a plain skip to retry on
  (let [completed-id (mk-job {:status "completed"})
        cancelled-id (mk-job {:status "cancelled"})
        aborted-id   (mk-job {:status "aborted"})]
    (t/is (= {:action :skip :status "completed"}
             (:result (mgmt :claim-job {:job-id completed-id
                                        :scheduled-at (ct/now)}))))
    (t/is (= {:action :skip :status "cancelled"}
             (:result (mgmt :claim-job {:job-id cancelled-id
                                        :scheduled-at (ct/now)}))))
    (t/is (= {:action :skip :status "aborted"}
             (:result (mgmt :claim-job {:job-id aborted-id
                                        :scheduled-at (ct/now)}))))
    (t/is (= "completed" (:status (get-row completed-id))))
    (t/is (= "cancelled" (:status (get-row cancelled-id))))
    (t/is (= "aborted" (:status (get-row aborted-id))))))

(t/deftest claim-job-skips-missing-row
  (t/is (= {:action :skip}
           (:result (mgmt :claim-job {:job-id      (uuid/next)
                                      :scheduled-at (ct/now)})))))

(t/deftest claim-job-validates-params
  (let [out (mgmt :claim-job {:job-id (uuid/next)})]
    (t/is (= :validation (th/ex-type (:error out))))))

(t/deftest jobs-methods-reject-a-key-they-do-not-declare
  (doseq [type [:claim-job :report-job-progress :complete-job :fail-job]]
    (let [out (mgmt type {::rpc/auth-key-id :nexus})]
      (t/is (= :authorization (th/ex-type (:error out))) (name type))
      (t/is (= :shared-key-not-allowed (th/ex-code (:error out))) (name type)))))

(t/deftest complete-job-rejects-a-key-it-does-not-declare-without-side-effects
  (let [job-id         (mk-job {})
        before         (get-row job-id)
        events-before  (count-job-events job-id)
        out            (mgmt :complete-job {::rpc/auth-key-id :nexus
                                            :job-id job-id
                                            :result {:x 1}})
        after          (get-row job-id)]
    (t/is (= :authorization (th/ex-type (:error out))))
    (t/is (= :shared-key-not-allowed (th/ex-code (:error out))))
    (t/is (= (:status before) (:status after)))
    (t/is (nil? (:completed-at after)))
    (t/is (= events-before (count-job-events job-id)))))

(t/deftest report-job-progress-appends-a-progress-event
  (let [job-id (mk-job {})
        _      (jobs/claim (make-cfg) job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        out    (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:stage :pages
                                                      :counters {:pages {:current 50 :total 100}}}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (t/is (= [{:stage :pages :counters {:pages {:current 50 :total 100}}}]
             (get-progresss job-id)))
    (t/testing "the job row has no progress column anymore"
      (t/is (not (contains? (get-row job-id) :progress))))))

(t/deftest report-job-progress-noop-on-terminal-row
  (let [job-id (mk-job {:status "completed"})
        out    (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:stage :pages
                                                      :counters {:pages {:current 100 :total 100}}}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip :status "completed"} (:result out)))
    (t/is (= [] (get-progresss job-id)))))

(t/deftest report-job-progress-persists-rapid-reports
  (let [job-id (mk-job {})
        _      (jobs/claim (make-cfg) job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
        _      (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:stage :pages
                                                      :counters {:pages {:current 3 :total 10}}}})
        _      (mgmt :report-job-progress {:job-id  job-id
                                           :progress {:stage :pages
                                                      :counters {:pages {:current 4 :total 10}}}})]
    (t/testing "the second immediate report is not throttled away"
      (t/is (= [{:stage :pages :counters {:pages {:current 3 :total 10}}}
                {:stage :pages :counters {:pages {:current 4 :total 10}}}]
               (get-progresss job-id))))))

(t/deftest report-job-progress-validates-the-payload
  (let [job-id (mk-job {})
        _      (jobs/claim (make-cfg) job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))]
    (t/testing "stage is mandatory"
      (t/is (= :validation (th/ex-type (:error (mgmt :report-job-progress
                                                     {:job-id job-id
                                                      :progress {:counters {:work {:current 1}}}}))))))
    (t/testing "no extra key is accepted"
      (t/is (= :validation (th/ex-type (:error (mgmt :report-job-progress
                                                     {:job-id job-id
                                                      :progress {:stage :work
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
    (jobs/heartbeat cfg :job-id job-id2 :progress {:stage :work})
    (jobs/complete cfg :job-id job-id1)
    (jobs/fail cfg job-id2 test-error)
    (t/is (not (contains? @@#'jobs/heartbeats job-id1)))
    (t/is (not (contains? @@#'jobs/progresses job-id2)))))

(t/deftest complete-and-fail-respect-first-terminal-wins
  (let [cfg        (make-cfg)
        running-id (mk-job {})
        orphan-id  (mk-job {:status "aborted"})
        _          (jobs/claim cfg running-id
                               (:scheduled-at (th/db-get :job {:id running-id} :id :scheduled-at)))]
    ;; a running job completes; a later complete/fail on the same row is a no-op
    (t/is (= 1 (jobs/complete cfg :job-id running-id :result {:value 1})))
    (t/is (= 0 (jobs/complete cfg :job-id running-id :result {:value 2})))
    (t/is (= 0 (jobs/fail cfg running-id test-error)))
    (t/is (= {:value 1} (:result (get-row running-id))))

    ;; an orphan (aborted by the dispatcher) is never overwritten
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
        (t/is (= {:action :skip :status "completed"} (:result out)))))
    (t/testing "fail on a terminal row reports skip"
      (let [out (mgmt :fail-job {:job-id done-id
                                 :error  {:type :internal
                                          :code "processing-error"
                                          :hint "bad image"}})]
        (t/is (nil? (:error out)))
        (t/is (= {:action :skip :status "completed"} (:result out)))))
    (t/testing "progress on a terminal row reports skip"
      (let [out (mgmt :report-job-progress {:job-id  done-id
                                            :progress {:stage :pages :counters {:pages {:current 4 :total 10}}}})]
        (t/is (nil? (:error out)))
        (t/is (= {:action :skip :status "completed"} (:result out))))))
  (let [cfg    (make-cfg)
        job-id (mk-job {})
        _      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))]
    (t/testing "live paths report run"
      (t/is (= {:action :run}
               (:result (mgmt :report-job-progress {:job-id  job-id
                                                    :progress {:stage :pages :counters {:pages {:current 4 :total 10}}}}))))
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

(t/deftest complete-job-on-a-finished-job-skips-instead-of-refusing-the-resource
  (let [cfg    (make-cfg)
        first  (uuid/next)
        second (uuid/next)]
    (doseq [id [first second]]
      (th/db-insert! :storage-object {:id id :backend "test"}))
    (let [job-id (mk-job {})]
      (jobs/claim cfg job-id (:scheduled-at (th/db-get :job {:id job-id} :id :scheduled-at)))
      (t/is (= {:action :run}
               (:result (mgmt :complete-job {:job-id job-id
                                             :result {:v 1}
                                             :resource-id first}))))

      (t/testing "the job is already finished, so there is no resource left to refuse"
        (let [out (mgmt :complete-job {:job-id job-id
                                       :result {:v 2}
                                       :resource-id second})]
          (t/is (nil? (:error out)))
          (t/is (= {:action :skip :status "completed"} (:result out)))))

      (t/testing "the row keeps the resource it was given and no event is added"
        (let [row (get-row job-id)]
          (t/is (= first (:resource-id row)))
          (t/is (= "completed" (:status row)))
          (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event
                                              WHERE job_id = ? AND kind = 'end'"
                                             job-id])))))))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CREATE-JOB-SESSION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest create-job-session-mints-a-session-of-the-owner
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        out     (mgmt :create-job-session {:job-id job-id})
        result  (:result out)]

    (t/is (nil? (:error out)))

    (t/testing "the answer is a real session of the job owner"
      (t/is (uuid? (:session-id result)))
      (t/is (string? (:session-token result)))
      (let [row (get-session-row (:session-id result))]
        (t/is (some? row))
        (t/is (= (:id profile) (:profile-id row)))))

    (t/testing "the token verifies and names the session and its owner"
      (let [claims (decode-token (:session-token result))]
        (t/is (some? claims))
        (t/is (= (:session-id result) (:sid claims)))
        (t/is (= (:id profile) (:uid claims)))
        ;; the short :exp of a job session: at most an hour by default,
        ;; never the absolute max-age of a login session
        (t/is (pos? (- (inst-ms (:exp claims)) (inst-ms (ct/now)))))
        (t/is (pos? (compare (ct/plus (ct/now) (ct/duration {:hours 1}))
                             (:exp claims))))))))

(t/deftest create-job-session-honours-the-configured-ttl
  (with-mocks [mock {:target 'app.config/get-job-session-ttl
                     :return (ct/duration {:minutes 5})}]
    (let [profile (mk-profile)
          job-id  (mk-job {:status     "running"
                           :profile-id (:id profile)})
          out     (mgmt :create-job-session {:job-id job-id})
          claims  (decode-token (get-in out [:result :session-token]))]
      (t/is (nil? (:error out)))
      (t/is (pos? (- (inst-ms (:exp claims)) (inst-ms (ct/now)))))
      (t/is (pos? (compare (ct/plus (ct/now) (ct/duration {:minutes 5}))
                           (:exp claims)))))))

(t/deftest create-job-session-mints-a-fresh-session-every-call
  ;; every call is self-contained: a worker retry never waits on a
  ;; session that a settle may already have closed
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        first   (mgmt :create-job-session {:job-id job-id})
        second  (mgmt :create-job-session {:job-id job-id})]
    (t/is (nil? (:error first)))
    (t/is (nil? (:error second)))
    (t/is (not= (get-in first [:result :session-id])
                (get-in second [:result :session-id])))))

(t/deftest create-job-session-refuses-a-job-that-is-not-running
  (let [profile (mk-profile)]
    (doseq [status ["new" "scheduled" "completed" "failed" "cancelled" "aborted"]]
      (let [job-id (mk-job {:status status :profile-id (:id profile)})
            out    (mgmt :create-job-session {:job-id job-id})]
        (t/is (some? (:error out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :invalid-job-state (th/ex-code (:error out))))
        (t/testing "and no session was minted"
          (t/is (zero? (count (th/db-exec! ["SELECT id FROM http_session_v2"])))))))))

(t/deftest create-job-session-refuses-an-unknown-job
  (let [out (mgmt :create-job-session {:job-id (uuid/next)})]
    (t/is (some? (:error out)))
    (t/is (= :not-found (th/ex-type (:error out))))
    (t/is (= :job-not-found (th/ex-code (:error out))))))

(t/deftest create-job-session-refuses-a-job-without-owner
  (let [job-id (mk-job {:status "running"})
        out    (mgmt :create-job-session {:job-id job-id})]
    (t/is (some? (:error out)))
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= :job-without-profile (th/ex-code (:error out))))))

(t/deftest create-job-session-refuses-an-owner-the-login-would-refuse
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})]

    (t/testing "a blocked owner is a restriction, not a fault"
      (th/db-update! :profile {:is-blocked true} {:id (:id profile)})
      (let [out (mgmt :create-job-session {:job-id job-id})]
        (t/is (= :restriction (th/ex-type (:error out))))
        (t/is (= :profile-blocked (th/ex-code (:error out))))))

    (t/testing "an inactive owner is refused like the login does"
      (th/db-update! :profile {:is-blocked false :is-active false} {:id (:id profile)})
      (let [out (mgmt :create-job-session {:job-id job-id})]
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :profile-not-active (th/ex-code (:error out)))))))

  (t/testing "a deleted owner does not exist"
    (let [profile (mk-profile 2)
          job-id  (mk-job {:status     "running"
                           :profile-id (:id profile)})]
      (th/db-update! :profile {:deleted-at (ct/now)} {:id (:id profile)})
      (let [out (mgmt :create-job-session {:job-id job-id})]
        (t/is (= :not-found (th/ex-type (:error out))))
        (t/is (= :profile-not-found (th/ex-code (:error out))))))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPLETE-JOB MULTIPART
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- artifact-tempfile
  "An on-disk artifact, as the multipart parser would leave one for the
  server: a path with the bytes of the produced export in it."
  [content]
  (let [path (fs/create-tempfile :dir tmp/default-tmp-dir
                                 :prefix "test-artifact-")]
    (with-open [out (io/output-stream path)]
      (io/write* out (.getBytes ^String content "UTF-8")))
    path))

(defn- multipart-complete
  [job-id path & {:as extra}]
  (-> (merge {:job-id  job-id
              :content {:path     path
                        :filename "result.zip"
                        :size     (fs/size path)
                        :mtype    "application/zip"}}
             extra)
      (assoc ::th/type        :complete-job
             ::rpc/auth-key-id :exporter)
      (th/with-multipart)
      (th/management-command!)))

(t/deftest claim-job-skips-a-job-of-another-tenant-like-a-missing-one
  (let [job-id (mk-job {:status "new" :tenant "other-tenant"})]
    ;; indistinguishable from a missing row: a bare skip, no status
    (t/is (= {:action :skip}
             (:result (mgmt :claim-job {:job-id      job-id
                                        :scheduled-at (ct/now)}))))))

(t/deftest claim-job-accepts-the-suffixed-key-of-its-tenant
  (let [scheduled-at (ct/now)
        job-id       (mk-job {:scheduled-at scheduled-at})
        tenant-key   (keyword (str "exporter:" (cf/get :tenant)))
        out          (mgmt :claim-job {:job-id       job-id
                                       :scheduled-at scheduled-at
                                       ::rpc/auth-key-id tenant-key})]
    (t/is (nil? (:error out)))
    (t/is (= :run (:action (:result out))))))

(t/deftest report-job-progress-skips-a-job-of-another-tenant-without-writing
  (let [job-id (mk-job {:status "running" :tenant "other-tenant"})
        out    (mgmt :report-job-progress {:job-id job-id
                                           :progress {:stage    :pages
                                                      :counters {:pages {:current 1
                                                                         :total 2}}}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip} (:result out)))
    (t/testing "no progress event was stored"
      (t/is (zero? (count-job-events job-id))))))

(t/deftest create-job-session-rejects-a-job-of-another-tenant-as-missing
  (let [job-id (mk-job {:status "running" :tenant "other-tenant"})
        out    (mgmt :create-job-session {:job-id job-id})]
    (t/is (= :not-found (th/ex-type (:error out))))
    (t/is (= :job-not-found (th/ex-code (:error out))))))

(t/deftest complete-job-skips-a-job-of-another-tenant-without-storing
  (let [profile    (mk-profile)
        job-id     (mk-job {:status     "running"
                            :profile-id (:id profile)
                            :tenant     "other-tenant"})
        path       (artifact-tempfile "penpot")
        session-id (uuid/next)
        out        (multipart-complete job-id path :session-id session-id)]
    (t/is (nil? (:error out)))
    ;; bare skip, like a missing row: no status to tell tenants apart
    (t/is (= {:action :skip} (:result out)))
    (t/testing "no artifact was stored and the row is untouched"
      (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))
      (t/is (= "running" (:status (get-row job-id)))))))

(t/deftest fail-job-skips-a-job-of-another-tenant
  (let [job-id (mk-job {:status "running" :tenant "other-tenant"})
        out    (mgmt :fail-job {:job-id job-id
                                :error  {:type :internal
                                         :code :boom
                                         :hint "boom"}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip} (:result out)))
    (t/is (= "running" (:status (get-row job-id))))))

(t/deftest complete-job-params-form-skips-a-job-of-another-tenant
  ;; The form without `:content` carries `:result`: a foreign tenant gets
  ;; a bare skip, so neither the write nor the row's state leaks.
  (let [job-id (mk-job {:status "running" :tenant "other-tenant"})
        out    (mgmt :complete-job {:job-id job-id
                                    :result {:x 1}})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :skip} (:result out)))
    (t/is (= "running" (:status (get-row job-id))))
    (t/is (nil? (:completed-at (get-row job-id))))))

(t/deftest complete-job-refuses-an-upload-outside-multipart
  ;; The upload map names a server path: only a request the multipart
  ;; parser flagged may carry one. A transit body is refused before any
  ;; store.
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")
        out     (mgmt :complete-job {:job-id  job-id
                                     :content {:path     path
                                               :filename "result.zip"
                                               :size     (fs/size path)
                                               :mtype    "application/zip"}})]
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= :params-validation (th/ex-code (:error out))))
    (t/testing "nothing was stored and the row is untouched"
      (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))
      (t/is (= "running" (:status (get-row job-id)))))))

(t/deftest complete-job-refuses-an-artifact-path-outside-the-upload-areas
  ;; Even over multipart, the parser only writes under its own temp
  ;; areas: anything else (e.g. an internal config file) is refused by
  ;; the same media check the other upload methods use.
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (fs/create-tempfile :prefix "outside-upload-")]
    (with-open [out (io/output-stream path)]
      (io/write* out (.getBytes "secret" "UTF-8")))
    (let [out (multipart-complete job-id path)]
      (t/is (= :validation (th/ex-type (:error out))))
      (t/is (= :params-validation (th/ex-code (:error out))))
      (t/testing "nothing was stored and the row is untouched"
        (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))
        (t/is (= "running" (:status (get-row job-id))))))))

(t/deftest close-job-session-leaves-a-session-of-another-profile-alone
  ;; The session id travels in the request: a shared-key holder must not
  ;; be able to delete another profile's login through a settle.
  (let [owner    (mk-profile 1)
        other    (mk-profile 2)
        job-id   (mk-job {:status     "running"
                          :profile-id (:id owner)})
        stranger (session/create-session (get th/*system* :app.http.session/manager)
                                         {:profile-id (:id other)})
        out      (multipart-complete job-id (artifact-tempfile "penpot")
                                     :session-id (:id stranger))]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (t/testing "the stranger session is still there"
      (t/is (some? (get-session-row (:id stranger)))))))

(t/deftest derive-exporter-key-matches-the-node-vector
  ;; The same golden value the exporter test pins: a change on either
  ;; side of the derivation fails here instead of in a deployment.
  (t/is (= "LXzydEEZEVAxbiNAzXV7mPSm6wYbrS7Omp4yMZ4gmOY"
           (keys/derive-exporter-key "test-secret-key" "default"))))

(t/deftest complete-job-multipart-stores-the-artifact-and-completes
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")
        out     (multipart-complete job-id path)
        row     (get-row job-id)
        rid     (:resource-id row)]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (t/is (= "completed" (:status row)))

    (t/testing "the artifact lives in the job-resource bucket, owned by the profile"
      (let [{:keys [metadata]} (first (th/db-exec! ["SELECT metadata
                                                     FROM storage_object WHERE id = ?" rid]))
            metadata (cond-> metadata (db/pgobject? metadata) db/decode-transit-pgobject)]
        (t/is (some? rid))
        (t/is (= "job-resource" (:bucket metadata)))
        (t/is (= (:id profile) (:profile-id metadata)))))

    (t/testing "the result names the artifact and how to download it"
      (let [result (:result row)]
        (t/is (= "result.zip" (:filename result)))
        (t/is (= "application/zip" (:mtype result)))
        (t/is (= (fs/size path) (:size result)))
        (t/is (str/ends-with? (:resource-uri result) (str "/assets/by-id/" rid)))))))

(t/deftest complete-job-multipart-ignores-legacy-text-fields
  ;; The part is the single source: an exporter that still sends the old
  ;; `filename`/`mtype` text fields completes fine, and the part wins.
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")
        out     (multipart-complete job-id path
                                    :filename "ignored.zip"
                                    :mtype    "image/gif")]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (let [result (:result (get-row job-id))]
      (t/is (= "result.zip" (:filename result)))
      (t/is (= "application/zip" (:mtype result))))))

(t/deftest complete-job-multipart-closes-the-render-session
  (let [profile    (mk-profile)
        job-id     (mk-job {:status     "running"
                            :profile-id (:id profile)})
        session-id (get-in (mgmt :create-job-session {:job-id job-id})
                           [:result :session-id])
        path       (artifact-tempfile "penpot")
        out        (multipart-complete job-id path :session-id session-id)]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (t/testing "the session is gone, deleted after the commit"
      (t/is (nil? (get-session-row session-id))))))

(t/deftest complete-job-multipart-refuses-a-mtype-outside-the-allowlist
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")
        out     (multipart-complete job-id path
                                    :content  {:path     path
                                               :filename "result.gif"
                                               :size     (fs/size path)
                                               :mtype    "image/gif"})]
    (t/is (some? (:error out)))
    (t/is (= :validation (th/ex-type (:error out))))
    (t/is (= :media-type-not-allowed (th/ex-code (:error out))))
    (t/testing "no artifact was stored and the row is untouched"
      (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))
      (t/is (= "running" (:status (get-row job-id)))))))

(t/deftest complete-job-multipart-refuses-a-part-without-filename
  ;; The part is the single source of the download name: a part without
  ;; one fails the upload schema before anything is stored.
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")
        out     (mgmt :complete-job {:job-id  job-id
                                     :content {:path     path
                                               :size     (fs/size path)
                                               :mtype    "application/zip"}})]
    (t/is (some? (:error out)))
    (t/is (= :validation (th/ex-type (:error out))))
    (t/testing "no artifact was stored and the row is untouched"
      (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))
      (t/is (= "running" (:status (get-row job-id)))))))

(t/deftest complete-job-multipart-refuses-a-result-over-the-cap
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "running"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")]
    (with-mocks [mock {:target 'app.config/get-exporter-max-result-size
                       :return 0}]
      (let [out (multipart-complete job-id path)]
        (t/is (some? (:error out)))
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :request-body-too-large (th/ex-code (:error out))))
        (t/testing "no artifact was stored and the row is untouched"
          (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))
          (t/is (= "running" (:status (get-row job-id)))))))))

(t/deftest complete-job-multipart-closes-the-session-on-a-lost-race
  (let [profile    (mk-profile)
        job-id     (mk-job {:status     "running"
                            :profile-id (:id profile)})
        session-id (get-in (mgmt :create-job-session {:job-id job-id})
                           [:result :session-id])
        path       (artifact-tempfile "penpot")]
    (with-mocks [mock {:target 'app.jobs/complete
                       :return 0}]
      (let [out (multipart-complete job-id path :session-id session-id)]
        (t/is (nil? (:error out)))
        (t/is (= :skip (-> out :result :action)))
        (t/testing "the session is gone even though the write lost"
          (t/is (nil? (get-session-row session-id))))
        (t/testing "the stored artifact was released, the row untouched"
          (t/is (nil? (:resource-id (get-row job-id))))
          (t/is (= "running" (:status (get-row job-id)))))))))

(t/deftest complete-job-multipart-of-a-terminal-job-skips-and-stores-nothing
  (let [profile (mk-profile)
        job-id  (mk-job {:status     "cancelled"
                         :profile-id (:id profile)})
        path    (artifact-tempfile "penpot")
        out     (multipart-complete job-id path)]
    (t/is (nil? (:error out)))
    ;; the bytes are not used: the completion lost the race with the
    ;; cancel, and no object is left for the storage GC to find
    (t/is (= {:action :skip :status "cancelled"} (:result out)))
    (t/is (zero? (count (th/db-exec! ["SELECT id FROM storage_object"]))))))

(t/deftest fail-job-closes-the-render-session
  (let [profile    (mk-profile)
        job-id     (mk-job {:status     "running"
                            :profile-id (:id profile)})
        session-id (get-in (mgmt :create-job-session {:job-id job-id})
                           [:result :session-id])
        out        (mgmt :fail-job {:job-id    job-id
                                    :error     {:type  :internal
                                                :code  "processing-error"
                                                :hint  "bad image"}
                                    :session-id session-id})]
    (t/is (nil? (:error out)))
    (t/is (= {:action :run} (:result out)))
    (t/is (= "failed" (:status (get-row job-id))))
    (t/is (nil? (get-session-row session-id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIRE CONTRACT: what the exporter actually receives and sends
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The claim carries the row's stored shapes (plain JSON: every id a
;; string, every enum its name). The exporter normalizes them to typed
;; values. These tests pin the backend half of that contract: the claim
;; returns stored shapes untouched, the dispatcher bytes are exact, and
;; the HTTP edges (auth, dispatch, body parsing) run production code.

(t/deftest claim-job-returns-decoded-typed-params
  ;; The row stores JSON (strings); the claim decodes with the row's
  ;; job-def, so the worker gets typed values (uuids, keywords) that
  ;; transit carries natively. A row whose job-def is gone answers
  ;; untouched (see the media-process claims above).
  (let [file-id      (uuid/next)
        object-id    (uuid/next)
        scheduled-at (ct/now)
        job-id       (mk-job {:name         "export-assets"
                              :scheduled-at scheduled-at
                              :params       {:exports [{:file-id   (str file-id)
                                                        :page-id   (str (uuid/next))
                                                        :object-id (str object-id)
                                                        :type      "png"
                                                        :name      "Board"
                                                        :suffix    ""
                                                        :scale     2}]
                                             :name    "the export"}})
        out          (mgmt :claim-job {:job-id       job-id
                                       :scheduled-at scheduled-at})
        params       (get-in out [:result :params])]
    (t/is (nil? (:error out)))
    (t/is (= "export-assets" (get-in out [:result :name])))
    (t/testing "ids travel as typed uuids"
      (t/is (= file-id (get-in params [:exports 0 :file-id])))
      (t/is (= object-id (get-in params [:exports 0 :object-id]))))
    (t/testing "enums travel as keywords"
      (t/is (= :png (get-in params [:exports 0 :type]))))))

(t/deftest claim-params-decode-to-typed-values-with-the-job-def
  ;; The row stores JSON (strings); the decoder the runner would use
  ;; turns them into the typed values the exporter validates against.
  ;; The claim path returns the stored shapes (see above); this pins
  ;; the decode half both sides rely on.
  (let [file-id   (uuid/next)
        object-id (uuid/next)
        stored    {:exports [{:file-id   (str file-id)
                              :page-id   (str (uuid/next))
                              :object-id (str object-id)
                              :type      "png"
                              :name      "Board"
                              :suffix    ""
                              :scale     2}]
                   :name    "the export"}
        decoded   (jobs/decode-params
                   (jobs/get-job-def (jobs/get-defs (make-cfg)) :export-assets)
                   stored)]
    (t/is (= file-id (get-in decoded [:exports 0 :file-id])))
    (t/is (= object-id (get-in decoded [:exports 0 :object-id])))
    (t/is (= :png (get-in decoded [:exports 0 :type])))
    (t/is (= "the export" (:name decoded)))))

(t/deftest shared-key-middleware-allows-the-exporter-key
  (let [secret   "test-secret"
        derived  (keys/derive-exporter-key secret "acme")
        handler  (#'mw/wrap-shared-key-auth (fn [request] request)
                                            {:exporter "test-key"}
                                            secret)
        allowed  (th/make-dummy-request :method :post
                                        :headers {"x-shared-key" "exporter test-key"})
        denied   (th/make-dummy-request :method :post
                                        :headers {"x-shared-key" "exporter wrong-key"})
        missing  (th/make-dummy-request :method :post)
        tenant   (th/make-dummy-request :method :post
                                        :headers {"x-shared-key" (str "exporter:acme " derived)})
        foreign  (th/make-dummy-request :method :post
                                        :headers {"x-shared-key" "exporter:acme wrong-key"})]
    (t/testing "the declared key reaches the handler with its id"
      (let [request (handler allowed)]
        (t/is (= :exporter (:app.http/auth-key-id request)))))
    (t/testing "a wrong key is a 403"
      (t/is (= 403 (:yetti.response/status (handler denied)))))
    (t/testing "a missing key is a 403"
      (t/is (= 403 (:yetti.response/status (handler missing)))))
    (t/testing "a per-tenant id the secret derives reaches the handler too"
      (let [request (handler tenant)]
        (t/is (= :exporter:acme (:app.http/auth-key-id request)))))
    (t/testing "a per-tenant id with a wrong key is a 403"
      (t/is (= 403 (:yetti.response/status (handler foreign)))))))

(t/deftest management-dispatch-routes-by-method-name
  (t/testing "the stub method runs with the multipart flag of the request"
    (let [handler  (rpc/make-rpc-handler
                    {:ping [{} (fn [data]
                                 (select-keys data [:app.rpc/auth-key-id
                                                    :app.rpc/is-multipart]))]})
          request  (-> (th/make-dummy-request
                        :method :post
                        :headers {"content-type" "multipart/form-data; boundary=test"})
                       (assoc :path-params {:method-name "ping"}
                              :params {}
                              :app.http/auth-key-id :exporter))
          response (handler request)]
      (t/is (= 200 (:yetti.response/status response)))
      (t/is (true? (:app.rpc/is-multipart (:yetti.response/body response))))
      (t/is (= :exporter (:app.rpc/auth-key-id (:yetti.response/body response))))))
  (t/testing "the real claim method resolves through the same dispatch"
    (let [scheduled-at (ct/now)
          job-id       (mk-job {:scheduled-at scheduled-at})
          handler      (rpc/make-rpc-handler
                        (get th/*system* :app.rpc/management-methods))
          request      (-> (th/make-dummy-request
                            :method :post
                            :headers {"content-type" "application/transit+json"})
                           (assoc :path-params {:method-name "claim-job"}
                                  :params {:job-id job-id :scheduled-at scheduled-at}
                                  :app.http/auth-key-id :exporter))
          response     (handler request)
          body         (:yetti.response/body response)]
      (t/is (= 200 (:yetti.response/status response)))
      (t/is (= :run (:action body)))
      (t/is (= "media-process" (:name body))))))

(t/deftest management-body-parses-transit-by-content-type
  (let [payload {:job-id       (uuid/next)
                 :scheduled-at (ct/inst "2026-10-07T09:00:00Z")}
        request (th/make-dummy-request
                 :method :post
                 :headers {"content-type" "application/transit+json"}
                 :body-bytes (transit/encode-str payload))]
    (t/testing "a transit body decodes to the params map"
      (t/is (= payload (#'mw/read-body-params
                        request nil "application/transit+json")))))
  (t/testing "a broken body raises malformed, not a 500"
    (let [request (th/make-dummy-request
                   :method :post
                   :headers {"content-type" "application/transit+json"}
                   :body-bytes "{nope")
          metrics (get th/*system* :app.metrics/metrics)]
      (try
        (#'mw/read-body-params request metrics "application/transit+json")
        (t/is false "should have raised")
        (catch clojure.lang.ExceptionInfo cause
          (t/is (= :validation (th/ex-type cause)))
          (t/is (= :malformed-json (th/ex-code cause))))))))

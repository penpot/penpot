;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.jobs-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [app.msgbus :as mbus]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)

(defn- test-fixture [next]
  (th/database-reset next))

(t/use-fixtures :each test-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JOB-DEF (plain handler + precompiled init-key)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn echo-handler
  "Plain handler function, importable and testable without integrant.
  Dual behavior: when invoked in-process (no job-id in cfg), heartbeat/progress
  are no-ops. When invoked via the runner (with job-id in cfg), they write to
  the job row. This allows the same handler to be tested both ways."
  [cfg params]
  (when (::jobs/job-id cfg)
    (jobs/heartbeat cfg)
    (jobs/heartbeat cfg :progress {:current 1 :stage "half"}))
  params)

(def schema:echo-params
  [:map
   [:text ::sm/text]
   [:object :keyword]
   [:deleted-at ::ct/inst]
   [:id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]])

(defmethod ig/init-key ::echo-job
  [_ cfg]
  {::jobs/name      :echo
   ::jobs/schema    schema:echo-params
   ::jobs/handler   (partial echo-handler cfg)
   ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:echo-params)})

(defn- get-job-defs
  []
  (-> {::jobs/defs {:echo (ig/ref ::echo-job)}
       ::echo-job  {::db/pool th/*pool*}}
      (ig/expand)
      (ig/init)
      (get ::jobs/defs)))

(defn- make-cfg
  [defs]
  {::jobs/defs   defs
   ::db/pool     th/*pool*
   ::mtx/metrics (get th/*system* :app.metrics/metrics)})

(defn- make-params
  []
  {:text      "hello"
   :object    :snapshot
   :deleted-at (ct/now)
   :id        (uuid/next)
   :file-id   (uuid/next)})

(defn- get-progresss
  "Progress events of a job, oldest first, with decoded payloads."
  [job-id]
  (->> (th/db-exec! ["SELECT payload FROM job_event
                      WHERE job_id = ? AND kind = 'progress'
                      ORDER BY created_at ASC, id ASC"
                     job-id])
       (mapv #(db/decode-json-pgobject (:payload %)))))

(defn- fake-msgbus
  "A minimal msgbus that records every publication on the atom."
  [messages]
  (reify mbus/IMsgBus
    (-sub [_ _ _] nil)
    (-pub [_ topic message]
      (swap! messages conj {:topic topic :message message})
      nil)
    (-purge [_ _] nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest submit-persists-optional-profile-and-resource-references
  (let [cfg          (make-cfg (get-job-defs))
        profile-id   (:id (th/create-profile* 1))
        resource-id  (uuid/next)]
    ;; the resource reference points to a real storage object: the column
    ;; is a foreign key kept for garbage collection
    (th/db-insert! :storage-object {:id resource-id :backend "test"})
    (let [internal-id  (jobs/submit cfg {::jobs/name   :echo
                                         ::jobs/params (make-params)})
          owned-id     (jobs/submit cfg {::jobs/name        :echo
                                         ::jobs/params      (make-params)
                                         ::jobs/profile-id  profile-id
                                         ::jobs/resource-id resource-id})
          internal-row (jobs/get-job cfg internal-id)
          owned-row    (jobs/get-job cfg owned-id)]

      (t/testing "profile_id stays nullable for internal jobs"
        (t/is (nil? (:profile-id internal-row)))
        (t/is (nil? (:resource-id internal-row))))

      (t/testing "an explicit profile is persisted as the owner of the job"
        (t/is (= profile-id (:profile-id owned-row))))

      (t/testing "the resource reference is a column, not part of params"
        (t/is (= resource-id (:resource-id owned-row)))
        (t/is (not (contains? (:params owned-row) :resource-id)))))))

(t/deftest submit-rejects-invalid-profile-and-resource-references
  (let [cfg (make-cfg (get-job-defs))]
    (t/testing "a non uuid profile-id is rejected before the insert"
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name       :echo
                                       ::jobs/params     (make-params)
                                       ::jobs/profile-id "not-a-uuid"}))))
    (t/testing "a non uuid resource-id is rejected before the insert"
      (t/is (thrown? Exception
                     (jobs/submit cfg {::jobs/name        :echo
                                       ::jobs/params      (make-params)
                                       ::jobs/resource-id "not-a-uuid"}))))
    (t/testing "no job row was created by the rejected submits"
      (t/is (= 0 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job"])))))))

(t/deftest progress-report-validates-its-payload
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "current is mandatory"
      (t/is (thrown? Exception
                     (jobs/heartbeat cfg :job-id job-id :progress {:total 10}))))

    (t/testing "unknown keys are rejected"
      (t/is (thrown? Exception
                     (jobs/heartbeat cfg :job-id job-id
                                     :progress {:current 1 :step 1}))))

    (t/testing "current must not be negative"
      (t/is (thrown-with-msg? Exception #"negative"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:current -1}))))

    (t/testing "total must be positive"
      (t/is (thrown-with-msg? Exception #"positive"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:current 0 :total 0}))))

    (t/testing "current must not be greater than total"
      (t/is (thrown-with-msg? Exception #"not lower than current"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:current 5 :total 4}))))

    (t/testing "stage is limited to 250 characters"
      (t/is (thrown-with-msg? Exception #"250"
                              (jobs/heartbeat cfg :job-id job-id
                                              :progress {:current 1
                                                         :stage (apply str (repeat 251 "x"))})))
      (t/is (= 2 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:current 1
                                            :stage (apply str (repeat 250 "x"))})))
      (t/is (= [{:current 1 :stage (apply str (repeat 250 "x"))}]
               (get-progresss job-id))))

    (t/testing "a rejected report never reaches the durable log"
      (t/is (= 1 (count (get-progresss job-id)))))))

(t/deftest progress-event-publishes-msgbus-for-profile-jobs
  (let [cfg        (make-cfg (get-job-defs))
        profile-id (:id (th/create-profile* 1))
        messages   (atom [])
        job-cfg    (assoc cfg ::mbus/msgbus (fake-msgbus messages))
        job-id     (jobs/submit job-cfg {::jobs/name       :echo
                                         ::jobs/params     (make-params)
                                         ::jobs/profile-id profile-id})]
    (t/is (pos? (jobs/heartbeat job-cfg :job-id job-id
                                :progress {:current 3 :total 7})))
    (t/testing "the event is published on the topic of the job profile"
      (t/is (= 1 (count @messages)))
      (let [{:keys [topic message]} (first @messages)]
        (t/is (= profile-id topic))
        (t/is (= :job-event (:type message)))
        (t/is (= job-id (:job-id message)))
        (t/is (= profile-id (:profile-id message)))
        (t/is (= "progress" (:kind message)))
        (t/is (= {:current 3 :total 7} (:payload message)))
        (t/is (some? (:event-id message)))
        (t/is (some? (:created-at message)))))))

(t/deftest progress-event-of-internal-job-is-not-published
  (let [cfg      (make-cfg (get-job-defs))
        messages (atom [])
        job-cfg  (assoc cfg ::mbus/msgbus (fake-msgbus messages))
        job-id   (jobs/submit job-cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
    (t/is (pos? (jobs/heartbeat job-cfg :job-id job-id :progress {:current 1})))
    (t/testing "the event is stored but nothing is published"
      (t/is (= 1 (count (get-progresss job-id))))
      (t/is (= [] @messages)))))

(t/deftest progress-event-of-profile-job-requires-msgbus
  (let [cfg        (make-cfg (get-job-defs))
        profile-id (:id (th/create-profile* 1))
        job-id     (jobs/submit cfg {::jobs/name       :echo
                                     ::jobs/params     (make-params)
                                     ::jobs/profile-id profile-id})]
    (t/testing "the forced route propagates the missing msgbus"
      (t/is (thrown-with-msg? Exception #"require ::mbus/msgbus"
                              (jobs/heartbeat cfg
                                              :job-id job-id
                                              :progress {:current 1}
                                              ::jobs/force? true)))
      (t/is (= [] (get-progresss job-id))
            "the event must not be stored without its notification"))

    (t/testing "the handler route logs the failure and keeps the job alive"
      (th/db-update! :job {:modified-at (ct/in-past {:minutes 5})} {:id job-id})
      (swap! jobs/heartbeats dissoc job-id)
      (t/is (= 1 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:current 1})))
      (t/is (= [] (get-progresss job-id))))))

(t/deftest submit-validates-params-with-job-schema
  (let [cfg (make-cfg (get-job-defs))]
    (t/is (thrown-with-msg? Exception #"check error"
                            (jobs/submit cfg {::jobs/name   :echo
                                              ::jobs/params {:text "hello"
                                                             :object :snapshot
                                                             :deleted-at (ct/now)
                                                             :id (uuid/next)
                                                             :file-id "not-an-uuid"}}))))

  (t/testing "missing job definition raises a clear error"
    (let [cfg (make-cfg (get-job-defs))]
      (t/is (thrown-with-msg? Exception #"no job definition"
                              (jobs/submit cfg {::jobs/name   :unknown
                                                ::jobs/params {}}))))))

(t/deftest submit-persists-row-with-json-params
  (let [cfg   (make-cfg (get-job-defs))
        params (make-params)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/queue  :test
                                 ::jobs/delay  1000
                                 ::jobs/priority 200
                                 ::jobs/label  "test-label"
                                 ::jobs/max-retries 5})
        row    (jobs/get-job cfg job-id)]

    (t/is (uuid? job-id))
    (t/is (= "echo" (:name row)))
    (t/is (= (str (cf/get :tenant) ":" "test") (:queue row)))
    (t/is (= "test-label" (:label row)))
    (t/is (= 200 (:priority row)))
    (t/is (= 5 (:max-retries row)))
    (t/is (= "new" (:status row)))

    (t/testing "scheduled_at respects the submitted delay"
      (let [delay (ct/duration {:seconds 1})]
        (t/is (pos? (- (inst-ms (:scheduled-at row)) (inst-ms (ct/now))))
              (str "scheduled-at should be in the future: "
                   (pr-str (:scheduled-at row))))))

    (t/testing "the row exposes params and no legacy columns"
      (t/is (some? (:params row)))
      (t/is (not (contains? row :props)))
      (t/is (not (contains? row :progress)))
      (t/is (not (contains? row :target))))

    (t/testing "params are stored as plain JSON (no transit tags) and decoded"
      (let [params (:params row)]
        (t/is (map? params))
        (t/is (nil? (some-> (th/db-exec-one! ["SELECT params::text FROM job WHERE id = ?"
                                              job-id])
                            :params
                            (str/index-of "~#"))))

        (t/testing "round-trip: decoded values have proper clojure types"
          (let [decoded (jobs/decode-params (jobs/get-job-def (get cfg ::jobs/defs) :echo)
                                            (:params row))]
            (t/is (keyword? (:object decoded)))
            (t/is (= :snapshot (:object decoded)))
            (t/is (uuid? (:id decoded)))
            (t/is (uuid? (:file-id decoded)))
            (t/is (ct/inst? (:deleted-at decoded)))))))))

(t/deftest submit-dedupe-replaces-not-due-new-rows
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-label"
        id1    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true
                                 ::jobs/delay  10000})
        id2    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true
                                 ::jobs/delay  10000})]

    (t/is (uuid? id1))
    (t/is (uuid? id2))
    (t/is (not= id1 id2))

    (t/testing "the first new row (not due yet) is removed"
      (t/is (nil? (jobs/get-job cfg id1)))

      (t/testing "and only the new row remains"
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                            WHERE label = ?" label]))))))))

(t/deftest submit-dedupe-keeps-due-and-running-rows
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-active-label"
        id1    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label})
        _      (th/db-update! :job {:status "running"} {:id id1})
        id2    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true})]

    (t/is (some? (jobs/get-job cfg id1)))
    (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                        WHERE label = ?" label])))))

  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        label  "dedupe-due-label"
        id1    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label})
        _      (th/db-update! :job {:scheduled-at (ct/minus (ct/now)
                                                            (ct/duration {:seconds 5}))}
                              {:id id1})
        id2    (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params
                                 ::jobs/label  label
                                 ::jobs/dedupe true})]

    (t/is (some? (jobs/get-job cfg id1)))
    (t/is (= 2 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job
                                        WHERE label = ?" label]))))))

(t/deftest submit-dedupe-rolls-back-on-insert-failure
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        opts   {::jobs/name   :echo
                ::jobs/params params
                ::jobs/dedupe true
                ::jobs/label  "atomic-label"}
        kept   (jobs/submit cfg opts)
        calls  (atom 0)
        orig   @#'db/exec-one!]
    ;; fault the INSERT (2nd statement): the DELETE must roll back too
    (alter-var-root #'db/exec-one!
                    (constantly (fn [& args]
                                  (when (= 2 (swap! calls inc))
                                    (throw (ex-info "boom" {})))
                                  (apply orig args))))
    (try
      (t/is (thrown? Exception (jobs/submit cfg opts)))
      (t/testing "the original row survives, no duplicate left behind"
        (t/is (some? (jobs/get-job cfg kept)))
        (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job WHERE label = ?" "atomic-label"])))))
      (finally
        (alter-var-root #'db/exec-one! (constantly orig))))))

(t/deftest submit-dedupe-atomic-on-autocommit-conn
  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        opts   {::jobs/name   :echo
                ::jobs/params params
                ::jobs/dedupe true
                ::jobs/label  "atomic-autocommit-label"}
        kept   (jobs/submit cfg opts)
        calls  (atom 0)
        orig   @#'db/exec-one!]
    ;; a raw connection outside any transaction: DELETE+INSERT must
    ;; still share one transaction opened on it
    (with-open [conn (db/open th/*pool*)]
      (alter-var-root #'db/exec-one!
                      (constantly (fn [& args]
                                    (when (= 2 (swap! calls inc))
                                      (throw (ex-info "boom" {})))
                                    (apply orig args))))
      (try
        (t/is (thrown? Exception
                       (jobs/submit (assoc cfg ::db/conn conn) opts)))
        (t/testing "the original row survives, no duplicate left behind"
          (t/is (some? (jobs/get-job cfg kept)))
          (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job WHERE label = ?" "atomic-autocommit-label"])))))
        (finally
          (alter-var-root #'db/exec-one! (constantly orig)))))))

(t/deftest submit-requires-metrics-on-bare-connectable
  (let [defs (get-job-defs)
        prev @@#'jobs/defs-registry]
    (try
      (reset! @#'jobs/defs-registry defs)
      (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                              (jobs/submit th/*pool* {::jobs/name   :echo
                                                      ::jobs/params (make-params)})))
      (finally
        (reset! @#'jobs/defs-registry prev)))))

(t/deftest plain-handler-is-testable-without-integrant
  (let [params (make-params)]
    (t/is (= params (echo-handler {} params))))

  (let [cfg    (make-cfg (get-job-defs))
        params (make-params)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params})]
    (t/testing "handler receives cfg with job-id context for heartbeats"
      (t/is (= params (echo-handler (assoc cfg ::jobs/job-id job-id) params))))))

(t/deftest terminal-writers-require-metrics
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                            (jobs/complete (dissoc cfg ::mtx/metrics) job-id)))
    (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                            (jobs/fail (dissoc cfg ::mtx/metrics) job-id {:code "x"})))
    (t/is (thrown-with-msg? Exception #"missing ::mtx/metrics"
                            (jobs/cancel (dissoc cfg ::mtx/metrics) job-id)))))

(t/deftest submit-strips-rollback-testing-flag-from-params
  (let [cfg    (make-cfg (get-job-defs))
        params (assoc (make-params) :rollback? true)
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params params})]
    (t/testing "durable rows never carry the in-process escape hatch"
      (t/is (nil? (:rollback? (:params (jobs/get-job cfg job-id))))))))

(t/deftest heartbeat-respects-throttle
  (let [cfg   (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "first heartbeat writes"
      ;; Backdate modified-at: submit and heartbeat can land in the same
      ;; millisecond, which would make a strict > assertion flaky.
      (th/db-update! :job {:modified-at (ct/in-past {:seconds 5})}
                     {:id job-id})
      (jobs/heartbeat cfg :job-id job-id)
      (let [row (jobs/get-job cfg job-id)]
        (t/is (> (inst-ms (:modified-at row)) (inst-ms (:created-at row))))))

    (t/testing "immediate second heartbeat does not write (throttled)"
      (let [row1 (jobs/get-job cfg job-id)
            _    (jobs/heartbeat cfg :job-id job-id)
            row2 (jobs/get-job cfg job-id)]
        (t/is (= (inst-ms (:modified-at row1))
                 (inst-ms (:modified-at row2))))))))

(t/deftest heartbeat-skips-terminal-states
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/testing "terminal states are never touched by heartbeat"
      (th/db-update! :job {:status "completed"
                           :modified-at (ct/in-past {:days 10})}
                     {:id job-id})
      (swap! @#'jobs/heartbeats dissoc job-id)
      (let [before (jobs/get-job cfg job-id)]
        (jobs/heartbeat cfg :job-id job-id)
        (t/is (= (inst-ms (:modified-at before))
                 (inst-ms (:modified-at (jobs/get-job cfg job-id)))))))))

(t/deftest progress-events-respect-throttle-and-skip-terminal-states
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "first progress report appends a progress event"
      (t/is (= 2 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:current 1 :total 10})))
      (t/is (= [{:current 1 :total 10}] (get-progresss job-id))))

    (t/testing "immediate second progress report is throttled"
      (t/is (= 0 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:current 2 :total 10})))
      (t/is (= [{:current 1 :total 10}] (get-progresss job-id)))

      (t/testing "after the throttle window elapses it appends again"
        (swap! @#'jobs/progresses
               (fn [m]
                 (update-in m [job-id]
                            #(ct/minus %
                                       (ct/duration {:seconds 2})))))
        (jobs/heartbeat cfg :job-id job-id :progress {:current 3 :total 10})
        (t/is (= [{:current 1 :total 10}
                  {:current 3 :total 10}]
                 (get-progresss job-id))))))

  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/testing "terminal states never get a progress event"
      (th/db-update! :job {:status "completed"} {:id job-id})
      (swap! @#'jobs/progresses dissoc job-id)
      (t/is (= 0 (jobs/heartbeat cfg :job-id job-id
                                 :progress {:current 9})))
      (t/is (= [] (get-progresss job-id))))))

(t/deftest throttle-prune-removes-stale-entries-keeps-fresh
  "When the throttle map exceeds prune-threshold, stale entries (older than
  prune-window) are removed and fresh entries are kept."
  (let [cfg        (make-cfg (get-job-defs))
        job-id-1   (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
        job-id-2   (jobs/submit cfg {::jobs/name   :echo
                                     ::jobs/params (make-params)})
        now        (ct/now)
        stale-time (ct/minus now (ct/duration {:hours 2}))  ;; older than 1h window
        fresh-time (ct/minus now (ct/duration {:minutes 30}))] ;; within 1h window

    ;; Fill the heartbeats map past the threshold with one stale and one fresh entry
    (with-redefs [jobs/prune-threshold 0]  ;; force prune on next call
      (reset! @#'jobs/heartbeats
              {job-id-1 stale-time
               job-id-2 fresh-time})

      ;; Trigger a heartbeat for a new job (should trigger prune)
      (let [job-id-3 (jobs/submit cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
        (jobs/heartbeat cfg :job-id job-id-3)

        ;; After prune: stale entry (job-id-1) should be gone, fresh (job-id-2) should remain
        (let [state @jobs/heartbeats]
          (t/is (not (contains? state job-id-1)) "stale entry removed")
          (t/is (contains? state job-id-2) "fresh entry kept")
          (t/is (contains? state job-id-3) "new entry added"))))))

(t/deftest cancel-skips-running-and-terminal-jobs
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "pending job can be cancelled"
      (t/is (= 1 (jobs/cancel cfg job-id)))
      (t/is (= "cancelled" (:status (jobs/get-job cfg job-id))))

      (t/testing "already cancelled job is not affected again"
        (t/is (zero? (jobs/cancel cfg job-id))))))

  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (t/testing "running job cannot be cancelled"
      (th/db-update! :job {:status "running"} {:id job-id})
      (t/is (zero? (jobs/cancel cfg job-id)))
      (t/is (= "running" (:status (jobs/get-job cfg job-id)))))))

(t/deftest cancel-affects-scheduled-and-retry-jobs
  (let [cfg (make-cfg (get-job-defs))]
    (doseq [status ["scheduled" "retry"]]
      (t/testing (str status " job can be cancelled")
        (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
          (th/db-update! :job {:status status} {:id job-id})
          (t/is (= 1 (jobs/cancel cfg job-id)))
          (t/is (= "cancelled" (:status (jobs/get-job cfg job-id)))))))))

(t/deftest get-user-status-maps-internal-statuses
  (t/are [status expected] (= expected (jobs/get-user-status status))
    "new"       "pending"
    "scheduled" "pending"
    "retry"     "pending"
    :new        "pending"
    "running"   "running"
    "completed" "completed"
    "failed"    "failed"
    "cancelled" "failed"))

(t/deftest job-defs-registry-validates-definitions
  (t/testing "valid job-def map passes the assert"
    (t/is (some? (get-job-defs))))

  (t/testing "registry name mismatch is detected"
    (t/is (thrown? Exception
                   (ig/init {::jobs/defs {::echo-job
                                          {::jobs/name      :other
                                           ::jobs/schema    schema:echo-params
                                           ::jobs/handler   echo-handler
                                           ::jobs/decoder   (sm/decoder schema:echo-params sm/json-transformer)
                                           ::jobs/validator (sm/validator schema:echo-params)}}}))))

  (t/testing "missing decoder/validator is detected"
    (t/is (thrown? Exception
                   (ig/init {::jobs/defs {::echo-job
                                          {::jobs/name    :echo
                                           ::jobs/schema  schema:echo-params
                                           ::jobs/handler echo-handler}}})))))

(t/deftest generic-schema-round-trip-preserves-type-sensitive-fields
  "For each registered job-def, verify that type-sensitive fields (uuids, insts)
  survive the JSON round-trip (db/json → decode). This catches the F2 class of
  bug where uuid types are lost during the transit→JSON switch."
  (let [defs (get-job-defs)]
    (t/is (pos? (count defs)) "should have at least one job-def")

    ;; Test the echo job-def which we know exists in the test registry
    (t/testing "echo job-def preserves types through JSON round-trip"
      (let [echo-def (get defs :echo)
            _        (t/is (some? echo-def) "echo job-def should exist")
            ;; Create a representative params map with type-sensitive fields
            sample-params {:text "test"
                           :object :snapshot
                           :deleted-at (ct/now)
                           :id (uuid/random)
                           :file-id (uuid/random)}

            ;; Round-trip through JSON (simulates the job table storage)
            ;; db/json returns a PGobject, which is what decode-params expects
            pg-obj  (db/json sample-params)
            decoded (jobs/decode-params echo-def pg-obj)]

        ;; Verify type-sensitive fields survived
        (t/is (uuid? (:id decoded)) "id should be uuid after decode")
        (t/is (uuid? (:file-id decoded)) "file-id should be uuid after decode")
        (t/is (inst? (:deleted-at decoded)) "deleted-at should be inst after decode")))))

(t/deftest invoke-falls-back-to-global-registry
  "Verify that invoke! uses the global registry fallback when ::defs is not
  on the cfg, consistent with submit!."
  ;; Rebind the private registry atom instead of overwriting global state:
  ;; it auto-restores on exit, so no later test can observe it. Note
  ;; get-job-defs itself populates the global via ig/init (established
  ;; pattern, also used by the sibling tests); the snapshot below is
  ;; taken after that, so it pins exactly this test's rebinding.
  (let [defs   (get-job-defs)
        before @@#'jobs/defs-registry]
    (with-redefs [jobs/defs-registry (atom defs)]
      ;; cfg WITHOUT ::jobs/defs to exercise the fallback; the echo
      ;; job-def is known to exist in the rebound registry
      (let [cfg    {::db/pool th/*pool*}
            result (jobs/invoke (assoc cfg ::jobs/name :echo
                                       ::jobs/params (make-params)))]
        ;; Should not throw; should find the job-def via the fallback.
        ;; The result is the params map (echo-handler returns params).
        (t/is (some? result))))
    (t/testing "global registry untouched"
      (t/is (= before @@#'jobs/defs-registry)))))

(t/deftest heartbeat-and-progress-noop-when-job-id-nil
  "When *job-id* is nil (in-process invoke! without a job row),
  heartbeat! and progress! are no-ops — they do not write to the
  database or update the throttle atoms."
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]

    (t/testing "heartbeat! is a no-op when *job-id* is nil"
      (let [row-before (jobs/get-job cfg job-id)]
        (binding [jobs/*job-id* nil]
          (jobs/heartbeat cfg))
        (let [row-after (jobs/get-job cfg job-id)]
          (t/is (= (inst-ms (:modified-at row-before))
                   (inst-ms (:modified-at row-after)))
                "modified-at should not change"))))

    (t/testing "progress reporting is a no-op when *job-id* is nil"
      (binding [jobs/*job-id* nil]
        (t/is (nil? (jobs/heartbeat cfg :progress {:current 1}))))
      (t/is (= [] (get-progresss job-id))
            "no progress event should be stored"))))

(t/deftest progress-noop-on-terminal-states
  "A progress report never stores an event when the job is already in a
  terminal state (completed, failed, cancelled): the row is locked and
  checked before the insert, so a report can never race a terminal
  transition."
  (let [cfg (make-cfg (get-job-defs))]
    (doseq [status ["completed" "failed" "cancelled"]]
      (t/testing (str "progress is a no-op on " status " status")
        (let [job-id (jobs/submit cfg {::jobs/name   :echo
                                       ::jobs/params (make-params)})]
          (th/db-update! :job {:status status} {:id job-id})
          ;; reset the throttle so should-write? would allow the write
          (swap! jobs/progresses dissoc job-id)
          (binding [jobs/*job-id* job-id]
            (t/is (= 0 (jobs/heartbeat cfg :progress {:current 1}))))
          (t/is (= [] (get-progresss job-id))))))))

(t/deftest defs-halt-clears-module-registry
  (let [prev @@#'jobs/defs-registry]
    ;; init populates the module registry
    (get-job-defs)
    (t/is (contains? @@#'jobs/defs-registry :echo))
    (try
      (ig/halt-key! ::jobs/defs nil)
      (t/is (= {} @@#'jobs/defs-registry))
      (finally
        (reset! @#'jobs/defs-registry prev)))))

(t/deftest heartbeat-bypasses-caller-transaction
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    ;; backdate so the beat is strictly greater (no same-millis flake)
    (th/db-update! :job {:modified-at (ct/in-past {:minutes 5})} {:id job-id})
    ;; beat inside a transaction that is rolled back: the beat must
    ;; still be visible (it went through the pool, not the tx)
    (db/tx-run! (assoc cfg ::db/rollback true)
                (fn [{:keys [::db/conn]}]
                  (jobs/heartbeat (assoc cfg ::db/conn conn) :job-id job-id)))
    (let [row (jobs/get-job cfg job-id)]
      (t/is (> (inst-ms (:modified-at row))
               (inst-ms (ct/in-past {:minutes 5})))))))

(t/deftest progress-bypasses-caller-transaction
  (let [cfg    (make-cfg (get-job-defs))
        job-id (jobs/submit cfg {::jobs/name   :echo
                                 ::jobs/params (make-params)})]
    (db/tx-run! (assoc cfg ::db/rollback true)
                (fn [{:keys [::db/conn]}]
                  (jobs/heartbeat (assoc cfg ::db/conn conn)
                                  :job-id job-id
                                  :progress {:current 1})))
    (t/is (= [{:current 1}] (get-progresss job-id)))))


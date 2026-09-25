;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.migrations-test
  (:require
   [app.db :as db]
   [app.migrations :as migrations]
   [app.util.migrations :as mg]
   [backend-tests.helpers :as th]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(t/deftest migrations-registry-satisfies-steps-spec
  (t/testing "every entry is a {:name :fn} step (guards against stray forms)"
    (t/is (s/valid? ::mg/steps migrations/migrations)
          (s/explain-str ::mg/steps migrations/migrations))))

(defn- table-columns
  [table]
  (->> (th/db-exec! ["SELECT column_name FROM information_schema.columns
                      WHERE table_schema='public' AND table_name=?" table])
       (map :column-name)
       set))

(defn- table-indexes
  [table]
  (->> (th/db-exec! ["SELECT indexname FROM pg_indexes
                      WHERE schemaname='public' AND tablename=?" table])
       (map :indexname)
       set))

(defn- table-foreign-keys
  [table]
  (->> (th/db-exec! ["SELECT conname, confdeltype FROM pg_constraint
                      WHERE contype='f' AND conrelid = ?::regclass" table])
       (map (fn [{:keys [conname confdeltype]}]
              [conname confdeltype]))
       set))

(defn- deferrable-foreign-keys
  "Names of the deferrable foreign keys of a table."
  [table]
  (->> (th/db-exec! ["SELECT conname FROM pg_constraint
                      WHERE contype='f' AND condeferrable AND conrelid = ?::regclass" table])
       (map :conname)
       set))

(t/deftest job-table-exists-with-expected-columns
  (t/is (= #{; dispatch/lifecycle columns
             "id" "name" "queue" "label" "priority" "scheduled_at"
             "retry_num" "max_retries" "status" "created_at" "modified_at"
             "started_at" "completed_at" "params"
             ; optional ledger columns
             "profile_id" "error" "result"
             "resource_id" "expires_at"}
           (table-columns "job"))))

(t/deftest job-table-has-no-target-nor-progress-columns
  (t/testing "target and job.progress are gone: progress lives in job_event"
    (t/is (empty? (filter #{"target" "progress"} (table-columns "job"))))))

(t/deftest job-event-table-exists-with-expected-columns
  (t/is (= #{"id" "job_id" "kind" "payload" "created_at"}
           (table-columns "job_event"))))

(t/deftest job-event-kind-check-rejects-unknown-kinds
  (let [job-id (th/mk-uuid "job-event-check")]
    (th/db-insert! :job {:id    job-id
                         :name  "test"
                         :queue "test:default"})
    (try
      (t/testing "the four lifecycle kinds are accepted"
        (doseq [kind ["start" "progress" "retry" "end"]]
          (t/is (some? (th/db-insert! :job-event {:job-id job-id
                                                  :kind   kind})))))
      (t/testing "any other kind is rejected"
        (t/is (thrown? Exception
                       (th/db-insert! :job-event {:job-id job-id
                                                  :kind   "unknown"}))))
      (finally
        (th/db-delete! :job {:id job-id})))))

(t/deftest job-event-index-serves-the-history-read
  (let [indexdef (:indexdef (th/db-exec-one! ["SELECT indexdef FROM pg_indexes
                                              WHERE schemaname = 'public'
                                                AND tablename = 'job_event'
                                                AND indexname = 'job_event__job_kind_created_idx'"]))]
    (t/is (str/includes? indexdef "(job_id, kind, created_at DESC, id DESC)"))))

(t/deftest job-event-cascades-on-job-deletion
  (let [job-id (th/mk-uuid "job-event-cascade")]
    (th/db-insert! :job {:id job-id :name "test" :queue "test:default"})
    (th/db-insert! :job-event {:job-id job-id :kind "progress"})
    (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event WHERE job_id = ?" job-id]))))
    (th/db-delete! :job {:id job-id})
    (t/is (= 0 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event WHERE job_id = ?" job-id]))))))

(t/deftest job-event-foreign-key-is-deferrable
  (t/testing "every new job reference is DEFERRABLE"
    (t/is (contains? (deferrable-foreign-keys "job_event") "job_event_job_id_fkey"))))

(t/deftest job-table-has-expected-indexes
  (t/is (contains? (table-indexes "job") "job__dispatcher__idx"))
  (t/is (contains? (table-indexes "job") "job__orphan__idx"))
  (t/is (contains? (table-indexes "job") "job__profile__idx")))

(t/deftest job-dispatcher-index-serves-ordering
  (let [indexdef (:indexdef (th/db-exec-one! ["SELECT indexdef FROM pg_indexes
                                              WHERE schemaname = 'public'
                                                AND tablename = 'job'
                                                AND indexname = 'job__dispatcher__idx'"]))]
    (t/testing "leading columns carry the sweep ORDER BY, status only in the predicate"
      (t/is (str/includes? indexdef "(priority DESC, scheduled_at)"))
      (t/is (str/includes? indexdef "WHERE"))
      (t/is (not (str/includes? indexdef "(status, scheduled_at)"))))))

(t/deftest job-table-has-sweep-path-indexes
  (t/testing "dispatcher reschedule of lost scheduled rows"
    (t/is (contains? (table-indexes "job") "job__scheduled__idx")))
  (t/testing "jobs-GC expiration scan"
    (t/is (contains? (table-indexes "job") "job__expires__idx")))
  (t/testing "jobs-GC retention scan"
    (t/is (contains? (table-indexes "job") "job__retention__idx"))))

(t/deftest job-table-has-expected-foreign-keys
  (t/testing "profile_id keeps profile deletion explicit (no action)"
    (t/is (contains? (table-foreign-keys "job")
                     ["job_profile_id_fkey" "a"])))

  (t/testing "resource_id is set to null when the storage object is deleted"
    (t/is (contains? (table-foreign-keys "job")
                     ["job_resource_id_fkey" "n"]))))

(t/deftest job-status-check-constraint-rejects-unknown-statuses
  (t/is (thrown? Exception
                 (th/db-insert! :job {:id   (th/mk-uuid "job-check")
                                      :name "test"
                                      :queue "test:default"
                                      :status "unknown"}))))

(t/deftest job-status-check-constraint-accepts-known-statuses
  (doseq [status ["new" "scheduled" "running" "retry"
                  "completed" "failed" "cancelled"]]
    (th/db-insert! :job {:id (th/mk-uuid "job-status" status)
                         :name "test"
                         :queue "test:default"
                         :status status})
    (let [{:keys [status priority retry-num max-retries params scheduled-at
                  created-at modified-at]}
          (-> (th/db-get :job {:id (th/mk-uuid "job-status" status)} :status
                         :priority :retry-num :max-retries :params :scheduled-at
                         :created-at :modified-at)
              (update :params db/decode-json-pgobject))]
      (t/is (= status status))
      (t/is (= 100 priority))
      (t/is (= 0 retry-num))
      (t/is (= 3 max-retries))
      (t/is (= {} params))
      (t/is (some? scheduled-at))
      (t/is (some? created-at))
      (t/is (some? modified-at))
      (th/db-delete! :job {:id (th/mk-uuid "job-status" status)}))))

(t/deftest job-has-no-modified-at-trigger
  (let [triggers (->> (th/db-exec! ["SELECT tgname FROM pg_trigger
                                     WHERE tgrelid = ?::regclass
                                       AND NOT tgisinternal" "job"])
                      (map :tgname)
                      set)]
    (t/is (empty? triggers))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.migrations-test
  (:require
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

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

(t/deftest job-table-exists-with-expected-columns
  (t/is (= #{; dispatch/lifecycle columns
             "id" "name" "queue" "label" "priority" "scheduled_at"
             "retry_num" "max_retries" "status" "created_at" "modified_at"
             "started_at" "completed_at" "props"
             ; optional ledger columns
             "profile_id" "target" "progress" "error" "result"
             "resource_id" "expires_at"}
           (table-columns "job"))))

(t/deftest job-table-has-expected-indexes
  (t/is (contains? (table-indexes "job") "job__dispatcher__idx"))
  (t/is (contains? (table-indexes "job") "job__orphan__idx"))
  (t/is (contains? (table-indexes "job") "job__profile__idx")))

(t/deftest job-table-has-expected-foreign-keys
  (t/testing "profile_id cascades on profile deletion"
    (t/is (contains? (table-foreign-keys "job")
                     ["job_profile_id_fkey" "c"])))

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
    (let [{:keys [status priority retry-num max-retries props scheduled-at
                  created-at modified-at]}
          (-> (th/db-get :job {:id (th/mk-uuid "job-status" status)} :status
                         :priority :retry-num :max-retries :props :scheduled-at
                         :created-at :modified-at)
              (update :props db/decode-json-pgobject))]
      (t/is (= status status))
      (t/is (= 100 priority))
      (t/is (= 0 retry-num))
      (t/is (= 3 max-retries))
      (t/is (= {} props))
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

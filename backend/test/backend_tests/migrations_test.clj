;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.migrations-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.migrations :as migrations]
   [app.util.migrations :as mg]
   [backend-tests.helpers :as th]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

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

(defn- foreign-key-flags
  "The deferral and delete-action flags of one foreign key. `condeferred` is
  true only for `INITIALLY DEFERRED`."
  [table name]
  (-> (th/db-exec! ["SELECT condeferrable, condeferred, confdeltype, convalidated
                       FROM pg_constraint
                      WHERE contype = 'f'
                        AND conname = ?
                        AND conrelid = ?::regclass"
                    name table])
      first))

(t/deftest job-table-exists-with-expected-columns
  (t/is (= #{; dispatch/lifecycle columns
             "id" "name" "tenant" "queue" "label" "priority" "scheduled_at"
             "retry_num" "max_retries" "status" "created_at" "modified_at"
             "started_at" "completed_at" "params"
             ; optional ledger columns
             "profile_id" "error" "result"
             "resource_id" "expires_at"}
           (table-columns "job"))))

(t/deftest job-tenant-column-is-not-nullable
  (t/testing "every job belongs to an instance, so the column is required"
    (t/is (= "NO"
             (:is-nullable (th/db-exec-one! ["SELECT is_nullable
                                               FROM information_schema.columns
                                              WHERE table_name = ?
                                                AND column_name = ?"
                                             "job" "tenant"]))))))

(t/deftest job-queue-stores-the-bare-queue-name
  (let [job-id (th/mk-uuid "job-bare-queue")]
    (th/db-insert! :job {:id     job-id
                         :name   "test"
                         :tenant "acme"
                         :queue  "webhooks"})
    (try
      (t/is (= "acme" (:tenant (th/db-get :job {:id job-id}))))
      (t/is (= "webhooks" (:queue (th/db-get :job {:id job-id}))))
      (t/testing "the tenant is not smuggled inside the queue"
        (t/is (not (str/includes? (:queue (th/db-get :job {:id job-id})) "acme"))))
      (finally
        (th/db-force-delete :job {:id job-id})))))

(t/deftest job-table-has-no-target-nor-progress-columns
  (t/testing "target and job.progress are gone: progress lives in job_event"
    (t/is (empty? (filter #{"target" "progress"} (table-columns "job"))))))

(t/deftest job-event-table-exists-with-expected-columns
  (t/is (= #{"id" "job_id" "kind" "payload" "created_at"}
           (table-columns "job_event"))))

(t/deftest job-event-kind-check-rejects-unknown-kinds
  (let [job-id (th/mk-uuid "job-event-check")]
    (th/db-insert! :job {:id     job-id
                         :name   "test"
                         :tenant "acme"
                         :queue  "default"})
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
        (th/db-force-delete :job {:id job-id})))))

(t/deftest job-event-index-serves-the-history-read
  (let [indexdef (:indexdef (th/db-exec-one! ["SELECT indexdef FROM pg_indexes
                                              WHERE schemaname = 'public'
                                                AND tablename = 'job_event'
                                                AND indexname = 'job_event__job_kind_created_idx'"]))]
    (t/is (str/includes? indexdef "(job_id, kind, created_at DESC, id DESC)"))))

(t/deftest job-event-cascades-on-job-deletion
  (let [job-id (th/mk-uuid "job-event-cascade")]
    (th/db-insert! :job {:id job-id :name "test" :tenant "acme" :queue "default"})
    (th/db-insert! :job-event {:job-id job-id :kind "progress"})
    (t/is (= 1 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event WHERE job_id = ?" job-id]))))
    (th/db-force-delete :job {:id job-id})
    (t/is (= 0 (:cnt (th/db-exec-one! ["SELECT count(*) AS cnt FROM job_event WHERE job_id = ?" job-id]))))))

(t/deftest job-event-foreign-key-is-deferrable
  (t/testing "every new job reference is DEFERRABLE"
    (t/is (contains? (deferrable-foreign-keys "job_event") "job_event_job_id_fkey"))))

(t/deftest job-table-has-expected-indexes
  (t/is (contains? (table-indexes "job") "job__dispatcher__idx"))
  (t/is (contains? (table-indexes "job") "job__orphan__idx"))
  (t/is (contains? (table-indexes "job") "job__profile__idx")))

(defn- indexdef
  [index-name]
  (:indexdef (th/db-exec-one! ["SELECT indexdef FROM pg_indexes
                                WHERE schemaname = 'public'
                                  AND tablename = 'job'
                                  AND indexname = ?" index-name])))

(t/deftest job-dispatcher-index-serves-ordering
  (let [def (indexdef "job__dispatcher__idx")]
    (t/testing "tenant leads because the filter is an equality it can answer"
      (t/is (str/includes? def "(tenant, priority DESC, scheduled_at)")))
    (t/testing "status only in the predicate, it is not a key column"
      (t/is (str/includes? def "WHERE"))
      (t/is (not (str/includes? def "(status, scheduled_at)"))))
    (t/testing "queue does not lead: the dispatcher claims every queue of the tenant"
      (t/is (not (str/includes? def "(tenant, queue"))))))

(t/deftest job-sweep-indexes-are-tenant-scoped
  (t/testing "orphan sweep"
    (t/is (str/includes? (indexdef "job__orphan__idx") "(tenant, modified_at)")))
  (t/testing "reschedule of lost scheduled rows"
    (t/is (str/includes? (indexdef "job__scheduled__idx") "(tenant, scheduled_at)")))
  (t/testing "cron no-overlap check and submit dedupe"
    (t/is (str/includes? (indexdef "job__name_label__idx") "(tenant, name, queue, label)"))))

(t/deftest job-table-has-sweep-path-indexes
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
                 (th/db-insert! :job {:id     (th/mk-uuid "job-check")
                                      :name   "test"
                                      :tenant "acme"
                                      :queue  "default"
                                      :status "unknown"}))))

(t/deftest job-status-check-constraint-accepts-known-statuses
  (doseq [status ["new" "scheduled" "running" "retry"
                  "completed" "failed" "cancelled" "aborted"]]
    (th/db-insert! :job {:id (th/mk-uuid "job-status" status)
                         :name "test"
                         :tenant "acme"
                         :queue "default"
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
      (th/db-force-delete :job {:id (th/mk-uuid "job-status" status)}))))

(t/deftest job-has-only-the-deletion-protection-trigger
  (let [triggers (->> (th/db-exec! ["SELECT tgname FROM pg_trigger
                                     WHERE tgrelid = ?::regclass
                                       AND NOT tgisinternal" "job"])
                      (map :tgname)
                      set)]
    (t/testing "no modified-at trigger: the application updates it"
      (t/is (not (contains? triggers "job__modified_at__tgr"))))
    (t/testing "deletion protection is the only trigger on job"
      (t/is (= #{"deletion_protection__tgr"} triggers)))))

;; --- File data storage reference (migration 0159)

(def ^:private file-data-migration
  (mg/resource "app/migrations/sql/0159-add-file-data-storage-object-id.sql"))

(defn- apply-file-data-migration!
  []
  (db/tx-run! (::db/pool th/*system*)
              (fn [{:keys [::db/conn]}]
                (file-data-migration conn))))

(t/deftest file-data-storage-object-reference-is-a-deferrable-no-action-fk
  (t/testing "the pointer column exists"
    (t/is (contains? (table-columns "file_data") "storage_object_id")))
  (t/testing "the index that serves the FK check and the GC scan exists"
    (t/is (contains? (table-indexes "file_data")
                     "file_data__storage_object_id__idx")))
  (t/testing "the reference does not cascade"
    (t/is (contains? (table-foreign-keys "file_data")
                     ["file_data_storage_object_id_fkey" "a"])))
  (t/testing "the reference is DEFERRABLE but checked immediately"
    ;; The storage GC catches the violation inside the chunk transaction. An
    ;; `INITIALLY DEFERRED` check fires at commit, outside the catch, and the
    ;; sweep would never move past the blocked chunk.
    (t/is (= {:condeferrable true
              :condeferred false
              :confdeltype "a"
              :convalidated true}
             (foreign-key-flags "file_data"
                                "file_data_storage_object_id_fkey")))))

(t/deftest deleting-a-referenced-storage-object-fails
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        sobject (:id (th/db-insert! :storage-object {:backend "fs"}))
        data-id (uuid/next)]

    (th/db-insert! :file-data
                   {:file-id (:id file)
                    :id data-id
                    :type "snapshot"
                    :backend "storage"
                    :storage-object-id sobject})

    (t/testing "a live reference refuses the physical delete"
      (let [cause (try
                    (th/db-exec! ["DELETE FROM storage_object WHERE id = ?" sobject])
                    nil
                    (catch java.sql.SQLException cause cause))]
        (t/is (some? cause))
        (t/is (= "23503" (.getSQLState cause)))))

    (th/db-delete! :file-data {:file-id (:id file) :id data-id})

    (t/testing "without the reference the delete succeeds"
      (th/db-exec! ["DELETE FROM storage_object WHERE id = ?" sobject])
      (t/is (nil? (th/db-get :storage-object {:id sobject}))))))

(t/deftest file-data-migration-backfills-and-is-idempotent
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        sobject (:id (th/db-insert! :storage-object {:backend "fs"}))
        data-id (uuid/next)]

    (th/db-insert! :file-data
                   {:file-id (:id file)
                    :id data-id
                    :type "snapshot"
                    :backend "storage"
                    :metadata (db/json {"storage-ref-id" (str sobject)})})

    (t/testing "the backfill copies the metadata pointer into the column"
      (apply-file-data-migration!)
      (t/is (= sobject (:storage-object-id (th/db-get :file-data
                                                      {:file-id (:id file)
                                                       :id data-id})))))

    (t/testing "the backfill removes the dead metadata pointer"
      (let [row      (th/db-get :file-data {:file-id (:id file) :id data-id})
            metadata (fdata/decode-metadata (:metadata row))]
        (t/is (nil? (:storage-ref-id metadata)))))

    (t/testing "a second application changes nothing"
      (let [before (th/db-get :file-data {:file-id (:id file) :id data-id})]
        (apply-file-data-migration!)
        (t/is (= before (th/db-get :file-data {:file-id (:id file)
                                               :id data-id})))))))

(t/deftest file-data-migration-guards-corrupt-and-orphan-pointers
  (let [profile    (th/create-profile* 1)
        file       (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)})
        sobject    (:id (th/db-insert! :storage-object {:backend "fs"}))
        valid-id   (uuid/next)
        corrupt-id (uuid/next)
        orphan-id  (uuid/next)
        other-id   (uuid/next)]

    (th/db-insert! :file-data
                   {:file-id (:id file) :id valid-id :type "snapshot" :backend "storage"
                    :metadata (db/json {"storage-ref-id" (str sobject)})})
    (th/db-insert! :file-data
                   {:file-id (:id file) :id corrupt-id :type "snapshot" :backend "storage"
                    :metadata (db/json {"storage-ref-id" "not-a-uuid"})})
    (th/db-insert! :file-data
                   {:file-id (:id file) :id orphan-id :type "snapshot" :backend "storage"
                    :metadata (db/json {"storage-ref-id" (str (uuid/next))})})
    (th/db-insert! :file-data
                   {:file-id (:id file) :id other-id :type "snapshot" :backend "db"
                    :metadata (db/json {"storage-ref-id" (str sobject)})})

    (t/testing "the migration applies without aborting"
      (t/is (true? (apply-file-data-migration!))))

    (t/testing "a valid pointer is backfilled"
      (t/is (= sobject (:storage-object-id (th/db-get :file-data
                                                      {:file-id (:id file)
                                                       :id valid-id})))))

    (t/testing "a non-uuid value stays NULL"
      (t/is (nil? (:storage-object-id (th/db-get :file-data
                                                 {:file-id (:id file)
                                                  :id corrupt-id})))))

    (t/testing "a pointer to a missing object stays NULL"
      (t/is (nil? (:storage-object-id (th/db-get :file-data
                                                 {:file-id (:id file)
                                                  :id orphan-id})))))

    (t/testing "rows of other backends are not touched"
      (t/is (nil? (:storage-object-id (th/db-get :file-data
                                                 {:file-id (:id file)
                                                  :id other-id})))))))

(t/deftest file-data-migration-creates-a-missing-index
  ;; In the test database the index already exists, so the creation branch is
  ;; only reachable by dropping it first. It is the branch a fresh deployment
  ;; runs.
  (th/db-exec! ["DROP INDEX file_data__storage_object_id__idx"])
  (t/is (not (contains? (table-indexes "file_data")
                        "file_data__storage_object_id__idx")))

  (t/is (true? (apply-file-data-migration!)))

  (t/is (contains? (table-indexes "file_data")
                   "file_data__storage_object_id__idx")))

(t/deftest file-data-migration-rebuilds-a-wrong-foreign-key
  ;; The migration owns the definition. A constraint created by hand with
  ;; CASCADE would delete the referencing rows instead of refusing the delete,
  ;; so it is replaced with the NO ACTION one.
  (th/db-exec! ["ALTER TABLE file_data
                   DROP CONSTRAINT file_data_storage_object_id_fkey"])
  (try
    (th/db-exec! ["ALTER TABLE file_data
                     ADD CONSTRAINT file_data_storage_object_id_fkey
                     FOREIGN KEY (storage_object_id) REFERENCES storage_object(id)
                     ON DELETE CASCADE DEFERRABLE"])
    (t/is (true? (apply-file-data-migration!)))
    (t/is (= "a" (:confdeltype
                  (foreign-key-flags "file_data"
                                     "file_data_storage_object_id_fkey"))))
    (finally
      (apply-file-data-migration!))))

(t/deftest file-data-migration-rebuilds-a-non-deferrable-foreign-key
  ;; Without DEFERRABLE the constraint breaks `SET CONSTRAINTS ALL DEFERRED`
  ;; and `database-reset`; the migration recreates it deferrable.
  (th/db-exec! ["ALTER TABLE file_data
                   DROP CONSTRAINT file_data_storage_object_id_fkey"])
  (try
    (th/db-exec! ["ALTER TABLE file_data
                     ADD CONSTRAINT file_data_storage_object_id_fkey
                     FOREIGN KEY (storage_object_id) REFERENCES storage_object(id)
                     ON DELETE NO ACTION"])
    (t/is (true? (apply-file-data-migration!)))
    (t/is (true? (:condeferrable
                  (foreign-key-flags "file_data"
                                     "file_data_storage_object_id_fkey"))))
    (finally
      (apply-file-data-migration!))))

(t/deftest file-data-migration-rebuilds-a-deferred-foreign-key
  ;; An `INITIALLY DEFERRED` check fires at commit, outside the
  ;; `storage-gc-deleted` chunk transaction, so the sweep would never move past
  ;; a blocked chunk. The migration must leave the check immediate.
  (th/db-exec! ["ALTER TABLE file_data
                   DROP CONSTRAINT file_data_storage_object_id_fkey"])
  (try
    (th/db-exec! ["ALTER TABLE file_data
                     ADD CONSTRAINT file_data_storage_object_id_fkey
                     FOREIGN KEY (storage_object_id) REFERENCES storage_object(id)
                     ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED"])
    (t/is (true? (apply-file-data-migration!)))
    (t/is (false? (:condeferred
                   (foreign-key-flags "file_data"
                                      "file_data_storage_object_id_fkey"))))
    (finally
      (apply-file-data-migration!))))

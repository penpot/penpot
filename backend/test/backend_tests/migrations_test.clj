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
             ; the jobs that resolved their key in the database
             "exclusive" "dedupe"
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
  (t/is (contains? (table-indexes "job") "job__profile__idx"))
  (t/is (contains? (table-indexes "job") "job__live_exclusive__uniq"))
  (t/is (contains? (table-indexes "job") "job__new_dedupe__uniq")))

(t/deftest audit-log-has-a-consumer-cursor
  (t/testing "the position columns and their covering index exist"
    (t/is (contains? (table-columns "audit_log") "seq"))
    (t/is (contains? (table-columns "audit_log") "xid"))
    (t/is (contains? (table-indexes "audit_log") "audit_log__xid__seq__idx")))
  (t/testing "one row per consumer, with the position and its clock"
    (t/is (= #{"consumer" "xid" "seq" "updated_at"}
             (table-columns "audit_log_consumer_cursor")))))

(t/deftest the-migration-keeps-the-legacy-archive-mark-and-indexes
  ;; The legacy mark and the indexes that served the old archiver stay in
  ;; place: the old archiver may still be running during the rollout, and a
  ;; later release removes them. The pending queue's `(created_at, id)`
  ;; index is not built either.
  (th/db-exec! ["ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS archived_at timestamptz"])
  (th/db-exec! ["DROP INDEX IF EXISTS audit_log__created_at__idx"])
  (th/db-exec! ["CREATE INDEX audit_log__created_at__idx
                     ON audit_log (created_at) WHERE archived_at IS NULL"])
  (th/db-exec! ["CREATE INDEX IF NOT EXISTS audit_log__archived_at__idx
                     ON audit_log (archived_at) WHERE archived_at IS NOT NULL"])
  (th/db-exec! ["CREATE INDEX IF NOT EXISTS audit_log_id_archived_at_idx
                     ON audit_log (id, archived_at)"])
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")]
    (t/is (true? (apply! th/*pool*))))
  (t/is (contains? (table-columns "audit_log") "archived_at"))
  (t/is (contains? (table-indexes "audit_log") "audit_log__created_at__idx"))
  (t/is (contains? (table-indexes "audit_log") "audit_log__archived_at__idx"))
  (t/is (contains? (table-indexes "audit_log") "audit_log_id_archived_at_idx"))
  (t/is (str/includes?
         (:indexdef (th/db-exec-one!
                     ["SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'audit_log'
                          AND indexname = 'audit_log__created_at__idx'"]))
         "WHERE (archived_at IS NULL)"))
  (t/is (not (some #(str/includes? % "(created_at, id)")
                   (map :indexdef
                        (th/db-exec! ["SELECT indexdef FROM pg_indexes
                                        WHERE schemaname = 'public'
                                          AND tablename = 'audit_log'"]))))))

(t/deftest the-migration-drops-the-old-queue-table-on-a-pre-release-db
  ;; The release never creates `audit_log_pending`: the pending queue never
  ;; shipped. A pre-release database that ran its step does have it, and it
  ;; may still have rows; the table has to go, or the leftover rows would
  ;; block the new GC when it deletes the rows they reference. Recreate the
  ;; table and run the migration again to pin it.
  (th/db-exec! ["CREATE TABLE IF NOT EXISTS audit_log_pending (
                   audit_log_id uuid NOT NULL REFERENCES audit_log(id) DEFERRABLE,
                   consumer smallint NOT NULL CHECK (consumer BETWEEN 1 AND 20),
                   PRIMARY KEY (consumer, audit_log_id))"])
  (t/is (contains? (table-columns "audit_log_pending") "consumer"))
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")]
    (t/is (true? (apply! th/*pool*))))
  (t/is (empty? (table-columns "audit_log_pending"))))

(t/deftest the-migration-does-not-seed-consumer-cursors
  ;; The migration cannot know which consumers are enabled, so the cursor
  ;; table starts empty; `ensure-cursor` creates the row at (0, 0) on a
  ;; consumer's first pass, which is what makes the first deploy replay the
  ;; unarchived buffer.
  (th/db-exec! ["DELETE FROM audit_log_consumer_cursor WHERE consumer IN (1, 2)"])
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")]
    (t/is (true? (apply! th/*pool*))))
  (let [rows (th/db-exec! ["SELECT consumer
                              FROM audit_log_consumer_cursor
                             WHERE consumer IN (1, 2)"])]
    (t/is (empty? rows))))

(t/deftest audit-log-rows-get-seq-and-xid-by-default
  ;; The previous version keeps inserting rows during the rollout, so the
  ;; database must fill both columns, and the new rows must sort above the
  ;; backfilled history (the reserved low range).
  (let [id (uuid/next)]
    (try
      (th/db-exec! ["INSERT INTO audit_log (id, name, type, source, profile_id,
                                            ip_addr, props, context, tracked_at, created_at)
                     VALUES (?, 'create-project', 'action', 'backend', ?,
                             '127.0.0.1', ?, ?, now(), now())"
                    id uuid/zero (db/tjson {}) (db/tjson {})])
      (let [row (th/db-exec-one! ["SELECT seq, xid::text AS xid
                                     FROM audit_log WHERE id = ?" id])]
        (t/is (some? (:seq row)))
        (t/is (>= (:seq row) 1000000))
        (t/is (not= "0" (:xid row))))
      (finally
        (th/db-exec! ["DELETE FROM audit_log WHERE id = ?" id])))))

(t/deftest audit-log-source-is-not-null
  ;; `0064` added the column nullable and left `SET NOT NULL` commented;
  ;; `0159` fills what is left and closes it. A NULL source would be deleted
  ;; by neither the GC (`NOT LIKE 'telemetry:%'`) nor the telemetry task
  ;; (`LIKE 'telemetry:%'`), so the row would stay for ever.
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")]
    (t/is (true? (apply! th/*pool*))))
  (t/is (= "NO" (:is-nullable
                 (th/db-exec-one!
                  ["SELECT is_nullable FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND table_name = 'audit_log'
                       AND column_name = 'source'"]))))
  (t/is (thrown? java.sql.SQLException
                 (th/db-exec!
                  ["INSERT INTO audit_log (id, name, type, source, profile_id,
                                           ip_addr, props, context, tracked_at, created_at)
                    VALUES (?, 'create-project', 'action', NULL, ?,
                            '127.0.0.1', ?, ?, now(), now())"
                   (uuid/next) uuid/zero (db/tjson {}) (db/tjson {})]))))

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
  ;; The test database already ran 0159 in its old shape: re-apply it so the
  ;; lost-sweep index exists with the current definition.
  (th/db-exec! ["DROP INDEX IF EXISTS job__scheduled__idx"])
  ;; An early 0154 built the cron probe index; re-create it so the drop
  ;; below proves the migration removes an index that already exists.
  (th/db-exec! ["CREATE INDEX IF NOT EXISTS job__name_label__idx
                     ON job (tenant, name, queue, label)
                  WHERE status IN ('new', 'scheduled', 'running', 'retry')"])
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")]
    (t/is (true? (apply! th/*pool*))))
  (t/testing "orphan sweep"
    (t/is (str/includes? (indexdef "job__orphan__idx") "(tenant, modified_at)")))
  (t/testing "lost sweep: it filters by the hand-off time"
    (t/is (str/includes? (indexdef "job__scheduled__idx") "(tenant, modified_at)"))
    (t/is (not (str/includes? (indexdef "job__scheduled__idx") "(tenant, scheduled_at)"))))
  (t/testing "the cron no-overlap probe index is gone"
    (t/is (nil? (indexdef "job__name_label__idx")))))

(t/deftest job-new-dedupe-index-admits-one-not-started-row-per-key
  ;; The dedupe of a submit is the index, not a delete before the insert:
  ;; it is unique, it covers the whole key, and it only holds the rows
  ;; that asked for the option and have not started yet.
  (let [def (indexdef "job__new_dedupe__uniq")]
    (t/is (str/includes? def "CREATE UNIQUE INDEX"))
    (t/is (str/includes? def "(tenant, name, queue, label)"))
    (t/is (str/includes? def "dedupe"))
    (t/testing "only the not-started status is covered"
      (t/is (str/includes? def "'new'"))
      (doseq [status ["scheduled" "running" "retry"]]
        (t/is (not (str/includes? def (str "'" status "'")))
              (str "the " status " status leaves the index"))))))

(t/deftest consumer-generalization-migration-is-idempotent
  ;; The out-of-band runbook pre-applies the whole migration (columns,
  ;; CONCURRENTLY indexes, backfill), so the migration must find the schema
  ;; and do nothing when it runs afterwards.
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")]
    (t/is (true? (apply! th/*pool*)))
    (t/is (contains? (table-columns "job") "exclusive"))
    (t/is (contains? (table-columns "job") "dedupe"))
    (t/is (contains? (table-indexes "job") "job__live_exclusive__uniq"))
    (t/is (contains? (table-indexes "job") "job__new_dedupe__uniq"))
    (t/is (contains? (table-columns "audit_log") "seq"))
    (t/is (contains? (table-columns "audit_log") "xid"))
    (t/is (contains? (table-indexes "audit_log") "audit_log__xid__seq__idx"))
    (t/is (contains? (table-columns "audit_log_consumer_cursor") "consumer"))))

(defn- sequence-last-value
  []
  (:last-value (th/db-exec-one! ["SELECT last_value FROM audit_log_seq_seq"])))

(defn- insert-audit-row
  "Insert one row with the columns the previous version filled, so the
  database gives it `seq` and `xid` from their defaults."
  []
  (let [id (uuid/next)]
    (th/db-exec! ["INSERT INTO audit_log (id, name, type, source, profile_id,
                                          ip_addr, props, context, tracked_at, created_at)
                   VALUES (?, 'create-project', 'action', 'backend', ?,
                           '127.0.0.1', ?, ?, now(), now())"
                  id uuid/zero (db/tjson {}) (db/tjson {})])
    id))

(t/deftest the-sequence-reserve-is-only-applied-to-a-fresh-sequence
  ;; A re-run must not move a sequence that already left its initial value:
  ;; on the first application it starts at 1 and the migration reserves the
  ;; low range, so the history gets 1..N and the new rows start above it.
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")
        before (sequence-last-value)]
    (try
      (th/db-exec! ["ALTER SEQUENCE audit_log_seq_seq RESTART WITH 1"])
      (t/is (true? (apply! th/*pool*)))
      (t/is (>= (sequence-last-value) 1000000))
      (finally
        (th/db-exec! ["SELECT setval('audit_log_seq_seq', ?::bigint, false)" before])))))

(t/deftest the-sequence-reserve-is-not-moved-on-a-rerun
  ;; Inserting a row advances the sequence; deleting it drops `count(*)`
  ;; again. The old unconditional setval lowered the sequence in that case.
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")
        id (insert-audit-row)]
    (try
      (let [before (sequence-last-value)]
        (th/db-exec! ["DELETE FROM audit_log WHERE id = ?" id])
        (t/is (true? (apply! th/*pool*)))
        (t/is (= before (sequence-last-value)))
        (let [max-seq (:max (th/db-exec-one! ["SELECT max(seq) AS max FROM audit_log"]))
              next    (:next (th/db-exec-one! ["SELECT nextval('audit_log_seq_seq') AS next"]))]
          (t/is (> next (or max-seq 0)))))
      (finally
        (th/db-exec! ["DELETE FROM audit_log WHERE id = ?" id])))))

(t/deftest the-sequence-reserve-is-not-applied-to-a-used-once-sequence
  ;; A sequence that was called once has last_value = 1 with is_called = true:
  ;; it is not intact, so the reserve must leave it alone.
  (let [apply! (mg/resource "app/migrations/sql/0159-consumer-generalization.sql")
        before (sequence-last-value)]
    (try
      (th/db-exec! ["SELECT setval('audit_log_seq_seq', 1, true)"])
      (t/is (true? (apply! th/*pool*)))
      (t/is (= 1 (sequence-last-value)))
      (finally
        (th/db-exec! ["SELECT setval('audit_log_seq_seq', ?::bigint, false)" before])))))

(t/deftest job-live-exclusive-index-admits-one-live-row-per-key
  ;; The exclusion of the live jobs is the index, not a check that races
  ;; an insert: it is unique, it covers the whole key, and it only
  ;; holds the rows that asked for the exclusion.
  (let [def (indexdef "job__live_exclusive__uniq")]
    (t/is (str/includes? def "CREATE UNIQUE INDEX"))
    (t/is (str/includes? def "(tenant, name, queue, label)"))
    (t/is (str/includes? def "exclusive"))
    (doseq [status ["new" "scheduled" "running" "retry"]]
      (t/is (str/includes? def (str "'" status "'"))
            (str "the live set covers the " status " status")))))

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

    (t/testing "the backfill removes the migrated metadata pointer"
      (let [row      (th/db-get :file-data {:file-id (:id file) :id data-id})
            metadata (fdata/decode-metadata (:metadata row))]
        (t/is (not (contains? metadata :storage-ref-id)))))

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
        orphan-soid (uuid/next)
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
                    :metadata (db/json {"storage-ref-id" (str orphan-soid)})})
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
      (let [row      (th/db-get :file-data {:file-id (:id file) :id corrupt-id})
            metadata (fdata/decode-metadata (:metadata row))]
        (t/is (nil? (:storage-object-id row)))
        (t/is (= "not-a-uuid" (:storage-ref-id metadata)))))

    (t/testing "a pointer to a missing object stays NULL"
      (let [row      (th/db-get :file-data {:file-id (:id file) :id orphan-id})
            metadata (fdata/decode-metadata (:metadata row))]
        (t/is (nil? (:storage-object-id row)))
        (t/is (= (str orphan-soid) (:storage-ref-id metadata)))))

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

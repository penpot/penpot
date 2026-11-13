;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.migrations-partition-file-change-test
  "Covers the data copy of 0158-partition-file-change: the rows of the old
  table move to the partitioned one with their columns in place, the `kind`
  comes from the label (1 snapshot, 2 revision), and the legacy table keeps
  the rows."

  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.java.io :as io]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private sql:old-schema
  "CREATE TABLE file (id uuid PRIMARY KEY);
   CREATE TABLE profile (id uuid PRIMARY KEY);

   CREATE TABLE file_change (
     file_id       uuid NOT NULL,
     id            uuid NOT NULL DEFAULT gen_random_uuid(),
     created_at    timestamptz NOT NULL DEFAULT now(),
     session_id    uuid,
     revn          bigint NOT NULL DEFAULT 0,
     data          bytea,
     changes       bytea,
     profile_id    uuid,
     features      text[],
     label         text,
     version       integer,
     created_by    text NOT NULL DEFAULT 'system',
     updated_at    timestamptz DEFAULT now(),
     deleted_at    timestamptz,
     migrations    text[],
     locked_by     uuid,
     data_ref_id   uuid,
     data_backend  text,
     CONSTRAINT file_change_pkey PRIMARY KEY (id),
     CONSTRAINT file_change_file_id_fkey FOREIGN KEY (file_id)
       REFERENCES file(id) DEFERRABLE,
     CONSTRAINT file_change_locked_by_fkey FOREIGN KEY (locked_by)
       REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE,
     CONSTRAINT file_change_profile_id_fkey FOREIGN KEY (profile_id)
       REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE);

   CREATE INDEX file_change__data_ref_id__idx
     ON file_change (data_ref_id);
   CREATE INDEX file_change__deleted_at__idx
     ON file_change (deleted_at) WHERE deleted_at IS NOT NULL;
   CREATE INDEX file_change__file_id__revn__idx
     ON file_change (file_id, revn);
   CREATE INDEX file_change__locked_by__idx
     ON file_change (locked_by) WHERE locked_by IS NOT NULL;
   CREATE INDEX file_change__profile_id__idx
     ON file_change (profile_id) WHERE profile_id IS NOT NULL;
   CREATE INDEX file_change__system_snapshots__idx
     ON file_change (file_id, created_at)
    WHERE created_by = 'system' AND deleted_at IS NULL;
   CREATE INDEX file_change__user_snapshots__idx
     ON file_change (file_id)
    WHERE label IS NOT NULL AND created_by = 'user' AND deleted_at IS NULL;")

(defn- run-migration!
  [conn]
  (let [sql (slurp (io/resource "app/migrations/sql/0158-partition-file-change.sql"))]
    (db/exec! conn [sql])))

(t/deftest copies-the-rows-into-the-partitioned-table
  ;; The migration runs in its own schema, with the shape of the table before
  ;; it: the run below is the only place where its INSERT..SELECT runs with
  ;; rows, so a swapped column or a wrong `kind` fails here and not on a real
  ;; instance.
  (let [schema     (str "test_0158_" (subs (str (uuid/next)) 0 8))
        fid        (uuid/next)
        snap-id    (uuid/next)
        rev-id     (uuid/next)
        created-at (ct/in-past "2h")
        updated-at (ct/in-past "1h")
        deleted-at (ct/in-past "30m")
        snap-data  (blob/encode {:snapshot 1})
        rev-changes (blob/encode [{:type :add-obj :id (uuid/next)}])]

    (db/tx-run! th/*pool*
                (fn [{:keys [::db/conn]}]
                  ;; the schema is dropped before the commit, and a failure
                  ;; rolls the whole transaction back: nothing is left behind
                  (db/exec! conn [(str "CREATE SCHEMA " schema)])
                  (db/exec! conn [(str "SET LOCAL search_path TO " schema)])
                  (db/exec! conn [sql:old-schema])

                  (db/insert! conn :file {:id fid})

                  (db/insert! conn :file-change
                              {:id           snap-id
                               :file-id      fid
                               :revn         1
                               :data         snap-data
                               :label        "v1"
                               :version      1
                               :created-by   "user"
                               :created-at   created-at
                               :updated-at   updated-at
                               :deleted-at   deleted-at
                               ;; a row that still carries the dead columns
                               :data-ref-id  (uuid/next)
                               :data-backend "legacy-db"})

                  (db/insert! conn :file-change
                              {:id         rev-id
                               :file-id    fid
                               :revn       2
                               :changes    rev-changes
                               :created-at created-at
                               :updated-at updated-at})

                  (run-migration! conn)

                  (let [rows     (db/exec! conn ["SELECT id, revn, kind, label, data, changes,
                                                         created_at, updated_at, deleted_at, created_by
                                                    FROM file_change
                                                   ORDER BY revn"])
                        snapshot (first rows)
                        revision (second rows)
                        legacy   (db/exec! conn ["SELECT id, revn, data, changes
                                                    FROM file_change_legacy
                                                   ORDER BY revn"])]

                    (t/testing "both rows are in the partitioned table"
                      (t/is (= 2 (count rows)))
                      (t/is (= [1 2] (mapv :revn rows))))

                    (t/testing "the labelled row is a snapshot with its payload"
                      (t/is (= 1 (:kind snapshot)))
                      (t/is (= "v1" (:label snapshot)))
                      (t/is (= "user" (:created-by snapshot)))
                      (t/is (= (seq snap-data) (seq ^bytes (:data snapshot))))
                      (t/is (nil? (:changes snapshot)))
                      (t/is (= (inst-ms created-at) (inst-ms (:created-at snapshot))))
                      (t/is (= (inst-ms updated-at) (inst-ms (:updated-at snapshot))))
                      (t/is (= (inst-ms deleted-at) (inst-ms (:deleted-at snapshot)))))

                    (t/testing "the unlabelled row is a revision with its changes"
                      (t/is (= 2 (:kind revision)))
                      (t/is (nil? (:label revision)))
                      (t/is (= (seq rev-changes) (seq ^bytes (:changes revision))))
                      (t/is (nil? (:data revision)))
                      (t/is (= (inst-ms created-at) (inst-ms (:created-at revision)))))

                    (t/testing "the legacy table keeps the rows for the rollback"
                      (t/is (= 2 (count legacy)))
                      (t/is (= [snap-id rev-id] (mapv :id legacy)))
                      (t/is (= (seq snap-data)
                               (seq ^bytes (:data (first legacy)))))
                      (t/is (= (seq rev-changes)
                               (seq ^bytes (:changes (second legacy)))))))

                  (t/testing "the legacy table no longer points at file"
                    ;; Its foreign key on `file_id` is `NO ACTION`: while the table has
                    ;; rows, the objects GC cannot delete the files they point to. The
                    ;; migration drops that one and keeps the two `ON DELETE SET NULL` ones.
                    (t/is (= ["file_change_locked_by_fkey_legacy"
                              "file_change_profile_id_fkey_legacy"]
                             (->> (db/exec! conn ["SELECT con.conname AS name
                                                       FROM pg_constraint con
                                                      WHERE con.conrelid = 'file_change_legacy'::regclass
                                                        AND con.contype = 'f'
                                                      ORDER BY con.conname"])
                                  (mapv :name)))))

                  (t/testing "the dead columns are not carried over"
                    ;; The old row carried `data_ref_id` and `data_backend`;
                    ;; the new table has no such columns.
                    (t/is (= []
                             (->> (db/exec! conn ["SELECT attname AS name
                                                     FROM pg_attribute
                                                    WHERE attrelid = 'file_change'::regclass
                                                      AND attnum > 0 AND NOT attisdropped
                                                      AND attname IN ('data_ref_id', 'data_backend')"])
                                  (mapv :name)))))

                  (db/exec! conn [(str "DROP SCHEMA " schema " CASCADE")])))))

(t/deftest drops-the-legacy-table-when-it-is-empty
  ;; On a new instance there is nothing to keep: the migration leaves no
  ;; legacy table behind. The relation is asserted on its own, so a missing
  ;; table can never pass for a clean one.
  (let [schema (str "test_0158_empty_" (subs (str (uuid/next)) 0 8))]
    (db/tx-run! th/*pool*
                (fn [{:keys [::db/conn]}]
                  (db/exec! conn [(str "CREATE SCHEMA " schema)])
                  (db/exec! conn [(str "SET LOCAL search_path TO " schema)])
                  (db/exec! conn [sql:old-schema])

                  (run-migration! conn)

                  (t/is (-> (db/exec-one! conn ["SELECT to_regclass('file_change_legacy') IS NULL AS empty"])
                            :empty)
                        "the empty legacy table is dropped")

                  (db/exec! conn [(str "DROP SCHEMA " schema " CASCADE")])))))

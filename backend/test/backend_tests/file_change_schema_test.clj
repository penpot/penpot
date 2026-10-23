;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-change-schema-test
  (:require
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- query
  "Runs a query that returns a single value and returns it."
  [sql & params]
  (-> (db/exec! th/*pool* (into [sql] params))
      first
      (vals)
      first))

(defn- partition-names
  []
  (->> (db/exec! th/*pool* ["SELECT c.relname AS name
                               FROM pg_class c
                               JOIN pg_partition_tree('file_change') t ON t.relid = c.oid
                              WHERE t.isleaf
                              ORDER BY c.relname"])
       (mapv :name)))

(t/deftest file-change-is-partitioned-in-sixteen-named-partitions
  (t/is (= (mapv #(format "file_change_%02d" %) (range 16))
           (partition-names))))

(t/deftest file-change-primary-key-is-file-id-and-id
  (t/is (= "PRIMARY KEY (file_id, id)"
           (query "SELECT pg_get_constraintdef(oid) AS def
                     FROM pg_constraint
                    WHERE conrelid = 'file_change'::regclass AND contype = 'p'"))))

(t/deftest file-change-has-three-foreign-keys-and-one-check
  (t/is (= 3 (query "SELECT count(*) AS total
                        FROM pg_constraint
                       WHERE conrelid = 'file_change'::regclass AND contype = 'f'")))
  (t/is (= 1 (query "SELECT count(*) AS total
                        FROM pg_constraint
                       WHERE conrelid = 'file_change'::regclass AND contype = 'c'")))
  (t/is (re-find #"kind"
                 (query "SELECT pg_get_constraintdef(oid) AS def
                            FROM pg_constraint
                           WHERE conrelid = 'file_change'::regclass AND contype = 'c'"))))

(t/deftest file-change-has-the-six-canonical-indexes
  (t/is (= ["file_change__deleted_at__idx"
            "file_change__file_id__revn__idx"
            "file_change__locked_by__idx"
            "file_change__profile_id__idx"
            "file_change__user_snapshots__idx"
            "file_change_pkey"]
           (->> (db/exec! th/*pool* ["SELECT indexname AS name
                                       FROM pg_indexes
                                      WHERE tablename = 'file_change'
                                        AND schemaname = current_schema()
                                      ORDER BY indexname"])
                (mapv :name)))))

(t/deftest file-change-has-no-legacy-columns-and-a-kind-column
  (t/is (= []
           (->> (db/exec! th/*pool* ["SELECT attname AS name
                                     FROM pg_attribute
                                    WHERE attrelid = 'file_change'::regclass
                                      AND attnum > 0 AND NOT attisdropped
                                      AND attname IN ('data', 'data_ref_id', 'data_backend')"])
                (mapv :name))))
  (t/is (true? (query "SELECT attnotnull AS notnull
                          FROM pg_attribute
                         WHERE attrelid = 'file_change'::regclass
                           AND attname = 'kind'"))))

(t/deftest the-legacy-table-has-no-foreign-key-to-file
  ;; The legacy table is kept as the way back, but its foreign key on
  ;; `file_id` is `NO ACTION`: while it has rows, the objects GC cannot
  ;; delete the files they point to.
  (t/is (= 0 (query "SELECT count(*) AS total
                       FROM pg_constraint con
                       JOIN pg_class rel ON rel.oid = con.conrelid
                      WHERE rel.relname = 'file_change_legacy'
                        AND con.contype = 'f'
                        AND con.confrelid = 'file'::regclass"))))

(t/deftest inserting-a-row-without-a-kind-fails
  ;; The column has no default on purpose: an insert that forgets it has to
  ;; fail instead of storing the wrong kind silently.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (t/is (thrown? java.sql.SQLException
                   (db/exec! th/*pool* ["INSERT INTO file_change (file_id, id)
                                           VALUES (?, gen_random_uuid())"
                                        (:id file)])))))

(t/deftest inserting-a-row-with-an-unknown-kind-fails
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (t/is (thrown? java.sql.SQLException
                   (db/exec! th/*pool* ["INSERT INTO file_change (file_id, id, kind)
                                           VALUES (?, gen_random_uuid(), 3)"
                                        (:id file)])))))

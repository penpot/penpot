;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.migrations-legacy-file-data-test
  "Covers the 0157 data migration: legacy file/file_change payloads move
  to file_data, keeping timestamps and deleted_at, nulling the legacy
  column; unlabelled file_change payloads (orphan revisions) are
  deleted."
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [next.jdbc :as jdbc]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- run-migration
  []
  (let [sql (slurp (io/resource "app/migrations/sql/0157-migrate-legacy-file-data.sql"))]
    (jdbc/execute! th/*pool* [sql])))

(defn- get-file-data
  [file-id id]
  (th/db-get :file-data {:file-id file-id :id id} {::db/remove-deleted false}))

(t/deftest migrates-legacy-file-and-snapshot
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        fid     (:id file)

        ;; Simulate an unmigrated main: move the payload back to
        ;; file.data and drop the file_data row.
        main-data   (:data (get-file-data fid fid))
        _           (t/is (some? main-data) "expected file_data main row from create-file*")
        file-row    (th/db-get :file {:id fid} {::db/remove-deleted false})
        _           (th/db-delete! :file-data {:file-id fid :id fid})
        _           (th/db-update! :file {:data main-data} {:id fid})

        ;; Simulate an unmigrated snapshot (labelled) with its own
        ;; timestamps and a deletion date.
        snap-id     (uuid/next)
        snap-data   (blob/encode {:snapshot 1})
        created-at  (ct/in-past "2h")
        updated-at  (ct/in-past "1h")
        deleted-at  (ct/in-past "30m")
        _           (th/db-insert! :file-change
                                   {:id snap-id
                                    :file-id fid
                                    :revn 1
                                    :data snap-data
                                    :label "v1"
                                    :created-at created-at
                                    :updated-at updated-at
                                    :deleted-at deleted-at})

        ;; Orphan revision (data, no label): deleted by the migration.
        rev-id      (uuid/next)
        rev-data    (blob/encode {:rev 1})
        _           (th/db-insert! :file-change
                                   {:id rev-id
                                    :file-id fid
                                    :revn 2
                                    :data rev-data})

        ;; Live revision (changes, no data, no label): untouched.
        live-id     (uuid/next)
        _           (th/db-insert! :file-change
                                   {:id live-id
                                    :file-id fid
                                    :revn 3
                                    :changes (blob/encode [{:type :dummy}])})]

    (run-migration)

    (t/testing "main payload moves to file_data as type main"
      (let [fd (get-file-data fid fid)]
        (t/is (some? fd))
        (t/is (= "main" (:type fd)))
        (t/is (= "db" (:backend fd)))
        (t/is (nil? (:metadata fd)))
        (t/is (= (seq ^bytes main-data) (seq ^bytes (:data fd))))
        (t/is (= (:created-at file-row) (:created-at fd)))
        (t/is (= (:modified-at file-row) (:modified-at fd)))
        (t/is (= (:deleted-at file-row) (:deleted-at fd)))))

    (t/testing "snapshot payload moves to file_data as type snapshot"
      (let [fd (get-file-data fid snap-id)]
        (t/is (some? fd))
        (t/is (= "snapshot" (:type fd)))
        (t/is (= "db" (:backend fd)))
        (t/is (nil? (:metadata fd)))
        (t/is (= (seq ^bytes snap-data) (seq ^bytes (:data fd))))
        ;; NOTE: timestamptz stores microsecond precision while the
        ;; test instants carry nanoseconds, so compare at millis.
        (t/is (= (inst-ms created-at) (inst-ms (:created-at fd))))
        (t/is (= (inst-ms updated-at) (inst-ms (:modified-at fd))))
        (t/is (= (inst-ms deleted-at) (inst-ms (:deleted-at fd))))))

    (t/testing "legacy columns are nulled"
      (t/is (nil? (:data (th/db-get :file {:id fid} {::db/remove-deleted false}))))
      (t/is (nil? (:data (th/db-get :file-change {:id snap-id} {::db/remove-deleted false})))))

    (t/testing "unlabelled payload row is deleted, live revision stays"
      (t/is (nil? (th/db-get :file-change {:id rev-id} {::db/remove-deleted false})))
      (let [row (th/db-get :file-change {:id live-id} {::db/remove-deleted false})]
        (t/is (some? row))
        (t/is (some? (:changes row)))))

    (t/testing "second run is a no-op"
      (run-migration)
      (t/is (some? (get-file-data fid fid)))
      (t/is (some? (get-file-data fid snap-id)))
      (t/is (nil? (:data (th/db-get :file {:id fid} {::db/remove-deleted false}))))
      (t/is (nil? (:data (th/db-get :file-change {:id snap-id} {::db/remove-deleted false}))))
      (t/is (nil? (th/db-get :file-change {:id rev-id} {::db/remove-deleted false}))))))

(t/deftest keeps-preexisting-file-data-row
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        fid     (:id file)

        ;; file_data already holds the current payload; the legacy
        ;; column keeps a stale copy that must not overwrite it.
        current   (:data (get-file-data fid fid))
        stale     (blob/encode {:stale true})
        _         (t/is (some? current))
        _         (t/is (not= (seq ^bytes current) (seq ^bytes stale)))
        _         (th/db-update! :file {:data stale} {:id fid})]

    (run-migration)

    (t/testing "file_data keeps its data and legacy is nulled"
      (let [fd (get-file-data fid fid)]
        (t/is (= (seq ^bytes current) (seq ^bytes (:data fd)))))
      (t/is (nil? (:data (th/db-get :file {:id fid} {::db/remove-deleted false})))))))

(t/deftest keeps-preexisting-snapshot-row
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        fid     (:id file)

        ;; file_data already holds the snapshot payload; the legacy column
        ;; keeps a stale copy that must not overwrite it.
        snap-id (uuid/next)
        current (blob/encode {:snapshot :current})
        stale   (blob/encode {:snapshot :stale})
        _       (t/is (not= (seq ^bytes current) (seq ^bytes stale)))
        _       (th/db-insert! :file-data
                               {:file-id  fid
                                :id       snap-id
                                :type     "snapshot"
                                :backend  "db"
                                :metadata nil
                                :data     current})
        _       (th/db-insert! :file-change
                               {:id      snap-id
                                :file-id fid
                                :revn    1
                                :label   "v1"
                                :data    stale})]

    (run-migration)

    (t/testing "file_data keeps its data and legacy is nulled"
      (let [fd (get-file-data fid snap-id)]
        (t/is (= (seq ^bytes current) (seq ^bytes (:data fd)))))
      (t/is (nil? (:data (th/db-get :file-change {:id snap-id}
                                    {::db/remove-deleted false})))))))


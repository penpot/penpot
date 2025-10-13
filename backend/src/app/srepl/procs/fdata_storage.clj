;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.procs.fdata-storage
  (:require
   [app.common.logging :as l]
   [app.db :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SNAPSHOTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:get-unmigrated-snapshots
  "SELECT fc.id, fc.file_id
     FROM file_change AS fc
    WHERE fc.data IS NOT NULL
      AND fc.label IS NOT NULL
    ORDER BY fc.id ASC")

(def sql:get-migrated-snapshots
  "SELECT f.id, f.file_id
     FROM file_data AS f
    WHERE f.data IS NOT NULL
      AND f.type = 'snapshot'
      AND f.id != f.file_id
    ORDER BY f.id ASC")

(defn migrate-snapshot-to-storage
  "Migrate the current existing files to store data in new storage
  tables."
  {:query sql:get-unmigrated-snapshots}
  [{:keys [::db/conn]} {:keys [id file-id]} & {:as options}]
  (let [{:keys [id file-id data created-at updated-at]}
        (db/get* conn :file-change {:id id :file-id file-id}
                 ::db/for-update true
                 ::db/remove-deleted false)]
    (when data
      (l/inf :hint "migrating snapshot" :file-id (str file-id) :id (str id))
      (db/update! conn :file-change
                  {:data nil}
                  {:id id :file-id file-id}
                  {::db/return-keys false})
      (db/insert! conn :file-data
                  {:backend "db"
                   :metadata nil
                   :type "snapshot"
                   :data data
                   :created-at created-at
                   :modified-at updated-at
                   :file-id file-id
                   :id id}
                  {::db/return-keys false}))))

(defn rollback-snapshot-from-storage
  "Migrate back to the file table storage."
  {:query sql:get-unmigrated-snapshots}
  [{:keys [::db/conn]} {:keys [id file-id]} & {:as opts}]
  (when-let [{:keys [id file-id data]}
             (db/get* conn :file-data {:id id :file-id file-id :type "snapshot"}
                      ::db/for-update true
                      ::db/remove-deleted false)]
    (l/inf :hint "rollback snapshot" :file-id (str file-id) :id (str id))
    (db/update! conn :file-change
                {:data data}
                {:id id :file-id file-id}
                {::db/return-keys false})
    (db/delete! conn :file-data
                {:id id :file-id file-id :type "snapshot"}
                {::db/return-keys false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sql:get-unmigrated-files
  "SELECT f.id
     FROM file AS f
    WHERE f.data IS NOT NULL
    ORDER BY f.modified_at ASC")

(def sql:get-migrated-files
  "SELECT f.id, f.file_id
     FROM file_data AS f
    WHERE f.data IS NOT NULL
      AND f.id = f.file_id
    ORDER BY f.id ASC")

(defn migrate-file-to-storage
  "Migrate the current existing files to store data in new storage
  tables."
  {:query sql:get-unmigrated-files}
  [{:keys [::db/conn] :as cfg} {:keys [id]} & {:as opts}]
  (let [{:keys [id data created-at modified-at]}
        (db/get* conn :file {:id id}
                 ::db/for-update true
                 ::db/remove-deleted false)]

    (when data
      (l/inf :hint "migrating file" :file-id (str id))

      (db/update! conn :file {:data nil} {:id id} ::db/return-keys false)
      (db/insert! conn :file-data
                  {:backend "db"
                   :metadata nil
                   :type "main"
                   :data data
                   :created-at created-at
                   :modified-at modified-at
                   :file-id id
                   :id id}
                  {::db/return-keys false}))

    (let [snapshots-sql
          (str "WITH snapshots AS (" sql:get-unmigrated-snapshots ") "
               "SELECT s.* FROM snapshots AS s WHERE s.file_id = ?")]
      (run! (fn [params]
              (migrate-snapshot-to-storage cfg params opts))
            (db/plan cfg [snapshots-sql id])))))


(defn rollback-file-from-storage
  "Migrate back to the file table storage."
  {:query sql:get-migrated-files}
  [{:keys [::db/conn] :as cfg} {:keys [id]} & {:as opts}]
  (when-let [{:keys [id data]}
             (db/get* conn :file-data {:id id :file-id id :type "main"}
                      ::db/for-update true
                      ::db/remove-deleted false)]
    (l/inf :hint "rollback file" :file-id (str id))
    (db/update! conn :file {:data data} {:id id} ::db/return-keys false)
    (db/delete! conn :file-data {:file-id id :id id :type "main"} ::db/return-keys false)

    (let [snapshots-sql
          (str "WITH snapshots AS (" sql:get-migrated-snapshots ") "
               "SELECT s.* FROM snapshots AS s WHERE s.file_id = ?")]
      (run! (fn [params]
              (rollback-snapshot-from-storage cfg params opts))
            (db/plan cfg [snapshots-sql id])))))

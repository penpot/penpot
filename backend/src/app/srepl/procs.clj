;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.procs
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.files.changes :as cfc]
   [app.common.files.helpers :as cfh]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.srepl.helpers :as h]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-with-path-data
  "SELECT id FROM file WHERE features @> '{fdata/path-data}'")

(defn disable-path-data
  "A script responsible for remove the path data type from file data and
  allow file to be open in older penpot versions.

  Should be used only in cases when you want to downgrade to an older
  penpot version for some reason."
  {:query sql:get-files-with-path-data}
  [cfg {:keys [id]} & {:as options}]

  (let [update-object
        (fn [object]
          (if (or (cfh/path-shape? object)
                  (cfh/bool-shape? object))
            (update object :content vec)
            object))

        update-container
        (fn [container]
          (d/update-when container :objects d/update-vals update-object))

        update-file
        (fn [file & _opts]
          (-> file
              (update :data (fn [data]
                              (-> data
                                  (update :pages-index d/update-vals update-container)
                                  (d/update-when :components d/update-vals update-container))))
              (update :features disj "fdata/path-data")
              (update :migrations disj
                      "0003-convert-path-content-v2"
                      "0003-convert-path-content")))

        options
        (-> options
            (assoc ::bfc/reset-migrations? true)
            (assoc ::h/validate? false))]

    (h/process-file! cfg id update-file options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STORAGE
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

(defn migrate-files-to-storage
  "Migrate the current existing files to store data in new storage
  tables."
  {:query sql:get-unmigrated-files}
  [{:keys [::db/conn]} {:keys [id]} & {:as opts}]
  (l/dbg :hint "migrating file" :file-id (str id))
  (let [{:keys [id data created-at modified-at]}
        (db/get* conn :file {:id id}
                 ::db/for-update true
                 ::db/remove-deleted false)]
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
                {::db/return-keys false})))

(defn rollback-files-from-storage
  "Migrate back to the file table storage."
  {:query sql:get-migrated-files}
  [{:keys [::db/conn]} {:keys [id file-id]} & {:as opts}]
  (l/dbg :hint "rollback file" :file-id (str id))
  (let [{:keys [id data]}
        (db/get* conn :file-data {:id id :file-id file-id}
                 ::db/for-update true
                 ::db/remove-deleted false)]
    (db/update! conn :file {:data data} {:id id} ::db/return-keys false)
    (db/delete! conn :file-data {:id id} ::db/return-keys false)))

(def sql:get-unmigrated-snapshots
  "SELECT fc.id, fc.file_id
     FROM file_change AS fc
    WHERE fc.data IS NOT NULL
      AND f.label IS NOT NULL
    ORDER BY f.id ASC")

(def sql:get-migrated-snapshots
  "SELECT f.id, f.file_id
     FROM file_data AS f
    WHERE f.data IS NOT NULL
      AND f.type = 'snapshot'
      AND f.id != f.file_id
    ORDER BY f.id ASC")

(defn migrate-snapshots-to-storage
  "Migrate the current existing files to store data in new storage
  tables."
  {:query sql:get-unmigrated-snapshots}
  [{:keys [::db/conn]} {:keys [id file-id]} & {:as options}]
  (l/dbg :hint "migrating snapshot" :file-id (str id) :id (str id))
  (let [{:keys [id file-id data created-at updated-at]}
        (db/get* conn :file-change {:id id :file-id file-id}
                 ::db/for-update true
                 ::db/remove-deleted false)]

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
                {::db/return-keys false})))

(defn rollback-snapshots-from-storage
  "Migrate back to the file table storage."
  {:query sql:get-unmigrated-snapshots}
  [{:keys [::db/conn]} {:keys [id file-id]} & {:as opts}]
  (l/dbg :hint "rollback snapshot" :file-id (str file-id) :id (str id))
  (let [{:keys [id file-id data]}
        (db/get* conn :file-data {:id id :file-id file-id}
                 ::db/for-update true
                 ::db/remove-deleted false)]
    (db/update! conn :file-change
                {:data data}
                {:id id :file-id file-id}
                {::db/return-keys false})
    (db/delete! conn :file-data
                {:id id :file-id file-id}
                {::db/return-keys false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GENERAL PURPOSE REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn repair-file
  "Internal helper for validate and repair the file. The operation is
  applied multiple times untile file is fixed or max iteration counter
  is reached (default 10).

  This function should not be used directly, it is used throught the
  app.srepl.main/repair-file! helper. In practical terms this function
  is private and implementation detail."
  [file libs & {:keys [max-iterations] :or {max-iterations 10}}]

  (let [validate-and-repair
        (fn [file libs iteration]
          (when-let [errors (not-empty (cfv/validate-file file libs))]
            (l/trc :hint "repairing file"
                   :file-id (str (:id file))
                   :iteration iteration
                   :errors (count errors))
            (let [changes (cfr/repair-file file libs errors)]
              (-> file
                  (update :revn inc)
                  (update :data cfc/process-changes changes)))))

        process-file
        (fn [file libs]
          (loop [file      file
                 iteration 0]
            (if (< iteration max-iterations)
              (if-let [file (validate-and-repair file libs iteration)]
                (recur file (inc iteration))
                file)
              (do
                (l/wrn :hint "max retry num reached on repairing file"
                       :file-id (str (:id file))
                       :iteration iteration)
                file))))

        file'
        (process-file file libs)]

    (when (not= (:revn file) (:revn file'))
      (l/trc :hint "file repaired" :file-id (str (:id file))))

    file'))

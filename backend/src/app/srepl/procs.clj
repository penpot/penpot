;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.procs
  (:require
   [app.common.data :as d]
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.features.fdata :as fdata]
   [app.srepl.helpers :as h]
   [app.common.time :as ct]
   [app.common.types.path :as path]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH-DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-with-path-data
  "SELECT id FROM file WHERE features @> '{fdata/path-data}' AND id = '469eee50-acf6-81e9-8006-d1365cdf4f7c'")

(defn disable-path-data
  "A script responsible for remove the path data type from file data and
  allow file to be open in older penpot versions.

  Should be used only in cases when you want to downgrade to an older
  penpot version for some reason."
  {:query sql:get-files-with-path-data}
  [{:keys [::db/conn] :as cfg} {:keys [id]} & {:as options}]

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
        (fn [file]
          (-> file
              (update :data (fn [data]
                              (-> data
                                  (update :pages-index d/update-vals update-container)
                                  (d/update-when :components d/update-vals update-container))))
              (update :features disj "fdata/path-data")
              (update :migrations disj
                      "0003-convert-path-content-v2"
                      "0003-convert-path-content")))]

    (h/process-file! cfg id update-file (assoc options
                                               ::bfc/reset-migrations? true
                                               ::h/validate? false))))


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
  (let [{:keys [id data index created-at modified-at]}
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

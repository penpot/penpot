;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.offload-file-data
  "A maintenance task responsible of moving file data from hot
  storage (the database row) to a cold storage (fs or s3)."
  (:require
   [app.binfile.common :as bfc]
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [integrant.core :as ig]))

(defn- offload-file-data
  [{:keys [::db/conn ::file-id] :as cfg}]
  (let [file (bfc/get-file cfg file-id :realize? true :lock-for-update? true)]
    (cond
      (not= "db" (:backend file))
      (l/wrn :hint (str "skiping file offload (file offloaded or incompatible with offloading) for " file-id)
             :file-id (str file-id))

      (nil? (:data file))
      (l/err :hint (str "skiping file offload (missing data) for " file-id)
             :file-id (str file-id))

      :else
      (do
        (fdata/update! cfg {:id file-id
                            :file-id file-id
                            :type "main"
                            :backend "storage"
                            :data (blob/encode (:data file))})

        (db/update! conn :file
                    {:data nil}
                    {:id file-id}
                    {::db/return-keys false})

        (l/trc :hint "offload file data"
               :file-id (str file-id))))))

(def sql:get-snapshots
  (str "WITH snapshots AS (" fsnap/sql:snapshots ")"
       "SELECT s.*
          FROM snapshots AS s
         WHERE s.backend = 'db'
           AND s.file_id = ?
         ORDER BY s.created_at"))

(defn- offload-snapshot-data
  [{:keys [::db/conn ::file-id] :as cfg} snapshot]
  (let [{:keys [id data] :as snapshot} (fdata/resolve-file-data cfg snapshot)]
    (if (nil? (:data snapshot))
      (l/err :hint (str "skiping snapshot offload (missing data) for " file-id)
             :file-id (str file-id)
             :snapshot-id id)
      (do
        (fsnap/create! cfg {:id id
                            :file-id file-id
                            :type "snapshot"
                            :backend "storage"
                            :data data})

        (l/trc :hint "offload snapshot data"
               :file-id (str file-id)
               :snapshot-id (str id))

        (db/update! conn :file-change
                    {:data nil}
                    {:id id :file-id file-id}
                    {::db/return-keys false})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (let [file-id (:file-id props)]
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (assoc ::file-id (:file-id props))
          (db/tx-run! (fn [{:keys [::db/conn] :as cfg}]
                        (offload-file-data cfg)

                        (run! (partial offload-snapshot-data cfg)
                              (db/plan conn [sql:get-snapshots file-id]))))))))

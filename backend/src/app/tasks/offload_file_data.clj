;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.offload-file-data
  "A maintenance task responsible of moving file data from hot
  storage (the database row) to a cold storage (fs or s3)."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
   [integrant.core :as ig]))

(defn- offload-file-data!
  [{:keys [::db/conn ::sto/storage ::file-id] :as cfg}]
  (let [file (db/get conn :file {:id file-id}
                     {::sql/for-update true})]
    (when (nil? (:data file))
      (ex/raise :hint "file already offloaded"
                :type :internal
                :code :file-already-offloaded
                :file-id file-id))

    (let [data (sto/content (:data file))
          sobj (sto/put-object! storage
                                {::sto/content data
                                 ::sto/touch true
                                 :bucket "file-data"
                                 :content-type "application/octet-stream"
                                 :file-id file-id})]

      (l/trc :hint "offload file data"
             :file-id (str file-id)
             :storage-id (str (:id sobj)))

      (db/update! conn :file
                  {:data-backend "objects-storage"
                   :data-ref-id (:id sobj)
                   :data nil}
                  {:id file-id}
                  {::db/return-keys false}))))

(defn- offload-file-data-fragments!
  [{:keys [::db/conn ::sto/storage ::file-id] :as cfg}]
  (doseq [fragment (db/query conn :file-data-fragment
                             {:file-id file-id
                              :deleted-at nil
                              :data-backend nil}
                             {::db/for-update true})]
    (let [data (sto/content (:data fragment))
          sobj (sto/put-object! storage
                                {::sto/content data
                                 ::sto/touch true
                                 :bucket "file-data-fragment"
                                 :content-type "application/octet-stream"
                                 :file-id file-id
                                 :file-fragment-id (:id fragment)})]

      (l/trc :hint "offload file data fragment"
             :file-id (str file-id)
             :file-fragment-id (str (:id fragment))
             :storage-id (str (:id sobj)))

      (db/update! conn :file-data-fragment
                  {:data-backend "objects-storage"
                   :data-ref-id (:id sobj)
                   :data nil}
                  {:id (:id fragment)}
                  {::db/return-keys false}))))

(def sql:get-snapshots
  "SELECT fc.*
     FROM file_change AS fc
    WHERE fc.file_id = ?
      AND fc.label IS NOT NULL
      AND fc.data IS NOT NULL
      AND fc.data_backend IS NULL")

(defn- offload-file-snapshots!
  [{:keys [::db/conn ::sto/storage ::file-id] :as cfg}]
  (doseq [snapshot (db/exec! conn [sql:get-snapshots file-id])]
    (let [data (sto/content (:data snapshot))
          sobj (sto/put-object! storage
                                {::sto/content data
                                 ::sto/touch true
                                 :bucket "file-change"
                                 :content-type "application/octet-stream"
                                 :file-id file-id
                                 :file-change-id (:id snapshot)})]

      (l/trc :hint "offload file change"
             :file-id (str file-id)
             :file-change-id (str (:id snapshot))
             :storage-id (str (:id sobj)))

      (db/update! conn :file-change
                  {:data-backend "objects-storage"
                   :data-ref-id (:id sobj)
                   :data nil}
                  {:id (:id snapshot)}
                  {::db/return-keys false}))))

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
    (-> cfg
        (assoc ::db/rollback (:rollback? props))
        (assoc ::file-id (:file-id props))
        (db/tx-run! (fn [cfg]
                      (offload-file-data! cfg)
                      (offload-file-data-fragments! cfg)
                      (offload-file-snapshots! cfg))))))

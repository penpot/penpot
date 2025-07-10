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
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def ^:private sql:get-file
  (str bfc/sql:get-file " FOR UPDATE OF f"))

(defn get-file
  "Get not-decoded file, only decodes the features set."
  [cfg id]
  (let [conn (db/get-connection cfg)]
    (db/get-with-sql conn [sql:get-file id])))

(defn- offload-file-data
  [{:keys [::db/conn ::sto/storage ::file-id] :as cfg}]
  (let [file (get-file cfg file-id)]
    (cond
      (:legacy-data file)
      (l/wrn :hint (str "skiping file offload (legacy storage detected) for " file-id)
             :file-id (str file-id))

      (some? (:backend file))
      (l/wrn :hint (str "skiping file offload (file offloaded or incompatible with offloading) for " file-id)
             :file-id (str file-id))

      (nil? (:data file))
      (l/err :hint (str "skiping file offload (missing data) for " file-id)
             :file-id (str file-id))

      :else
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

        (db/update! conn :file-data
                    {:backend "storage"
                     :metadata (db/tjson {:storage/id (:id sobj)})
                     :content nil}
                    {:id file-id :file-id file-id :type "main"}
                    {::db/return-keys false})))))


(def sql:get-fragments
  "SELECT f.id,
          f.content AS data
     FROM file_data AS f
    WHERE f.file_id = ?
      AND f.type = 'fragment'
      AND f.backend IS NULL
      AND f.deleted_at IS NULL
    ORDER BY f.created_at ASC
      FOR UPDATE")

(defn- offload-fragment-data
  [{:keys [::db/conn ::sto/storage ::file-id] :as cfg}
   {:keys [id data] :as fragment}]

  (let [data (sto/content data)
        sobj (sto/put-object! storage
                              {::sto/content data
                               ::sto/touch true
                               :bucket "file-data"
                               :content-type "application/octet-stream"
                               :file-id file-id
                               :file-data-id (:id fragment)})]

    (l/trc :hint "offload fragment data"
           :file-id (str file-id)
           :fragment-id (str id)
           :storage-id (str (:id sobj)))

    (db/update! conn :file-data
                {:backend "storage"
                 :metadata (db/tjson {:storage/id (:id sobj)})
                 :content nil}
                {:id id :file-id file-id :type "fragment"}
                {::db/return-keys false})))

(def sql:get-snapshots
  "SELECT fc.id,
          fc.project_id,
          fc.created_at,
          fc.updated_at,
          fc.deleted_at,
          fc.revn,
          fc.data AS legacy_data,
          fc.features,
          fc.version,
          fd.backend AS backend,
          fd.metadata AS metadata,
          fd.content AS data
     FROM file_change AS fc
     LEFT JOIN file_data AS fd ON (fd.file_id = fc.file_id
                                   AND fd.id = fc.id
                                   AND fd.type = 'snapshot')
    WHERE fc.file_id = ?
      AND fc.label IS NOT NULL
    ORDER BY fc.created_at ASC
      FOR UPDATE OF fc")

(defn- offload-snapshot-data
  [{:keys [::db/conn ::sto/storage ::file-id] :as cfg}
   {:keys [id] :as snapshot}]

  (cond
    (:legacy-data snapshot)
    (l/wrn :hint (str "skiping snapshot offload (legacy storage detected) for " id)
           :snapshot-id (str id)
           :file-id (str file-id))

    (some? (:backend snapshot))
    (l/err :hint (str "skiping snapshot offload (file offloaded or incompatible with offloading) for " id)
           :snapshot-id (str id)
           :file-id (str file-id))

    (nil? (:data snapshot))
    (l/err :hint (str "skiping snapshot offload (missing data) for " id)
           :snapshot-id (str id)
           :file-id (str file-id))

    :else
    (let [data (sto/content (:data snapshot))
          sobj (sto/put-object! storage
                                {::sto/content data
                                 ::sto/touch true
                                 :bucket "file-data"
                                 :content-type "application/octet-stream"
                                 :file-id file-id
                                 :file-data-id id})]

      (l/trc :hint "offload snapshot data"
             :file-id (str file-id)
             :snapshot-id (str id)
             :storage-id (str (:id sobj)))

      (db/update! conn :file-data
                  {:backend "storage"
                   :metadata (db/tjson {:storage/id (:id sobj)})
                   :content nil}
                  {:id id :file-id file-id :type "snapshot"}
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
    (let [file-id (:file-id props)]
      (-> cfg
          (assoc ::db/rollback (:rollback? props))
          (assoc ::file-id (:file-id props))
          (db/tx-run! (fn [{:keys [::db/conn] :as cfg}]
                        (offload-file-data cfg)

                        (run! (partial offload-snapshot-data cfg)
                              (db/plan conn [sql:get-snapshots file-id]))

                        (run! (partial offload-fragment-data cfg)
                              (db/plan conn [sql:get-fragments file-id]))))))))

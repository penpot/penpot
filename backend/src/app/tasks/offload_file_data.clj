;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.offload-file-data
  "A maintenance task responsible of moving file data from hot
  storage (the database row) to a cold storage (fs or s3)."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def ^:private sql:get-file-data
  "SELECT fd.*
     FROM file_data AS fd
    WHERE fd.file_id = ?
      AND fd.backend = 'db'
      AND fd.deleted_at IS NULL")

(defn- offload-file-data
  [cfg {:keys [id file-id type] :as fdata}]
  (fdata/upsert! cfg (assoc fdata :backend "storage"))
  (l/trc :file-id (str file-id)
         :id (str id)
         :type type))

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
          (db/tx-run! (fn [{:keys [::db/conn] :as cfg}]
                        (run! (partial offload-file-data cfg)
                              (db/plan conn [sql:get-file-data file-id]))))))))

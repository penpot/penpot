;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.offload-file-data
  "A maintenance task responsible of moving file data from hot
  storage (the database row) to a cold storage (fs or s3)."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.jobs :as jobs]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def ^:private sql:get-file-data
  "SELECT fd.*
     FROM file_data AS fd
    WHERE fd.file_id = ?
      AND fd.backend = 'db'
      AND fd.deleted_at IS NULL")

(def ^:private sql:lock-file
  "SELECT id FROM file WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED")

(defn- offload-file-data
  [cfg {:keys [id file-id type] :as fdata}]
  (jobs/heartbeat cfg)
  (fdata/upsert! cfg (assoc fdata :backend "storage"))
  (l/trc :file-id (str file-id)
         :id (str id)
         :type type))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-offload-file-data
  "Plain job handler: offload the file data rows of one file."
  [cfg params]
  (let [file-id (:file-id params)]
    (-> cfg
        (assoc ::db/rollback (:rollback? params))
        (db/tx-run! (fn [{:keys [::db/conn] :as cfg}]
                      (if (db/exec-one! conn [sql:lock-file file-id])
                        (let [counter (volatile! 0)]
                          (run! (fn [fdata]
                                  (offload-file-data cfg fdata)
                                  (vswap! counter inc))
                                (db/plan conn [sql:get-file-data file-id]))
                          (let [total @counter]
                            (l/trc :hint "file data offloaded"
                                   :file-id (str file-id)
                                   :offloaded total)
                            {:offloaded total :skipped 0}))
                        (do
                          (l/dbg :hint "skip offload, file is locked or gone"
                                 :file-id (str file-id))
                          {:offloaded 0 :skipped 1})))))))

(def schema:offload-file-data-params
  [:map
   [:file-id ::sm/uuid]])

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :offload-file-data
   ::jobs/schema    schema:offload-file-data-params
   ::jobs/handler
   (fn [_context params]
     (execute-offload-file-data cfg params))
   ::jobs/decoder   (sm/decoder schema:offload-file-data-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:offload-file-data-params)})

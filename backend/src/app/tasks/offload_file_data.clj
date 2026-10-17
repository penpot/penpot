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
   [app.common.time :as ct]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.jobs :as jobs]
   [app.jobs.metrics :as metrics]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [integrant.core :as ig]))

(def ^:private sql:get-file-data
  "SELECT fd.*
     FROM file_data AS fd
    WHERE fd.file_id = ?
      AND fd.backend = 'db'
      AND fd.deleted_at IS NULL")

(def ^:private sql:lock-file
  ;; NOTE: SKIP LOCKED only serializes against writers that also lock
  ;; the file row. Every file_data writer must therefore hold at least
  ;; FOR SHARE on it (the update RPC via lock-for-share?, file-gc via
  ;; FOR UPDATE); a writer skipping that lock would reopen the
  ;; lost-update race this lock closes.
  "SELECT id FROM file WHERE id = ? AND deleted_at IS NULL FOR UPDATE SKIP LOCKED")

(defn- offload-file-data
  [cfg {:keys [id file-id type data] :as fdata} stats]
  (jobs/heartbeat cfg)
  (fdata/upsert! cfg (assoc fdata :backend "storage"))
  (vswap! stats (fn [{:keys [rows bytes by-type]}]
                  (let [size (if (bytes? data) (alength ^bytes data) 0)]
                    {:rows    (inc rows)
                     :bytes   (+ bytes size)
                     :by-type (update by-type type
                                      (fn [{r :rows b :bytes :or {r 0 b 0}}]
                                        {:rows (+ r 1) :bytes (+ b size)}))})))
  (l/trc :file-id (str file-id)
         :id (str id)
         :type type))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn execute-offload-file-data
  "Plain job handler: offload the file data rows of one file."
  [cfg params]
  (let [file-id (:file-id params)
        tpoint  (ct/tpoint)]
    (try
      (let [summary (-> cfg
                        (assoc ::db/rollback (:rollback? params))
                        (db/tx-run! (fn [{:keys [::db/conn] :as cfg}]
                                      (if (db/exec-one! conn [sql:lock-file file-id])
                                        (let [stats (volatile! {:rows 0 :bytes 0 :by-type {}})]
                                          (run! (fn [fdata]
                                                  (offload-file-data cfg fdata stats))
                                                (db/plan conn [sql:get-file-data file-id]))
                                          (let [{:keys [rows bytes by-type]} @stats]
                                            (l/trc :hint "file data offloaded"
                                                   :file-id (str file-id)
                                                   :offloaded rows
                                                   :bytes bytes)
                                            {:offloaded rows :bytes bytes :by-type by-type :skipped 0}))
                                        (do
                                          (l/dbg :hint "skip offload, file is locked or gone"
                                                 :file-id (str file-id))
                                          {:offloaded 0 :bytes 0 :by-type {} :skipped 1})))))]
        (if (pos? (:skipped summary))
          (do
            (metrics/record-offload-duration cfg :skip (inst-ms (tpoint)))
            summary)
          (do
            (doseq [[type {:keys [rows bytes]}] (:by-type summary)]
              (metrics/record-offload-rows cfg :ok type rows)
              (metrics/record-offload-bytes cfg :ok type bytes))
            (metrics/record-offload-duration cfg :ok (inst-ms (tpoint)))
            summary)))
      (catch Throwable cause
        (metrics/record-offload-duration cfg :error (inst-ms (tpoint)))
        (throw cause)))))

(def schema:offload-file-data-params
  [:map
   [:file-id ::sm/uuid]])

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided")
  (assert (some? (::mtx/metrics params)) "expected metrics to be provided"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :offload-file-data
   ::jobs/schema    schema:offload-file-data-params
   ::jobs/handler
   (fn [_context params]
     (execute-offload-file-data cfg params))
   ::jobs/decoder   (sm/decoder schema:offload-file-data-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:offload-file-data-params)})

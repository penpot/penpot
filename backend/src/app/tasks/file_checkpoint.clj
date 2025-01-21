;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-checkpoint
  "A maintenance task that is responsible of performing a checkpoint on a recently edited file.

  The checkpoint process right now consist on the following task:

  - Check all file-media-object references and create new if some of
    them are missing
  "
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.storage :as sto]
   [app.tasks.file-gc :as fgc]
   [app.util.blob :as blob]
   [integrant.core :as ig]))

(defn- process-file-snapshots!
  [{:keys [::db/conn ::sto/storage] :as cfg} file-id]
  (run! (fn [{:keys [snapshot-id] :as file}]
          (let [file  (bfc/decode-file cfg file)
                file' (bfc/upsert-media-references cfg file)]

            (when (not= file file')
              (cfv/validate-file-schema! file')
              (let [data (blob/encode (:data file'))]
                ;; If file was already offloaded, we touch the underlying storage
                ;; object for properly trigger storage-gc-touched task
                (when (feat.fdata/offloaded? file)
                  (some->> (:data-ref-id file) (sto/touch-object! storage)))

                (db/update! conn :file-change
                            {:data data
                             :data-backend nil
                             :data-ref-id nil}
                            {:id snapshot-id}
                            {::db/return-keys false})))))

        (db/plan cfg [fgc/sql:get-snapshots file-id])))

(defn- process-file!
  [cfg file-id]
  (if-let [file (fgc/get-file cfg file-id)]
    (do
      (->> file
           (bfc/decode-file cfg)
           (bfc/upsert-media-references cfg)
           (cfv/validate-file-schema!)
           (bfc/update-file! cfg))

      (process-file-snapshots! cfg file-id)

      true)

    (do
      (l/dbg :hint "skip" :file-id (str (::fgc/file-id cfg)))
      false)))

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
    (let [cfg     (assoc cfg ::db/rollback (:rollback? props))
          file-id (get props :file-id)]

      (when-not (uuid? file-id)
        (ex/raise :type :internal
                  :code :invalid-props
                  :hint "file is missing or it is not uuid instance"
                  :file-id file-id))

      (try
        (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                          (let [cfg (update cfg ::sto/storage sto/configure conn)]
                            (process-file! cfg file-id))))

        (catch Throwable cause
          (l/err :hint "error file checkpoint task"
                 :file-id (str file-id)
                 :cause cause))))))

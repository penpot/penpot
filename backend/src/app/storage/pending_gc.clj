;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.storage.pending-gc
  "A maintenance task that reclaims storage objects created in 'pending'
  state that were never promoted to 'valid' (e.g. after a crash between
  writing the blob and promoting the row).

  Pending rows are invisible to the normal lifecycle (dedup, gc, reads). This
  task removes the orphaned blob (if any) and the pending row itself, without
  ever iterating the whole physical store."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.storage :as sto]
   [app.storage.impl :as impl]
   [integrant.core :as ig]))

(def ^:private sql:get-pending-sobjects
  "SELECT id, backend
     FROM storage_object
    WHERE status = 'pending'
      AND created_at <= now() - interval '24 hours'
    ORDER BY created_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- get-pending-chunk
  [conn chunk-size]
  (db/exec! conn [sql:get-pending-sobjects chunk-size]))

(def ^:private sql:delete-pending-sobject
  "DELETE FROM storage_object WHERE id = ? AND status = 'pending'")

(def ^:private chunk-size
  100)

(defn- delete-pending-rows!
  "Select, lock and delete a chunk of pending rows in a single transaction.
  Returns the deleted rows or nil when there is nothing left to reclaim."
  [cfg]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                ;; NOTE: db/exec! returns an empty vector when there are no
                ;; rows left; use not-empty to detect it.
                (when-let [chunk (not-empty (get-pending-chunk conn chunk-size))]
                  (doseq [{:keys [id]} chunk]
                    (db/exec-one! conn [sql:delete-pending-sobject id]))
                  chunk))))

(defn- delete-blobs!
  "Best-effort removal of the orphaned blobs. Runs after the pending rows
  have been committed so a failure here never blocks their reclamation."
  [storage rows]
  (doseq [{:keys [id backend]} rows]
    (try
      (-> (impl/resolve-backend storage (keyword backend))
          (impl/del-object {:id id}))
      (catch Throwable cause
        (l/err :hint "error deleting orphaned pending blob"
               :id (str id)
               :backend backend
               :cause cause)))))

(defn- process!
  [{::sto/keys [storage] :as cfg}]
  (loop [total 0]
    (if-let [rows (delete-pending-rows! cfg)]
      (do
        (delete-blobs! storage rows)
        (recur (long (+ total (count rows)))))
      total)))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected valid db pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (let [total (process! cfg)]
      (l/inf :hint "task finished" :total total)
      {:processed total})))

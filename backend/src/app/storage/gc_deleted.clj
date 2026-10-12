;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.storage.gc-deleted
  "A task responsible to permanently delete already marked as deleted
  storage files. The storage objects are practically never marked to
  be deleted directly by the api call.

  The touched-gc is responsible of collecting the usage of the object
  and mark it as deleted. Only the TMP files are are created with
  expiration date in future."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.storage :as sto]
   [app.storage.impl :as impl]
   [integrant.core :as ig]))

(def ^:private sql:lock-sobjects
  "SELECT id FROM storage_object
    WHERE id = ANY(?::uuid[])
      FOR UPDATE
     SKIP LOCKED")

(defn- lock-ids
  "Perform a select before delete for proper object locking and
  prevent concurrent operations and we proceed only with successfully
  locked objects."
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (->> (db/exec! conn [sql:lock-sobjects ids])
         (into #{} (map :id))
         (not-empty))))

(def ^:private sql:delete-sobjects
  "DELETE FROM storage_object
    WHERE id = ANY(?::uuid[])")

(defn- delete-sobjects
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (-> (db/exec-one! conn [sql:delete-sobjects ids])
        (db/get-update-count))))

(defn- delete-in-bulk
  [cfg backend-id ids]
  ;; Each chunk runs in its own transaction, on its own connection: a chunk
  ;; that fails (for example, an object that a foreign key still references) is
  ;; logged and skipped, and it cannot poison the cursor that feeds the rest.
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::sto/storage]}]
                  (when-let [ids (lock-ids conn ids)]
                    (let [total (delete-sobjects conn ids)]
                      (-> (impl/resolve-backend storage backend-id)
                          (impl/del-objects-in-bulk ids))

                      (doseq [id ids]
                        (l/dbg :hint "permanently delete storage object"
                               :id (str id)
                               :backend (name backend-id)))
                      total))))
    (catch Throwable cause
      (l/err :hint "unexpected error on bulk deletion"
             :ids ids
             :cause cause))))


(defn- group-by-backend
  [items]
  (d/group-by (comp keyword :backend) :id #{} items))

(def ^:private sql:get-deleted-sobjects
  "SELECT s.*
     FROM storage_object AS s
    WHERE s.deleted_at IS NOT NULL
      AND s.deleted_at <= ?
    ORDER BY s.deleted_at ASC")

(def ^:private chunk-size
  "Pending objects deleted in each transaction."
  25)

(defn- pending-row
  "Takes the columns the GC needs from a `db/plan` row.

  A plan row is a view over the open result set, valid only while the reduction
  is running, so it cannot be stored for later: this has to happen inside the
  reducing function."
  [row]
  {:id (:id row)
   :backend (:backend row)})

(defn- clean-deleted
  "Walks the pending objects with `db/plan`, a server-side cursor that streams
  the rows instead of materialising them, and deletes them chunk by chunk.

  The rows are read in their own read-only transaction and every chunk of
  `chunk-size` objects is deleted in its own one (see `delete-in-bulk`), so a
  chunk that fails is logged and skipped instead of aborting the whole run, and
  the work of the previous chunks is already committed."
  [cfg]
  (let [xform (comp (map pending-row)
                    (partition-all chunk-size)
                    (mapcat group-by-backend))
        rf    (fn
                ([total] total)
                ([total [backend-id ids]]
                 (+ total (or (delete-in-bulk cfg backend-id ids) 0))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce xform rf 0
                             (db/plan conn [sql:get-deleted-sobjects (ct/now)]))))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage")
  (assert (db/pool? (::db/pool params)) "expect valid storage"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (let [total (clean-deleted cfg)]
      (l/inf :hint "task finished" :total total)
      {:deleted total})))

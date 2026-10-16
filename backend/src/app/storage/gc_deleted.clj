;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.storage :as sto]
   [app.storage.impl :as impl]
   [clojure.set :as set]
   [integrant.core :as ig]))

(def ^:private max-attempts
  "Maximum number of deletion attempts before giving up and accepting
  the orphan blob."
  7)

(def ^:private chunk-size
  "Pending objects deleted in each transaction."
  25)

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

(def ^:private sql:increment-attempts-and-defer
  "UPDATE storage_object
      SET deletion_attempts = deletion_attempts + 1,
          deleted_at = NOW() + INTERVAL '1 day'
    WHERE id = ANY(?::uuid[])")

(defn- increment-attempts-and-defer
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:increment-attempts-and-defer ids])))

(def ^:private sql:delete-give-up
  "DELETE FROM storage_object
    WHERE id = ANY(?::uuid[])
      AND deletion_attempts >= ?")

(defn- delete-give-up
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:delete-give-up ids max-attempts])))

(defn- process-chunk
  "Attempt to delete a chunk of storage objects from a specific backend.

  This function runs inside the chunk's own transaction (see
  `delete-in-bulk`) — it does NOT open its own transaction. The rows are
  locked via FOR UPDATE SKIP LOCKED before deleting.

  Returns the number of successfully deleted objects, or 0 if no rows
  could be locked."
  [conn storage backend-id ids]
  (if-let [locked-ids (lock-ids conn ids)]
    (let [fail-ids (try
                     (-> (impl/resolve-backend storage backend-id)
                         (impl/del-objects-in-bulk locked-ids))
                     (catch Throwable cause
                       (l/err :hint "error on physical deletion, will retry"
                              :ids locked-ids
                              :cause cause)
                       locked-ids))
          ok-ids   (set/difference locked-ids fail-ids)]

      (doseq [id ok-ids]
        (l/dbg :hint "permanently delete storage object"
               :id (str id)
               :backend (name backend-id)))

      (when (seq ok-ids)
        ;; NOTE: rows arrive here with no live references (the mark-time
        ;; scan in gc-touched owns that check, also for the upload-session
        ;; bucket). If a NO ACTION reference still exists, this DELETE
        ;; aborts the chunk loud instead of breaking anyone silently.
        (delete-sobjects conn ok-ids))

      (when (seq fail-ids)
        ;; NOTE: deferred rows keep both their row and their references
        ;; (upload-session mappings included) until the final give-up, so
        ;; a backend outage does not break live sessions.
        (increment-attempts-and-defer conn fail-ids)
        (let [given-up (delete-give-up conn fail-ids)]
          (when (pos? (db/get-update-count given-up))
            (l/wrn :hint "giving up on orphan blob after max attempts"
                   :ids fail-ids
                   :max-attempts max-attempts))))

      (count ok-ids))
    0))

(defn- delete-in-bulk
  [cfg backend-id ids]
  ;; Each chunk runs in its own transaction, on its own connection: a chunk
  ;; that fails (for example, an object that a foreign key still references) is
  ;; logged and skipped, and it cannot poison the cursor that feeds the rest.
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::sto/storage]}]
                  (process-chunk conn storage backend-id ids)))
    (catch Throwable cause
      (l/err :hint "unexpected error on bulk deletion, skipping chunk"
             :ids ids
             :cause cause)
      0)))

(defn- group-by-backend
  [items]
  (d/group-by (comp keyword :backend) :id #{} items))

(def ^:private sql:get-deleted-sobjects
  "SELECT id, backend
     FROM storage_object
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
      AND status = 'valid'
    ORDER BY deleted_at ASC")

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
  the work of the previous chunks is already committed. A physical-deletion
  failure inside a chunk defers its rows one day and gives up after
  `max-attempts` attempts (see `process-chunk`)."
  [cfg]
  (let [xform (comp (map pending-row)
                    (partition-all chunk-size)
                    (mapcat group-by-backend))
        rf    (fn
                ([total] total)
                ([total [backend-id ids]]
                 (let [deleted (or (delete-in-bulk cfg backend-id ids) 0)]
                   ;; Beat the job after every chunk: each chunk commits on its
                   ;; own, so a long sweep neither loses work on a late failure
                   ;; nor outruns the orphan lease.
                   (jobs/heartbeat cfg)
                   (+ total deleted))))]
    (db/tx-run! (assoc cfg ::db/read-only true)
                (fn [{:keys [::db/conn]}]
                  (transduce xform rf 0
                             (db/plan conn [sql:get-deleted-sobjects (ct/now)]))))))

(declare execute-storage-gc-deleted)

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage")
  (assert (db/pool? (::db/pool params)) "expect valid db pool"))

(def schema:storage-gc-deleted-params
  "Params map (no params needed; cfg-provided config only)."
  [:map {:closed true}])

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :storage-gc-deleted
   ::jobs/schema    schema:storage-gc-deleted-params
   ::jobs/handler
   (fn [_context params]
     (execute-storage-gc-deleted cfg params))
   ::jobs/decoder   (sm/decoder schema:storage-gc-deleted-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:storage-gc-deleted-params)})

(defn execute-storage-gc-deleted
  "Plain job handler: clean the marked-deleted storage objects."
  ([cfg] (execute-storage-gc-deleted cfg {}))
  ([cfg _params]
   ;; NOTE: no outer transaction here on purpose — clean-deleted
   ;; commits each chunk in its own transaction, so a late failure
   ;; only loses the in-flight chunk.
   (jobs/heartbeat cfg)
   (let [total (clean-deleted cfg)]
     (l/inf :hint "task finished" :total total)
     {:deleted total})))

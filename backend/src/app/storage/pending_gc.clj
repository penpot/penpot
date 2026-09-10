;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
  "SELECT id, backend,
          coalesce(metadata->>'~:storage-target', 'default') as target
     FROM storage_object
    WHERE status = 'pending'
      AND created_at <= now() - interval '24 hours'
      AND (deleted_at IS NULL OR deleted_at <= now())
    ORDER BY created_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- get-pending-chunk
  [conn chunk-size]
  (db/exec! conn [sql:get-pending-sobjects chunk-size]))

(def ^:private sql:delete-pending-sobject
  "DELETE FROM storage_object WHERE id = ? AND status = 'pending'")

(defn- log-refusal!
  "Indirection over the error log so tests can capture the refusal payload."
  [backend-id target ids]
  (l/err :hint "storage target is not configured, deletion refused"
         :backend (name backend-id)
         :target target
         :ids (mapv str ids)))

(def ^:private sql:park-unresolvable
  "UPDATE storage_object
      SET deleted_at = NOW() + INTERVAL '1 day'
    WHERE id = ANY(?::uuid[])
      AND status = 'pending'")

(defn- park-unresolvable!
  "Refuses to delete rows whose target id is not configured: logs the
  misconfiguration and pushes `deleted_at` forward so the rows are excluded
  from the next selection without being deleted."
  [conn backend-id target ids]
  (log-refusal! backend-id target ids)
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:park-unresolvable ids])))

(def ^:private chunk-size
  100)

(defn- group-by-route
  [rows]
  (group-by (fn [{:keys [backend target]}]
              [(keyword backend) target])
            rows))

(defn- delete-pending-rows!
  "Select, lock and delete a chunk of pending rows in a single transaction.
  Rows whose target id is not configured are never deleted: they are parked
  (error logged, `deleted_at` pushed forward) so the loop terminates and the
  rows survive.

  Returns a map `{:deleted rows :parked count}`, or nil when there is nothing
  left to reclaim."
  [cfg]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn ::sto/storage]}]
                ;; NOTE: db/exec! returns an empty vector when there are no
                ;; rows left; use not-empty to detect it.
                (when-let [chunk (not-empty (get-pending-chunk conn chunk-size))]
                  (reduce-kv
                   (fn [acc [backend-id target] rows]
                     (if (sto/target-resolvable? storage backend-id target)
                       (do
                         (doseq [{:keys [id]} rows]
                           (db/exec-one! conn [sql:delete-pending-sobject id]))
                         (update acc :deleted into rows))
                       (do
                         (park-unresolvable! conn backend-id target (mapv :id rows))
                         (update acc :parked + (count rows)))))
                   {:deleted [] :parked 0}
                   (group-by-route chunk))))))

(defn- delete-blobs!
  "Best-effort removal of the orphaned blobs. Runs after the pending rows
  have been committed so a failure here never blocks their reclamation.

  The `:storage-target` metadata is load-bearing: the S3 backend reads it
  (`s3/target-id`) to resolve the bucket/prefix/client for `del-object`."
  [storage rows]
  (doseq [{:keys [id backend target]} rows]
    (try
      (-> (impl/resolve-backend storage (keyword backend))
          (impl/del-object (with-meta {:id id} {:storage-target target})))
      (catch Throwable cause
        (l/err :hint "error deleting orphaned pending blob"
               :id (str id)
               :backend backend
               :cause cause)))))

(defn- process!
  [{::sto/keys [storage] :as cfg}]
  (loop [total-deleted 0
         total-parked  0]
    (if-let [result (delete-pending-rows! cfg)]
      (let [removed (:deleted result)
            parked  (:parked result)]
        (delete-blobs! storage removed)
        (recur (long (+ total-deleted (count removed)))
               (long (+ total-parked parked))))
      {:processed total-deleted
       :parked    total-parked})))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected valid db pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (let [{:keys [processed parked]} (process! cfg)]
      (l/inf :hint "task finished" :total processed :parked parked)
      {:processed processed
       :parked    parked})))

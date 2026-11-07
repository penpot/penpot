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
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.storage.impl :as impl]
   [integrant.core :as ig]))

(def ^:private sql:delete-sobjects
  "DELETE FROM storage_object
    WHERE id = ANY(?::uuid[])")

(defn- delete-by-backend
  "Deletes the locked objects of one backend group: the database rows and
  their backend content. Runs inside the caller's transaction. Returns the
  number of deleted rows; reporting it (metrics) is the caller's job, once
  the transaction has committed."
  [{:keys [::db/conn ::sto/storage]} backend-id ids]
  (let [ids-array (db/create-array conn "uuid" ids)
        total     (-> (db/exec-one! conn [sql:delete-sobjects ids-array])
                      (db/get-update-count))]
    (-> (impl/resolve-backend storage backend-id)
        (impl/del-objects-in-bulk ids))

    (doseq [id ids]
      (l/dbg :hint "permanently delete storage object"
             :id (str id)
             :backend (name backend-id)))
    total))

(defn- group-by-backend
  [rows]
  (d/group-by (comp keyword :backend) :id #{} rows))

(defn- process-chunk-deletion
  "Deletes the locked rows of one chunk, grouped by backend. Runs inside the
  caller's transaction: any failure here aborts it, so the whole chunk is
  rolled back (nothing is deleted and the locks are released) and skipped by
  the caller. Returns a map of backend id to deleted row count."
  [tx rows]
  (->> (group-by-backend rows)
       (map (fn [[backend-id ids]]
              [backend-id (delete-by-backend tx backend-id ids)]))
       (into {})))

(def ^:private sql:get-deleted-sobjects
  "SELECT s.id, s.backend, s.deleted_at
     FROM storage_object AS s
    WHERE s.deleted_at IS NOT NULL
      AND s.deleted_at <= ?
      AND s.status = 'valid'
      AND (s.deleted_at, s.id) > (?, ?)
    ORDER BY s.deleted_at ASC, s.id ASC
    LIMIT ?
      FOR UPDATE SKIP LOCKED")

(def ^:private sql:get-first-deleted-sobjects
  "SELECT s.id, s.backend, s.deleted_at
     FROM storage_object AS s
    WHERE s.deleted_at IS NOT NULL
      AND s.deleted_at <= ?
      AND s.status = 'valid'
    ORDER BY s.deleted_at ASC, s.id ASC
    LIMIT ?
      FOR UPDATE SKIP LOCKED")

(def ^:private chunk-size
  "Pending objects read, locked and deleted in each transaction."
  25)

(def ^:private throttle-every
  "Deleted objects between throttle pauses."
  5000)

(def ^:private throttle-ms
  "Pause applied every `throttle-every` deleted objects, letting the
  database (and its replicas) breathe on huge backlogs."
  5000)

(defn- throttle
  "Sleeps `throttle-ms` when the deleted count crosses a `throttle-every`
  boundary. Runs between chunk transactions, never holding one open."
  [prev-total new-total]
  (when (> (quot new-total throttle-every)
           (quot prev-total throttle-every))
    (l/inf :hint "throttling storage gc"
           :deleted new-total
           :pause-ms throttle-ms)
    (Thread/sleep (long throttle-ms))))

(defn- fetch-and-lock-chunk
  "Reads and locks one chunk of pending objects in `(deleted_at, id)` order.
  Objects already locked by another process are skipped, so two concurrent
  runs never take the same chunk. Runs inside the caller's transaction."
  [conn last-deleted-at last-id]
  (let [[sql params] (if (some? last-deleted-at)
                       [sql:get-deleted-sobjects
                        [(ct/now) last-deleted-at last-id chunk-size]]
                       [sql:get-first-deleted-sobjects
                        [(ct/now) chunk-size]])]
    (db/exec! conn (into [sql] params))))

(defn- clean-deleted
  "Processes the pending objects chunk by chunk with keyset pagination: each
  chunk is one short transaction that reads and locks the next `chunk-size`
  objects after the `(deleted_at, id)` cursor and deletes them (database rows
  and backend content). Only `valid` objects are read: pending ones (still
  being written, see the status lifecycle) stay untouched even when already
  marked as deleted.

  A chunk that fails aborts its transaction: nothing of the chunk is deleted,
  its locks are released, and it is logged and skipped. The cursor advances
  to the `(deleted_at, id)` of the last row of each read chunk regardless of
  the deletion result, so a failed chunk is not visited again in the same
  run. There is no long-lived read transaction: each transaction lives only
  as long as one chunk, letting the vacuum proceed between chunks. Rows
  locked by a concurrent run are skipped and deferred to the next run; a
  chunk that fails after a partial backend delete self-heals on the next
  run (physical deletes are idempotent). Every `throttle-every` deleted
  objects the run pauses `throttle-ms` (see `throttle`)."
  [{:keys [::mtx/metrics] :as cfg}]
  (loop [total           0
         last-deleted-at nil
         last-id         nil]

    (let [[rows counts]
          (db/tx-run! cfg
                      (fn [{:keys [::db/conn] :as tx}]
                        (let [rows (fetch-and-lock-chunk conn last-deleted-at last-id)]
                          (try
                            [rows (process-chunk-deletion tx rows)]
                            (catch Throwable cause
                              ;; Explicit rollback, then return normally (do
                              ;; not rethrow): the caller needs `rows` to
                              ;; advance the keyset cursor past the failed
                              ;; chunk. The commit that `tx-run!` performs
                              ;; afterwards is a JDBC no-op on the already
                              ;; rolled-back connection.
                              (db/rollback! conn)
                              (l/err :hint "unexpected error on chunk deletion"
                                     :ids (map :id rows)
                                     :cause cause)
                              [rows {}])))))]

      ;; Reported only after the chunk commits: a rolled-back chunk leaves
      ;; an empty count map, so retried rows are never counted twice.
      (doseq [[backend-id n] counts]
        (mtx/run! metrics
                  :id :storage-gc-deleted-objects
                  :inc n
                  :labels [(name backend-id)]))

      ;; Beat the job after every chunk: each chunk commits on its own, so a
      ;; long sweep neither loses work on a late failure nor outruns the
      ;; orphan lease.
      (jobs/heartbeat cfg)

      (if-let [cursor (peek rows)]
        (let [new-total (+ total (reduce + 0 (vals counts)))]
          (throttle total new-total)
          (recur new-total
                 (:deleted-at cursor)
                 (:id cursor)))
        total))))

(declare execute-storage-gc-deleted)

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage")
  (assert (db/pool? (::db/pool params)) "expect valid db pool")
  (assert (mtx/metrics? (::mtx/metrics params)) "expect valid metrics instance"))

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

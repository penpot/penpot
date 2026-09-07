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
   [app.common.time :as ct]
   [app.db :as db]
   [app.storage :as sto]
   [app.storage.impl :as impl]
   [clojure.set :as set]
   [integrant.core :as ig]))

(def ^:private max-attempts
  "Maximum number of deletion attempts before giving up and accepting
  the orphan blob."
  7)

(def ^:private chunk-size
  "Number of rows to process per transaction."
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

(defn- delete-sobjects!
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (-> (db/exec-one! conn [sql:delete-sobjects ids])
        (db/get-update-count))))

(def ^:private sql:increment-attempts-and-defer
  "UPDATE storage_object
      SET deletion_attempts = deletion_attempts + 1,
          deleted_at = NOW() + INTERVAL '1 day'
    WHERE id = ANY(?::uuid[])")

(defn- increment-attempts-and-defer!
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:increment-attempts-and-defer ids])))

(def ^:private sql:delete-give-up
  "DELETE FROM storage_object
    WHERE id = ANY(?::uuid[])
      AND deletion_attempts >= ?")

(defn- delete-give-up!
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:delete-give-up ids max-attempts])))

(defn- process-chunk
  "Attempt to delete a chunk of storage objects from a specific backend.

  This function runs inside the caller's transaction (clean-deleted!) —
  it does NOT open its own transaction. The caller is responsible for
  ensuring the rows are locked via FOR UPDATE SKIP LOCKED before calling.

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
        (delete-sobjects! conn ok-ids))

      (when (seq fail-ids)
        (increment-attempts-and-defer! conn fail-ids)
        (let [given-up (delete-give-up! conn fail-ids)]
          (when (pos? (db/get-update-count given-up))
            (l/wrn :hint "giving up on orphan blob after max attempts"
                   :ids fail-ids
                   :max-attempts max-attempts))))

      (count ok-ids))
    0))

(defn- group-by-backend
  [items]
  (d/group-by (comp keyword :backend) :id #{} items))

(def ^:private sql:get-deleted-chunk
  "SELECT id, backend
     FROM storage_object
    WHERE deleted_at IS NOT NULL
      AND deleted_at <= ?
      AND status = 'valid'
    ORDER BY deleted_at ASC
    LIMIT ?
      FOR UPDATE
     SKIP LOCKED")

(defn- get-deleted-chunk
  [conn size]
  (db/exec! conn [sql:get-deleted-chunk (ct/now) size]))

(defn- clean-deleted!
  [cfg]
  (loop [total 0]
    (let [deleted (db/tx-run! cfg
                              (fn [{:keys [::db/conn ::sto/storage]}]
                                (let [chunk (get-deleted-chunk conn chunk-size)]
                                  (when (seq chunk)
                                    (let [by-backend (group-by-backend chunk)]
                                      (reduce-kv (fn [acc backend-id ids]
                                                   (+ acc (process-chunk conn storage backend-id ids)))
                                                 0
                                                 by-backend))))))]
      (if deleted
        (recur (+ total deleted))
        total))))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage")
  (assert (db/pool? (::db/pool params)) "expect valid db pool"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (let [total (clean-deleted! cfg)]
      (l/inf :hint "task finished" :total total)
      {:deleted total})))

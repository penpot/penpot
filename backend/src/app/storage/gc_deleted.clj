;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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

(defn- delete-sobjects!
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (-> (db/exec-one! conn [sql:delete-sobjects ids])
        (db/get-update-count))))

(defn- delete-in-bulk!
  [cfg backend-id ids]
  ;; We run the deletion on a separate transaction. This is
  ;; because if some exception is raised inside procesing
  ;; one chunk, it does not affects the rest of the chunks.
  (try
    (db/tx-run! cfg
                (fn [{:keys [::db/conn ::sto/storage]}]
                  (when-let [ids (lock-ids conn ids)]
                    (let [total (delete-sobjects! conn ids)]
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

(defn- get-buckets
  [conn]
  (let [now (ct/now)]
    (sequence
     (comp (partition-all 25)
           (mapcat group-by-backend))
     (db/cursor conn [sql:get-deleted-sobjects now]))))

(defn- clean-deleted!
  [{:keys [::db/conn] :as cfg}]
  (reduce (fn [total [backend-id ids]]
            (let [deleted (delete-in-bulk! cfg backend-id ids)]
              (+ total (or deleted 0))))
          0
          (get-buckets conn)))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (sto/valid-storage? (::sto/storage params)) "expect valid storage")
  (assert (db/pool? (::db/pool params)) "expect valid storage"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (db/tx-run! cfg (fn [cfg]
                      (let [total (clean-deleted! cfg)]
                        (l/inf :hint "task finished" :total total)
                        {:deleted total})))))

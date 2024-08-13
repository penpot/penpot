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
   [app.db :as db]
   [app.storage :as-alias sto]
   [app.storage.impl :as impl]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
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
  "SELECT s.* FROM storage_object AS s
    WHERE s.deleted_at IS NOT NULL
      AND s.deleted_at < now() - ?::interval
    ORDER BY s.deleted_at ASC")

(defn- get-buckets
  [conn min-age]
  (let [age (db/interval min-age)]
    (sequence
     (comp (partition-all 25)
           (mapcat group-by-backend))
     (db/cursor conn [sql:get-deleted-sobjects age]))))


(defn- clean-deleted!
  [{:keys [::db/conn ::min-age] :as cfg}]
  (reduce (fn [total [backend-id ids]]
            (let [deleted (delete-in-bulk! cfg backend-id ids)]
              (+ total (or deleted 0))))
          0
          (get-buckets conn min-age)))


(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::sto/storage ::db/pool]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age (dt/duration {:hours 2})))

(defmethod ig/init-key ::handler
  [_ {:keys [::min-age] :as cfg}]
  (fn [{:keys [props] :as task}]
    (let [min-age (dt/duration (or (:min-age props) min-age))]
      (db/tx-run! cfg (fn [cfg]
                        (let [cfg   (assoc cfg ::min-age min-age)
                              total (clean-deleted! cfg)]

                          (l/inf :hint "task finished"
                                 :min-age (dt/format-duration min-age)
                                 :total total)

                          {:deleted total}))))))



;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage
  "Objects storage abstraction layer."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.storage.db :as sdb]
   [app.storage.fs :as sfs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as ss3]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [integrant.core :as ig]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::s3 ::ss3/backend)
(s/def ::fs ::sfs/backend)
(s/def ::db ::sdb/backend)

(s/def ::backends
  (s/map-of ::us/keyword
            (s/nilable
             (s/or :s3 ::ss3/backend
                   :fs ::sfs/backend
                   :db ::sdb/backend))))

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req-un [::wrk/executor ::db/pool ::backends]))

(defmethod ig/prep-key ::storage
  [_ {:keys [backends] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc :backends (d/without-nils backends))))

(defmethod ig/init-key ::storage
  [_ {:keys [backends] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc :backends (d/without-nils backends))))

(s/def ::storage
  (s/keys :req-un [::backends ::wrk/executor ::db/pool]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord StorageObject [id size created-at expired-at backend])

(defn storage-object?
  [v]
  (instance? StorageObject v))

(def ^:private
  sql:insert-storage-object
  "insert into storage_object (id, size, backend, metadata)
   values (?, ?, ?, ?::jsonb)
   returning *")

(def ^:private
  sql:insert-storage-object-with-expiration
  "insert into storage_object (id, size, backend, metadata, deleted_at)
   values (?, ?, ?, ?::jsonb, ?)
   returning *")

(defn- insert-object
  [conn id size backend mdata expiration]
  (if expiration
    (db/exec-one! conn [sql:insert-storage-object-with-expiration id size backend mdata expiration])
    (db/exec-one! conn [sql:insert-storage-object id size backend mdata])))

(defn- create-database-object
  [{:keys [conn backend]} {:keys [content] :as object}]
  (if (instance? StorageObject object)
    ;; If we in this condition branch, this means we come from the
    ;; clone-object, so we just need to clone it with a new backend.
    (let [id     (uuid/random)
          mdata  (meta object)
          result (insert-object conn
                                id
                                (:size object)
                                (name backend)
                                (db/tjson mdata)
                                (:expired-at object))]
      (assoc object
             :id (:id result)
             :backend backend
             :created-at (:created-at result)))
    (let [id     (uuid/random)
          mdata  (dissoc object :content :expired-at)
          result (insert-object conn
                                id
                                (count content)
                                (name backend)
                                (db/tjson mdata)
                                (:expired-at object))]
      (StorageObject. (:id result)
                      (:size result)
                      (:created-at result)
                      (:deleted-at result)
                      backend
                      mdata
                      nil))))

(def ^:private sql:retrieve-storage-object
  "select * from storage_object where id = ? and (deleted_at is null or deleted_at > now())")

(defn row->storage-object [res]
  (let [mdata (some-> (:metadata res) (db/decode-transit-pgobject))]
    (StorageObject. (:id res)
                    (:size res)
                    (:created-at res)
                    (:deleted-at res)
                    (keyword (:backend res))
                    mdata
                    nil)))

(defn- retrieve-database-object
  [{:keys [conn] :as storage} id]
  (when-let [res (db/exec-one! conn [sql:retrieve-storage-object id])]
    (row->storage-object res)))

(def sql:delete-storage-object
  "update storage_object set deleted_at=now() where id=?")

(defn- delete-database-object
  [{:keys [conn] :as storage} id]
  (let [result (db/exec-one! conn [sql:delete-storage-object id])]
    (pos? (:next.jdbc/update-count result))))

(defn- register-recheck
  [{:keys [pool] :as storage} backend id]
  (db/insert! pool :storage-pending {:id id :backend (name backend)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn object->relative-path
  [{:keys [id] :as obj}]
  (impl/id->path id))

(defn file-url->path
  [url]
  (fs/path (java.net.URI. (str url))))

(defn content
  ([data] (impl/content data nil))
  ([data size] (impl/content data size)))

(defn get-object
  [{:keys [conn pool] :as storage} id]
  (us/assert ::storage storage)
  (-> (assoc storage :conn (or conn pool))
      (retrieve-database-object id)))

(defn put-object
  "Creates a new object with the provided content."
  [{:keys [pool conn backend executor] :as storage} {:keys [content] :as params}]
  (us/assert ::storage storage)
  (us/assert impl/content? content)
  (let [storage (assoc storage :conn (or conn pool))
        object  (create-database-object storage params)]

    ;; Schedule to execute in background; in an other transaction and
    ;; register the currently created storage object id for a later
    ;; recheck.
    (px/run! executor #(register-recheck storage backend (:id object)))

    ;; Store the data finally on the underlying storage subsystem.
    (-> (impl/resolve-backend storage backend)
        (impl/put-object object content))

    object))

(defn clone-object
  "Creates a clone of the provided object using backend based efficient
  method. Always clones objects to the configured default."
  [{:keys [pool conn] :as storage} object]
  (us/assert ::storage storage)
  (let [storage (assoc storage :conn (or conn pool))
        object* (create-database-object storage object)]
    (if (= (:backend object) (:backend storage))
      ;; if the source and destination backends are the same, we
      ;; proceed to use the fast path with specific copy
      ;; implementation on backend.
      (-> (impl/resolve-backend storage (:backend storage))
          (impl/copy-object object object*))

      ;; if the source and destination backends are different, we just
      ;; need to obtain the streams and proceed full copy of the data
      (with-open [is (-> (impl/resolve-backend storage (:backend object))
                         (impl/get-object-data object))]
        (-> (impl/resolve-backend storage (:backend storage))
            (impl/put-object object* (impl/content is (:size object))))))
    object*))

(defn get-object-data
  "Return an input stream instance of the object content."
  [{:keys [pool conn] :as storage} object]
  (us/assert ::storage storage)
  (when (or (nil? (:expired-at object))
            (dt/is-after? (:expired-at object) (dt/now)))
    (-> (assoc storage :conn (or conn pool))
        (impl/resolve-backend (:backend object))
        (impl/get-object-data object))))

(defn get-object-bytes
  "Returns a byte array of object content."
  [{:keys [pool conn] :as storage} object]
  (us/assert ::storage storage)
  (when (or (nil? (:expired-at object))
            (dt/is-after? (:expired-at object) (dt/now)))
    (-> (assoc storage :conn (or conn pool))
        (impl/resolve-backend (:backend object))
        (impl/get-object-bytes object))))

(defn get-object-url
  ([storage object]
   (get-object-url storage object nil))
  ([{:keys [conn pool] :as storage} object options]
   (us/assert ::storage storage)
   (when (or (nil? (:expired-at object))
             (dt/is-after? (:expired-at object) (dt/now)))
     (-> (assoc storage :conn (or conn pool))
         (impl/resolve-backend (:backend object))
         (impl/get-object-url object options)))))

(defn get-object-path
  "Get the Path to the object. Only works with `:fs` type of
  storages."
  [storage object]
  (let [backend (impl/resolve-backend storage (:backend object))]
    (when (not= :fs (:type backend))
      (ex/raise :type :internal
                :code :operation-not-allowed
                :hint "get-object-path only works with fs type backends"))
    (when (or (nil? (:expired-at object))
              (dt/is-after? (:expired-at object) (dt/now)))
      (-> (impl/get-object-url backend object nil)
          (file-url->path)))))

(defn del-object
  [{:keys [conn pool] :as storage} id-or-obj]
  (us/assert ::storage storage)
  (-> (assoc storage :conn (or conn pool))
      (delete-database-object (if (uuid? id-or-obj) id-or-obj (:id id-or-obj)))))

(d/export impl/resolve-backend)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Permanently delete objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A task responsible to permanently delete already marked as deleted
;; storage files.

(declare sql:retrieve-deleted-objects)

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::gc-deleted-task [_]
  (s/keys :req-un [::storage ::db/pool ::min-age]))

(defmethod ig/init-key ::gc-deleted-task
  [_ {:keys [pool storage min-age] :as cfg}]
  (letfn [(group-by-backend [rows]
            (let [conj (fnil conj [])]
              [(reduce (fn [acc {:keys [id backend]}]
                         (update acc (keyword backend) conj id))
                       {}
                       rows)
               (count rows)]))

          (retrieve-deleted-objects [conn]
            (let [min-age (db/interval min-age)
                  rows    (db/exec! conn [sql:retrieve-deleted-objects min-age])]
              (some-> (seq rows) (group-by-backend))))

          (delete-in-bulk [conn [backend ids]]
            (let [backend (impl/resolve-backend storage backend)
                  backend (assoc backend :conn conn)]
              (impl/del-objects-in-bulk backend ids)))]

    (fn [_]
      (db/with-atomic [conn pool]
        (loop [n 0]
          (if-let [[groups total] (retrieve-deleted-objects conn)]
            (do
              (run! (partial delete-in-bulk conn) groups)
              (recur (+ n ^long total)))
            (do
              (l/info :task "gc-deleted"
                      :action "permanently delete items"
                      :count n)
              {:deleted n})))))))

(def sql:retrieve-deleted-objects
  "with items_part as (
     select s.id
       from storage_object as s
      where s.deleted_at is not null
        and s.deleted_at < (now() - ?::interval)
      order by s.deleted_at
      limit 100
   )
   delete from storage_object
    where id in (select id from items_part)
   returning *;")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Analyze touched objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This task is part of the garbage collection of storage objects and
;; is responsible on analyzing the touched objects and mark them for deletion
;; if corresponds.
;;
;; When file_media_object is deleted, the depending storage_object are
;; marked as touched. This means that some files that depend on a
;; concrete storage_object are no longer exists and maybe this
;; storage_object is no longer necessary and can be eligible for
;; elimination. This task periodically analyzes touched objects and
;; mark them as freeze (means that has other references and the object
;; is still valid) or deleted (no more references to this object so is
;; ready to be deleted).

(declare sql:retrieve-touched-objects)

(defmethod ig/pre-init-spec ::gc-touched-task [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::gc-touched-task
  [_ {:keys [pool] :as cfg}]
  (letfn [(group-results [rows]
            (let [conj (fnil conj [])]
              (reduce (fn [acc {:keys [id nrefs]}]
                        (if (pos? nrefs)
                          (update acc :to-freeze conj id)
                          (update acc :to-delete conj id)))
                      {}
                      rows)))

          (retrieve-touched [conn]
            (let [rows (db/exec! conn [sql:retrieve-touched-objects])]
              (some-> (seq rows) (group-results))))

          (mark-delete-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set deleted_at=now(), touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" (into-array java.util.UUID ids))]))

          (mark-freeze-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" (into-array java.util.UUID ids))]))]

    (fn [_]
      (db/with-atomic [conn pool]
        (loop [cntf 0
               cntd 0]
          (if-let [{:keys [to-delete to-freeze]} (retrieve-touched conn)]
            (do
              (when (seq to-delete) (mark-delete-in-bulk conn to-delete))
              (when (seq to-freeze) (mark-freeze-in-bulk conn to-freeze))
              (recur (+ cntf (count to-freeze))
                     (+ cntd (count to-delete))))
            (do
              (l/info :task "gc-touched"
                      :action "mark freeze"
                      :count cntf)
              (l/info :task "gc-touched"
                      :action "mark for deletion"
                      :count cntd)
              {:freeze cntf :delete cntd})))))))

(def sql:retrieve-touched-objects
  "select so.id,
          ((select count(*) from file_media_object where media_id = so.id) +
           (select count(*) from file_media_object where thumbnail_id = so.id)) as nrefs
     from storage_object as so
    where so.touched_at is not null
    order by so.touched_at
    limit 100;")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recheck Stalled Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Because the physical storage (filesystem, s3, ... except db) is not
;; transactional, in some situations we can found physical object
;; leakage. That situations happens when the transaction that writes
;; the file aborts, leaving the file written to the underlying storage
;; but the reference on the database is lost with the rollback.
;;
;; For this situations we need to write a "log" of inserted files that
;; are checked in some time in future. If physical file exists but the
;; database refence does not exists means that leaked file is found
;; and is immediately deleted. The responsibility of this task is
;; check that write log for possible leaked files.

(def recheck-min-age (dt/duration {:hours 1}))

(declare sql:retrieve-pending-to-recheck)
(declare sql:exists-storage-object)

(defmethod ig/pre-init-spec ::recheck-task [_]
  (s/keys :req-un [::storage ::db/pool]))

(defmethod ig/init-key ::recheck-task
  [_ {:keys [pool storage] :as cfg}]
  (letfn [(group-results [rows]
            (let [conj (fnil conj [])]
              (reduce (fn [acc {:keys [id exist] :as row}]
                        (cond-> (update acc :all conj id)
                          (false? exist)
                          (update :to-delete conj (dissoc row :exist))))
                      {}
                      rows)))

          (group-by-backend [rows]
            (let [conj (fnil conj [])]
              (reduce (fn [acc {:keys [id backend]}]
                        (update acc (keyword backend) conj id))
                      {}
                      rows)))

          (retrieve-pending [conn]
            (let [rows (db/exec! conn [sql:retrieve-pending-to-recheck (db/interval recheck-min-age)])]
              (some-> (seq rows) (group-results))))

          (delete-group [conn [backend ids]]
            (let [backend (impl/resolve-backend storage backend)
                  backend (assoc backend :conn conn)]
              (impl/del-objects-in-bulk backend ids)))

          (delete-all [conn ids]
            (let [ids (db/create-array conn "uuid" (into-array java.util.UUID ids))]
              (db/exec-one! conn ["delete from storage_pending where id = ANY(?)" ids])))]

    (fn [_]
      (db/with-atomic [conn pool]
        (loop [n 0 d 0]
          (if-let [{:keys [all to-delete]} (retrieve-pending conn)]
            (let [groups (group-by-backend to-delete)]
              (run! (partial delete-group conn) groups)
              (delete-all conn all)
              (recur (+ n (count all))
                     (+ d (count to-delete))))
            (do
              (l/info :task "recheck"
                      :action "recheck items"
                      :processed n
                      :deleted n)
              {:processed n :deleted d})))))))

(def sql:retrieve-pending-to-recheck
  "select sp.id,
          sp.backend,
          sp.created_at,
          (case when count(so.id) > 0 then true
                else false
            end) as exist
     from storage_pending as sp
     left join storage_object as so
            on (so.id = sp.id)
    where sp.created_at < now() - ?::interval
    group by 1,2,3
    order by sp.created_at asc
    limit 100")

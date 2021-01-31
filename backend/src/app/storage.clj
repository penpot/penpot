;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.storage
  "File Storage abstraction layer."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.storage.db :as sdb]
   [app.storage.fs :as sfs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as ss3]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]
   [promesa.exec :as px])
  (:import
   java.io.InputStream))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::backend ::us/keyword)

(s/def ::s3 ::ss3/backend)
(s/def ::fs ::sfs/backend)
(s/def ::db ::sdb/backend)

(s/def ::backends
  (s/keys :opt-un [::s3 ::fs ::db]))

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req-un [::backend ::wrk/executor ::db/pool ::backends]))

(defmethod ig/prep-key ::storage
  [_ {:keys [backends] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc :backends (d/without-nils backends))))

(defmethod ig/init-key ::storage
  [_ cfg]
  cfg)

(s/def ::storage
  (s/keys :req-un [::backends ::wrk/executor ::db/pool ::backend]))

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

(declare resolve-backend)

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
    (-> (resolve-backend storage backend)
        (impl/put-object object content))

    object))

(defn clone-object
  "Creates a clone of the provided object using backend basded efficient
  method. Always clones objects to the configured default."
  [{:keys [pool conn executor] :as storage} object]
  (us/assert ::storage storage)
  (let [storage (assoc storage :conn (or conn pool))
        object* (create-database-object storage object)]
    (if (= (:backend object) (:backend storage))
      ;; if the source and destination backends are the same, we
      ;; proceed to use the fast path with specific copy
      ;; implementation on backend.
      (-> (resolve-backend storage (:backend storage))
          (impl/copy-object object object*))

      ;; if the source and destination backends are different, we just
      ;; need to obtain the streams and proceed full copy of the data
      (with-open [^InputStream input
                  (-> (resolve-backend storage (:backend object))
                      (impl/get-object-data object))]
        (-> (resolve-backend storage (:backend storage))
            (impl/put-object object* (impl/content input (:size object))))))

    object*))

(defn get-object-data
  [{:keys [pool conn] :as storage} object]
  (us/assert ::storage storage)
  (when (or (nil? (:expired-at object))
            (dt/is-after? (:expired-at object) (dt/now)))
    (-> (assoc storage :conn (or conn pool))
        (resolve-backend (:backend object))
        (impl/get-object-data object))))

(defn get-object-url
  ([storage object]
   (get-object-url storage object nil))
  ([{:keys [conn pool] :as storage} object options]
   (us/assert ::storage storage)
   (when (or (nil? (:expired-at object))
             (dt/is-after? (:expired-at object) (dt/now)))
     (-> (assoc storage :conn (or conn pool))
         (resolve-backend (:backend object))
         (impl/get-object-url object options)))))

(defn get-object-path
  "Get the Path to the object. Only works with `:fs` type of
  storages."
  [{:keys [backend conn path] :as storage} object]
  (let [backend (resolve-backend storage (:backend object))]
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

;; --- impl

(defn resolve-backend
  [{:keys [conn pool] :as storage} backend-id]
  (let [backend (get-in storage [:backends backend-id])]
    (when-not backend
      (ex/raise :type :internal
                :code :backend-not-configured
                :hint (str/fmt "backend '%s' not configured" backend-id)))
    (assoc backend :conn (or conn pool))))

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
  (letfn [(retrieve-deleted-objects [conn]
            (let [min-age (db/interval min-age)
                  result  (db/exec! conn [sql:retrieve-deleted-objects min-age])]
              (when (seq result)
                (as-> (group-by (comp keyword :backend) result) $
                  (reduce-kv #(assoc %1 %2 (map :id %3)) $ $)))))

          (delete-in-bulk [conn backend ids]
            (let [backend (resolve-backend storage backend)
                  backend (assoc backend :conn conn)]
              (impl/del-objects-in-bulk backend ids)))]

    (fn [task]
      (db/with-atomic [conn pool]
        (loop [groups (retrieve-deleted-objects conn)]
          (when groups
            (doseq [[sid objects] groups]
              (delete-in-bulk conn sid objects))
            (recur (retrieve-deleted-objects conn))))))))

(def sql:retrieve-deleted-objects
  "with items_part as (
     select s.id
       from storage_object as s
      where s.deleted_at is not null
        and s.deleted_at < (now() - ?::interval)
      order by s.deleted_at
      limit 500
   )
   delete from storage_object
    where id in (select id from items_part)
   returning *;")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Analize touched objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This task is part of the garbage collection of storage objects and
;; is responsible on analizing the touched objects and mark them for deletion
;; if corresponds.
;;
;; When file_media_object is deleted, the depending storage_object are
;; marked as touched. This means that some files that depend on a
;; concrete storage_object are no longer exists and maybe this
;; storage_object is no longer necessary and can be ellegible for
;; elimination. This task peridically analizes touched objects and
;; mark them as freeze (means that has other references and the object
;; is still valid) or deleted (no more references to this object so is
;; ready to be deleted).

(declare sql:retrieve-touched-objects)

(defmethod ig/pre-init-spec ::gc-touched-task [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::gc-touched-task
  [_ {:keys [pool] :as cfg}]
  (letfn [(retrieve-touched-objects [conn]
            (seq (db/exec! conn [sql:retrieve-touched-objects])))

          (group-resuls [rows]
            (let [conj (fnil conj [])]
              (reduce (fn [acc {:keys [id nrefs]}]
                        (if (pos? nrefs)
                          (update acc :to-freeze conj id)
                          (update acc :to-delete conj id)))
                      {}
                      rows)))

          (mark-delete-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set deleted_at=now(), touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" (into-array java.util.UUID ids))]))

          (mark-freeze-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" (into-array java.util.UUID ids))]))]

    (fn [task]
      (db/with-atomic [conn pool]
        (loop []
          (when-let [touched (retrieve-touched-objects conn)]
            (let [{:keys [to-delete to-freeze]} (group-resuls touched)]
              (when (seq to-delete)
                (mark-delete-in-bulk conn to-delete))
              (when (seq to-freeze)
                (mark-freeze-in-bulk conn to-freeze))
              (Thread/sleep 100)
              (recur))))
        nil))))

(def sql:retrieve-touched-objects
  "select so.id,
          ((select count(*) from file_media_object where media_id = so.id) +
           (select count(*) from file_media_object where thumbnail_id = so.id)) as nrefs
     from storage_object as so
    where so.touched_at is not null
    order by so.touched_at
    limit 500;")

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
;; and is inmediatelly deleted. The responsability of this task is
;; check that write log for possible leaked files.

(declare sql:retrieve-pending)
(declare sql:exists-storage-object)

(defmethod ig/pre-init-spec ::recheck-task [_]
  (s/keys :req-un [::storage ::db/pool]))

(defmethod ig/init-key ::recheck-task
  [_ {:keys [pool storage] :as cfg}]
  (letfn [(retrieve-pending [conn]
            (->> (db/exec! conn [sql:retrieve-pending])
                 (map (fn [{:keys [backend] :as row}]
                        (assoc row :backend (keyword backend))))
                 (seq)))

          (exists-on-database? [conn id]
            (:exists (db/exec-one! conn [sql:exists-storage-object id])))

          (recheck-item [conn {:keys [id backend]}]
            (when-not (exists-on-database? conn id)
              (let [backend (resolve-backend storage backend)
                    backend (assoc backend :conn conn)]
                (impl/del-objects-in-bulk backend [id]))))]

    (fn [task]
      (db/with-atomic [conn pool]
        (loop [items (retrieve-pending conn)]
          (when items
            (run! (partial recheck-item conn) items)
            (recur (retrieve-pending conn))))))))

(def sql:retrieve-pending
  "with items_part as (
     select s.id
       from storage_pending as s
      where s.created_at < now() - '1 hour'::interval
      order by s.created_at
      limit 100
   )
   delete from storage_pending
    where id in (select id from items_part)
   returning *;")

(def sql:exists-storage-object
  "select exists (select id from storage_object where id = ?) as exists")

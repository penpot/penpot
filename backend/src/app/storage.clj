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
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [integrant.core :as ig]))

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
  (s/keys :req-un [::db/pool ::backends]))

(defmethod ig/prep-key ::storage
  [_ {:keys [backends] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc :backends (d/without-nils backends))))

(defmethod ig/init-key ::storage
  [_ {:keys [backends] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc :backends (d/without-nils backends))))

(s/def ::storage
  (s/keys :req-un [::backends ::db/pool]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord StorageObject [id size created-at expired-at touched-at backend])

(defn storage-object?
  [v]
  (instance? StorageObject v))

(s/def ::storage-object storage-object?)
(s/def ::storage-content impl/content?)


(defn- clone-database-object
  ;; If we in this condition branch, this means we come from the
  ;; clone-object, so we just need to clone it with a new backend.
  [{:keys [conn backend]} object]
  (let [id     (uuid/random)
        mdata  (meta object)
        result (db/insert! conn :storage-object
                           {:id id
                            :size (:size object)
                            :backend (name backend)
                            :metadata (db/tjson mdata)
                            :deleted-at (:expired-at object)
                            :touched-at (:touched-at object)})]
    (assoc object
           :id (:id result)
           :backend backend
           :created-at (:created-at result)
           :touched-at (:touched-at result))))

(defn- create-database-object
  [{:keys [conn backend]} {:keys [content] :as object}]
  (us/assert ::storage-content content)
  (let [id     (uuid/random)
        mdata  (dissoc object :content :expired-at :touched-at)

        result (db/insert! conn :storage-object
                           {:id id
                            :size (count content)
                            :backend (name backend)
                            :metadata (db/tjson mdata)
                            :deleted-at (:expired-at object)
                            :touched-at (:touched-at object)})]

    (StorageObject. (:id result)
                    (:size result)
                    (:created-at result)
                    (:deleted-at result)
                    (:touched-at result)
                    backend
                    mdata
                    nil)))

(def ^:private sql:retrieve-storage-object
  "select * from storage_object where id = ? and (deleted_at is null or deleted_at > now())")

(defn row->storage-object [res]
  (let [mdata (or (some-> (:metadata res) (db/decode-transit-pgobject)) {})]
    (StorageObject. (:id res)
                    (:size res)
                    (:created-at res)
                    (:deleted-at res)
                    (:touched-at res)
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
  [{:keys [pool conn backend] :as storage} {:keys [content] :as params}]
  (us/assert ::storage storage)
  (us/assert ::storage-content content)
  (us/assert ::us/keyword backend)
  (let [storage (assoc storage :conn (or conn pool))
        object  (create-database-object storage params)]

    ;; Store the data finally on the underlying storage subsystem.
    (-> (impl/resolve-backend storage backend)
        (impl/put-object object content))

    object))

(defn clone-object
  "Creates a clone of the provided object using backend based efficient
  method. Always clones objects to the configured default."
  [{:keys [pool conn backend] :as storage} object]
  (us/assert ::storage storage)
  (us/assert ::storage-object object)
  (us/assert ::us/keyword backend)
  (let [storage (assoc storage :conn (or conn pool))
        object* (clone-database-object storage object)]
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

(declare sql:retrieve-deleted-objects-chunk)

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::gc-deleted-task [_]
  (s/keys :req-un [::storage ::db/pool ::min-age]))

(defmethod ig/init-key ::gc-deleted-task
  [_ {:keys [pool storage min-age] :as cfg}]
  (letfn [(retrieve-deleted-objects-chunk [conn cursor]
            (let [min-age (db/interval min-age)
                  rows    (db/exec! conn [sql:retrieve-deleted-objects-chunk min-age cursor])]
              [(some-> rows peek :created-at)
               (some->> (seq rows) (d/group-by' #(-> % :backend keyword) :id) seq)]))

          (retrieve-deleted-objects [conn]
            (->> (d/iteration (fn [cursor]
                                (retrieve-deleted-objects-chunk conn cursor))
                              :initk (dt/now)
                              :vf second
                              :kf first)
                 (sequence cat)))

          (delete-in-bulk [conn backend ids]
            (let [backend (impl/resolve-backend storage backend)
                  backend (assoc backend :conn conn)]
              (impl/del-objects-in-bulk backend ids)))]

    (fn [_]
      (db/with-atomic [conn pool]
        (loop [total  0
               groups (retrieve-deleted-objects conn)]
          (if-let [[backend ids] (first groups)]
            (do
              (delete-in-bulk conn backend ids)
              (recur (+ total (count ids))
                     (rest groups)))
            (do
              (l/info :task "gc-deleted" :count total)
              {:deleted total})))))))

(def sql:retrieve-deleted-objects-chunk
  "with items_part as (
     select s.id
       from storage_object as s
      where s.deleted_at is not null
        and s.deleted_at < (now() - ?::interval)
        and s.created_at < ?
      order by s.created_at desc
      limit 100
   )
   delete from storage_object
    where id in (select id from items_part)
   returning *;")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Analyze touched objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This task is part of the garbage collection of storage objects and is responsible on analyzing the touched
;; objects and mark them for deletion if corresponds.
;;
;; For example: when file_media_object is deleted, the depending storage_object are marked as touched. This
;; means that some files that depend on a concrete storage_object are no longer exists and maybe this
;; storage_object is no longer necessary and can be eligible for elimination. This task periodically analyzes
;; touched objects and mark them as freeze (means that has other references and the object is still valid) or
;; deleted (no more references to this object so is ready to be deleted).

(declare sql:retrieve-touched-objects-chunk)
(declare sql:retrieve-file-media-object-nrefs)
(declare sql:retrieve-team-font-variant-nrefs)

(defmethod ig/pre-init-spec ::gc-touched-task [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::gc-touched-task
  [_ {:keys [pool] :as cfg}]
  (letfn [(has-team-font-variant-nrefs? [conn id]
            (-> (db/exec-one! conn [sql:retrieve-team-font-variant-nrefs id id id id]) :nrefs pos?))

          (has-file-media-object-nrefs? [conn id]
            (-> (db/exec-one! conn [sql:retrieve-file-media-object-nrefs id id]) :nrefs pos?))

          (mark-freeze-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" ids)]))

          (mark-delete-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set deleted_at=now(), touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" ids)]))

          (retrieve-touched-chunk [conn cursor]
            (let [rows (->> (db/exec! conn [sql:retrieve-touched-objects-chunk cursor])
                            (mapv #(d/update-when % :metadata db/decode-transit-pgobject)))
                  kw   (fn [o] (if (keyword? o) o (keyword o)))]
              (when (seq rows)
                [(-> rows peek :created-at)
                 ;; NOTE: we use the :file-media-object as default value for backward compatibility because when we
                 ;; deploy it we can have old backend instances running in the same time as the new one and we can
                 ;; still have storage-objects created without reference value.  And we know that if it does not
                 ;; have value, it means :file-media-object.
                 (d/group-by' #(or (some-> % :metadata :reference kw) :file-media-object) :id rows)])))

          (retrieve-touched [conn]
            (->> (d/iteration (fn [cursor]
                                (retrieve-touched-chunk conn cursor))
                              :initk (dt/now)
                              :vf second
                              :kf first)
                 (sequence cat)))

          (process-objects! [conn pred-fn ids]
            (loop [to-freeze #{}
                   to-delete #{}
                   ids       (seq ids)]
              (if-let [id (first ids)]
                (if (pred-fn conn id)
                  (recur (conj to-freeze id) to-delete (rest ids))
                  (recur to-freeze (conj to-delete id) (rest ids)))

                (do
                  (some->> (seq to-freeze) (mark-freeze-in-bulk conn))
                  (some->> (seq to-delete) (mark-delete-in-bulk conn))
                  [(count to-freeze) (count to-delete)]))))
          ]

    (fn [_]
      (db/with-atomic [conn pool]
        (loop [to-freeze 0
               to-delete 0
               groups    (retrieve-touched conn)]
          (if-let [[reference ids] (first groups)]
            (let [[f d] (case reference
                          :file-media-object (process-objects! conn has-file-media-object-nrefs? ids)
                          :team-font-variant (process-objects! conn has-team-font-variant-nrefs? ids)
                          (ex/raise :type :internal
                                    :code :unexpected-unknown-reference
                                    :hint (format "unknown reference %s" (pr-str reference))))]
              (recur (+ to-freeze f)
                     (+ to-delete d)
                     (rest groups)))
            (do
              (l/info :task "gc-touched" :to-freeze to-freeze :to-delete to-delete)
              {:freeze to-freeze :delete to-delete})))))))

(def sql:retrieve-touched-objects-chunk
  "select so.* from storage_object as so
    where so.touched_at is not null
      and so.created_at < ?
    order by so.created_at desc
    limit 500;")

(def sql:retrieve-file-media-object-nrefs
  "select ((select count(*) from file_media_object where media_id = ?) +
           (select count(*) from file_media_object where thumbnail_id = ?)) as nrefs")

(def sql:retrieve-team-font-variant-nrefs
  "select ((select count(*) from team_font_variant where woff1_file_id = ?) +
           (select count(*) from team_font_variant where woff2_file_id = ?) +
           (select count(*) from team_font_variant where otf_file_id = ?) +
           (select count(*) from team_font_variant where ttf_file_id = ?)) as nrefs")

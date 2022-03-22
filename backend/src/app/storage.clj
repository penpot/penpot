;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.storage
  "Objects storage abstraction layer."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
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
   [promesa.core :as p]
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
  (s/keys :req-un [::db/pool ::wrk/executor ::backends]))

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

(defn get-metadata
  [params]
  (into {}
        (remove (fn [[k _]] (qualified-keyword? k)))
        params))

(defn- get-database-object-by-hash
  [conn backend bucket hash]
  (let [sql (str "select * from storage_object "
                 " where (metadata->>'~:hash') = ? "
                 "   and (metadata->>'~:bucket') = ? "
                 "   and backend = ?"
                 "   and deleted_at is null"
                 " limit 1")]
    (db/exec-one! conn [sql hash bucket (name backend)])))

(defn- create-database-object
  [{:keys [conn backend executor]} {:keys [::content ::expired-at ::touched-at] :as params}]
  (us/assert ::storage-content content)
  (px/with-dispatch executor
    (let [id     (uuid/random)

          mdata  (cond-> (get-metadata params)
                   (satisfies? impl/IContentHash content)
                   (assoc :hash (impl/get-hash content)))

          ;; NOTE: for now we don't reuse the deleted objects, but in
          ;; futute we can consider reusing deleted objects if we
          ;; found a duplicated one and is marked for deletion but
          ;; still not deleted.
          result (when (and (::deduplicate? params)
                            (:hash mdata)
                            (:bucket mdata))
                   (get-database-object-by-hash conn backend (:bucket mdata) (:hash mdata)))

          result (or result
                     (db/insert! conn :storage-object
                                 {:id id
                                  :size (count content)
                                  :backend (name backend)
                                  :metadata (db/tjson mdata)
                                  :deleted-at expired-at
                                  :touched-at touched-at}))]

      (StorageObject. (:id result)
                      (:size result)
                      (:created-at result)
                      (:deleted-at result)
                      (:touched-at result)
                      backend
                      mdata
                      nil))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn object->relative-path
  [{:keys [id] :as obj}]
  (impl/id->path id))

(defn file-url->path
  [url]
  (fs/path (java.net.URI. (str url))))

(dm/export impl/content)
(dm/export impl/wrap-with-hash)

(defn get-object
  [{:keys [conn pool] :as storage} id]
  (us/assert ::storage storage)
  (p/do
    (-> (assoc storage :conn (or conn pool))
        (retrieve-database-object id))))

(defn put-object!
  "Creates a new object with the provided content."
  [{:keys [pool conn backend] :as storage} {:keys [::content] :as params}]
  (us/assert ::storage storage)
  (us/assert ::storage-content content)
  (us/assert ::us/keyword backend)
  (p/let [storage (assoc storage :conn (or conn pool))
          object  (create-database-object storage params)]

    ;; Store the data finally on the underlying storage subsystem.
    (-> (impl/resolve-backend storage backend)
        (impl/put-object object content))

    object))

(defn touch-object!
  "Mark object as touched."
  [{:keys [pool conn] :as storage} object-or-id]
  (p/do
    (let [id  (if (storage-object? object-or-id) (:id object-or-id) object-or-id)
          res (db/update! (or conn pool) :storage-object
                          {:touched-at (dt/now)}
                          {:id id}
                          {:return-keys false})]
      (pos? (:next.jdbc/update-count res)))))

(defn get-object-data
  "Return an input stream instance of the object content."
  [{:keys [pool conn] :as storage} object]
  (us/assert ::storage storage)
  (p/do
    (when (or (nil? (:expired-at object))
              (dt/is-after? (:expired-at object) (dt/now)))
      (-> (assoc storage :conn (or conn pool))
          (impl/resolve-backend (:backend object))
          (impl/get-object-data object)))))

(defn get-object-bytes
  "Returns a byte array of object content."
  [{:keys [pool conn] :as storage} object]
  (us/assert ::storage storage)
  (p/do
    (when (or (nil? (:expired-at object))
              (dt/is-after? (:expired-at object) (dt/now)))
      (-> (assoc storage :conn (or conn pool))
          (impl/resolve-backend (:backend object))
          (impl/get-object-bytes object)))))

(defn get-object-url
  ([storage object]
   (get-object-url storage object nil))
  ([{:keys [conn pool] :as storage} object options]
   (us/assert ::storage storage)
   (p/do
     (when (or (nil? (:expired-at object))
               (dt/is-after? (:expired-at object) (dt/now)))
       (-> (assoc storage :conn (or conn pool))
           (impl/resolve-backend (:backend object))
           (impl/get-object-url object options))))))

(defn get-object-path
  "Get the Path to the object. Only works with `:fs` type of
  storages."
  [storage object]
  (p/do
    (let [backend (impl/resolve-backend storage (:backend object))]
      (when (not= :fs (:type backend))
        (ex/raise :type :internal
                  :code :operation-not-allowed
                  :hint "get-object-path only works with fs type backends"))
      (when (or (nil? (:expired-at object))
                (dt/is-after? (:expired-at object) (dt/now)))
        (p/-> (impl/get-object-url backend object nil) file-url->path)))))

(defn del-object!
  [{:keys [conn pool] :as storage} object-or-id]
  (us/assert ::storage storage)
  (p/do
    (let [id  (if (storage-object? object-or-id) (:id object-or-id) object-or-id)
          res (db/update! (or conn pool) :storage-object
                          {:deleted-at (dt/now)}
                          {:id id}
                          {:return-keys false})]
      (pos? (:next.jdbc/update-count res)))))

(dm/export impl/resolve-backend)
(dm/export impl/calculate-hash)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Permanently delete objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A task responsible to permanently delete already marked as deleted
;; storage files. The storage objects are practically never marked to
;; be deleted directly by the api call. The touched-gc is responsible
;; of collecting the usage of the object and mark it as deleted.

(declare sql:retrieve-deleted-objects-chunk)

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::gc-deleted-task [_]
  (s/keys :req-un [::storage ::db/pool ::min-age ::wrk/executor]))

(defmethod ig/init-key ::gc-deleted-task
  [_ {:keys [pool storage min-age] :as cfg}]
  (letfn [(retrieve-deleted-objects-chunk [conn cursor]
            (let [min-age (db/interval min-age)
                  rows    (db/exec! conn [sql:retrieve-deleted-objects-chunk min-age cursor])]
              [(some-> rows peek :created-at)
               (some->> (seq rows) (d/group-by #(-> % :backend keyword) :id #{}) seq)]))

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
              @(impl/del-objects-in-bulk backend ids)))]

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
      limit 25
   )
   delete from storage_object
    where id in (select id from items_part)
   returning *;")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Analyze touched objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This task is part of the garbage collection process of storage
;; objects and is responsible on analyzing the touched objects and
;; mark them for deletion if corresponds.
;;
;; For example: when file_media_object is deleted, the depending
;; storage_object are marked as touched. This means that some files
;; that depend on a concrete storage_object are no longer exists and
;; maybe this storage_object is no longer necessary and can be
;; eligible for elimination. This task periodically analyzes touched
;; objects and mark them as freeze (means that has other references
;; and the object is still valid) or deleted (no more references to
;; this object so is ready to be deleted).

(declare sql:retrieve-touched-objects-chunk)
(declare sql:retrieve-file-media-object-nrefs)
(declare sql:retrieve-team-font-variant-nrefs)
(declare sql:retrieve-profile-nrefs)

(defmethod ig/pre-init-spec ::gc-touched-task [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::gc-touched-task
  [_ {:keys [pool] :as cfg}]
  (letfn [(has-team-font-variant-nrefs? [conn id]
            (-> (db/exec-one! conn [sql:retrieve-team-font-variant-nrefs id id id id]) :nrefs pos?))

          (has-file-media-object-nrefs? [conn id]
            (-> (db/exec-one! conn [sql:retrieve-file-media-object-nrefs id id]) :nrefs pos?))

          (has-profile-nrefs? [conn id]
            (-> (db/exec-one! conn [sql:retrieve-profile-nrefs id id]) :nrefs pos?))

          (mark-freeze-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" ids)]))

          (mark-delete-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set deleted_at=now(), touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" ids)]))

          ;; NOTE: A getter that retrieves the key witch will be used
          ;; for group ids; previoulsy we have no value, then we
          ;; introduced the `:reference` prop, and then it is renamed
          ;; to `:bucket` and now is string instead. This is
          ;; implemented in this way for backward comaptibilty.

          ;; NOTE: we use the "file-media-object" as default value for
          ;; backward compatibility because when we deploy it we can
          ;; have old backend instances running in the same time as
          ;; the new one and we can still have storage-objects created
          ;; without bucket value.  And we know that if it does not
          ;; have value, it means :file-media-object.

          (get-bucket [{:keys [metadata]}]
            (or (some-> metadata :bucket)
                (some-> metadata :reference d/name)
                "file-media-object"))

          (retrieve-touched-chunk [conn cursor]
            (let [rows (->> (db/exec! conn [sql:retrieve-touched-objects-chunk cursor])
                            (mapv #(d/update-when % :metadata db/decode-transit-pgobject)))]
              (when (seq rows)
                [(-> rows peek :created-at)
                 (d/group-by get-bucket :id #{} rows)])))

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
          (if-let [[bucket ids] (first groups)]
            (let [[f d] (case bucket
                          "file-media-object" (process-objects! conn has-file-media-object-nrefs? ids)
                          "team-font-variant" (process-objects! conn has-team-font-variant-nrefs? ids)
                          "profile"           (process-objects! conn has-profile-nrefs? ids)
                          (ex/raise :type :internal
                                    :code :unexpected-unknown-reference
                                    :hint (dm/fmt "unknown reference %" bucket)))]
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

(def sql:retrieve-profile-nrefs
  "select ((select count(*) from profile where photo_id = ?) +
           (select count(*) from team where photo_id = ?)) as nrefs")

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
   [app.storage.fs :as sfs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as ss3]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::id #{:assets-fs :assets-s3})
(s/def ::s3 ::ss3/backend)
(s/def ::fs ::sfs/backend)
(s/def ::type #{:fs :s3})

(s/def ::backends
  (s/map-of ::us/keyword
            (s/nilable
             (s/or :s3 ::ss3/backend
                   :fs ::sfs/backend))))

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req [::db/pool ::wrk/executor ::backends]))

(defmethod ig/init-key ::storage
  [_ {:keys [::backends ::db/pool] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc ::backends (d/without-nils backends))
      (assoc ::db/pool-or-conn pool)))

(s/def ::backend keyword?)
(s/def ::storage
  (s/keys :req [::backends ::db/pool ::db/pool-or-conn]
          :opt [::backend]))

(s/def ::storage-with-backend
  (s/and ::storage #(contains? % ::backend)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-metadata
  [params]
  (into {}
        (remove (fn [[k _]] (qualified-keyword? k)))
        params))

(defn- get-database-object-by-hash
  [pool-or-conn backend bucket hash]
  (let [sql (str "select * from storage_object "
                 " where (metadata->>'~:hash') = ? "
                 "   and (metadata->>'~:bucket') = ? "
                 "   and backend = ?"
                 "   and deleted_at is null"
                 " limit 1")]
    (some-> (db/exec-one! pool-or-conn [sql hash bucket (name backend)])
            (update :metadata db/decode-transit-pgobject))))

(defn- create-database-object
  [{:keys [::backend ::db/pool-or-conn]} {:keys [::content ::expired-at ::touched-at] :as params}]
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
                 (get-database-object-by-hash pool-or-conn backend (:bucket mdata) (:hash mdata)))

        result (or result
                   (-> (db/insert! pool-or-conn :storage-object
                                   {:id id
                                    :size (impl/get-size content)
                                    :backend (name backend)
                                    :metadata (db/tjson mdata)
                                    :deleted-at expired-at
                                    :touched-at touched-at})
                       (update :metadata db/decode-transit-pgobject)
                       (update :metadata assoc ::created? true)))]

    (impl/storage-object
     (:id result)
     (:size result)
     (:created-at result)
     (:deleted-at result)
     (:touched-at result)
     backend
     (:metadata result))))

(def ^:private sql:retrieve-storage-object
  "select * from storage_object where id = ? and (deleted_at is null or deleted_at > now())")

(defn row->storage-object [res]
  (let [mdata (or (some-> (:metadata res) (db/decode-transit-pgobject)) {})]
    (impl/storage-object
     (:id res)
     (:size res)
     (:created-at res)
     (:deleted-at res)
     (:touched-at res)
     (keyword (:backend res))
     mdata)))

(defn- retrieve-database-object
  [conn id]
  (some-> (db/exec-one! conn [sql:retrieve-storage-object id])
          (row->storage-object)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn object->relative-path
  [{:keys [id] :as obj}]
  (impl/id->path id))

(defn file-url->path
  [url]
  (when url
    (fs/path (java.net.URI. (str url)))))

(dm/export impl/content)
(dm/export impl/wrap-with-hash)
(dm/export impl/object?)

(defn get-object
  [{:keys [::db/pool-or-conn] :as storage} id]
  (us/assert! ::storage storage)
  (retrieve-database-object pool-or-conn id))

(defn put-object!
  "Creates a new object with the provided content."
  [{:keys [::backend] :as storage} {:keys [::content] :as params}]
  (us/assert! ::storage-with-backend storage)
  (us/assert! ::impl/content content)
  (let [object (create-database-object storage params)]
    (if (::created? (meta object))
      ;; Store the data finally on the underlying storage subsystem.
      (-> (impl/resolve-backend storage backend)
          (impl/put-object object content))
      object)))

(defn touch-object!
  "Mark object as touched."
  [{:keys [::db/pool-or-conn] :as storage} object-or-id]
  (us/assert! ::storage storage)
  (let [id (if (impl/object? object-or-id) (:id object-or-id) object-or-id)
        rs (db/update! pool-or-conn :storage-object
                       {:touched-at (dt/now)}
                       {:id id}
                       {::db/return-keys? false})]
    (pos? (db/get-update-count rs))))

(defn get-object-data
  "Return an input stream instance of the object content."
  [storage object]
  (us/assert! ::storage storage)
  (when (or (nil? (:expired-at object))
            (dt/is-after? (:expired-at object) (dt/now)))
    (-> (impl/resolve-backend storage (:backend object))
        (impl/get-object-data object))))

(defn get-object-bytes
  "Returns a byte array of object content."
  [storage object]
  (us/assert! ::storage storage)
  (if (or (nil? (:expired-at object))
          (dt/is-after? (:expired-at object) (dt/now)))
    (-> (impl/resolve-backend storage (:backend object))
        (impl/get-object-bytes object))
    (p/resolved nil)))

(defn get-object-url
  ([storage object]
   (get-object-url storage object nil))
  ([storage object options]
   (us/assert! ::storage storage)
   (when (or (nil? (:expired-at object))
             (dt/is-after? (:expired-at object) (dt/now)))
     (-> (impl/resolve-backend storage (:backend object))
         (impl/get-object-url object options)))))

(defn get-object-path
  "Get the Path to the object. Only works with `:fs` type of
  storages."
  [storage object]
  (us/assert! ::storage storage)
  (let [backend (impl/resolve-backend storage (:backend object))]
    (when (and (= :fs (::type backend))
               (or (nil? (:expired-at object))
                   (dt/is-after? (:expired-at object) (dt/now))))
      (-> (impl/get-object-url backend object nil) file-url->path))))

(defn del-object!
  [{:keys [::db/pool-or-conn] :as storage} object-or-id]
  (us/assert! ::storage storage)
  (let [id  (if (impl/object? object-or-id) (:id object-or-id) object-or-id)
        res (db/update! pool-or-conn :storage-object
                        {:deleted-at (dt/now)}
                        {:id id}
                        {::db/return-keys? false})]
    (pos? (db/get-update-count res))))

(dm/export impl/resolve-backend)
(dm/export impl/calculate-hash)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection: Permanently delete objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A task responsible to permanently delete already marked as deleted
;; storage files. The storage objects are practically never marked to
;; be deleted directly by the api call. The touched-gc is responsible
;; of collecting the usage of the object and mark it as deleted. Only
;; the TMP files are are created with expiration date in future.

(declare sql:retrieve-deleted-objects-chunk)

(defmethod ig/pre-init-spec ::gc-deleted-task [_]
  (s/keys :req [::storage ::db/pool]))

(defmethod ig/prep-key ::gc-deleted-task
  [_ cfg]
  (assoc cfg ::min-age (dt/duration {:hours 2})))

(defmethod ig/init-key ::gc-deleted-task
  [_ {:keys [::db/pool ::storage ::min-age]}]
  (letfn [(retrieve-deleted-objects-chunk [conn min-age cursor]
            (let [min-age (db/interval min-age)
                  rows    (db/exec! conn [sql:retrieve-deleted-objects-chunk min-age cursor])]
              [(some-> rows peek :created-at)
               (some->> (seq rows) (d/group-by #(-> % :backend keyword) :id #{}) seq)]))

          (retrieve-deleted-objects [conn min-age]
            (d/iteration (partial retrieve-deleted-objects-chunk conn min-age)
                         :initk (dt/now)
                         :vf second
                         :kf first))

          (delete-in-bulk [backend-id ids]
            (let [backend (impl/resolve-backend storage backend-id)]

              (doseq [id ids]
                (l/debug :hint "gc-deleted: permanently delete storage object" :backend backend-id :id id))

              (impl/del-objects-in-bulk backend ids)))]

    (fn [params]
      (let [min-age (or (:min-age params) min-age)]
        (db/with-atomic [conn pool]
          (loop [total  0
                 groups (retrieve-deleted-objects conn min-age)]
            (if-let [[backend-id ids] (first groups)]
              (do
                (delete-in-bulk backend-id ids)
                (recur (+ total (count ids))
                       (rest groups)))
              (do
                (l/info :hint "gc-deleted: task finished" :min-age (dt/format-duration min-age) :total total)
                {:deleted total}))))))))

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
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::gc-touched-task
  [_ {:keys [::db/pool]}]
  (letfn [(get-team-font-variant-nrefs [conn id]
            (-> (db/exec-one! conn [sql:retrieve-team-font-variant-nrefs id id id id]) :nrefs))

          (get-file-media-object-nrefs [conn id]
            (-> (db/exec-one! conn [sql:retrieve-file-media-object-nrefs id id]) :nrefs))

          (get-profile-nrefs [conn id]
            (-> (db/exec-one! conn [sql:retrieve-profile-nrefs id id]) :nrefs))

          (mark-freeze-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" ids)]))

          (mark-delete-in-bulk [conn ids]
            (db/exec-one! conn ["update storage_object set deleted_at=now(), touched_at=null where id = ANY(?)"
                                (db/create-array conn "uuid" ids)]))

          ;; NOTE: A getter that retrieves the key witch will be used
          ;; for group ids; previously we have no value, then we
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
            (d/iteration (partial retrieve-touched-chunk conn)
                         :initk (dt/now)
                         :vf second
                         :kf first))

          (process-objects! [conn get-fn ids bucket]
            (loop [to-freeze #{}
                   to-delete #{}
                   ids       (seq ids)]
              (if-let [id (first ids)]
                (let [nrefs (get-fn conn id)]
                  (if (pos? nrefs)
                    (do
                      (l/debug :hint "gc-touched: processing storage object"
                               :id id :status "freeze"
                               :bucket bucket :refs nrefs)
                      (recur (conj to-freeze id) to-delete (rest ids)))
                    (do
                      (l/debug :hint "gc-touched: processing storage object"
                               :id id :status "delete"
                               :bucket bucket :refs nrefs)
                      (recur to-freeze (conj to-delete id) (rest ids)))))
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
                          "file-media-object" (process-objects! conn get-file-media-object-nrefs ids bucket)
                          "team-font-variant" (process-objects! conn get-team-font-variant-nrefs ids bucket)
                          "profile"           (process-objects! conn get-profile-nrefs ids bucket)
                          (ex/raise :type :internal
                                    :code :unexpected-unknown-reference
                                    :hint (dm/fmt "unknown reference %" bucket)))]
              (recur (+ to-freeze (long f))
                     (+ to-delete (long d))
                     (rest groups)))
            (do
              (l/info :hint "gc-touched: task finished" :to-freeze to-freeze :to-delete to-delete)
              {:freeze to-freeze :delete to-delete})))))))

(def sql:retrieve-touched-objects-chunk
  "SELECT so.*
     FROM storage_object AS so
    WHERE so.touched_at IS NOT NULL
      AND so.created_at < ?
    ORDER by so.created_at DESC
    LIMIT 500;")

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

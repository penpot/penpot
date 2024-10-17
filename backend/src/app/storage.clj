;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage
  "Objects storage abstraction layer."
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.storage.fs :as sfs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as ss3]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig])
  (:import
   java.io.InputStream))

(defn get-legacy-backend
  []
  (let [name (cf/get :assets-storage-backend)]
    (case name
      :assets-fs :fs
      :assets-s3 :s3
      :fs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::id #{:assets-fs :assets-s3 :fs :s3})
(s/def ::s3 ::ss3/backend)
(s/def ::fs ::sfs/backend)
(s/def ::type #{:fs :s3})

(s/def ::backends
  (s/map-of ::us/keyword
            (s/nilable
             (s/or :s3 ::ss3/backend
                   :fs ::sfs/backend))))

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req [::db/pool ::backends]))

(defmethod ig/init-key ::storage
  [_ {:keys [::backends ::db/pool] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc ::backends (d/without-nils backends))
      (assoc ::backend (or (get-legacy-backend)
                           (cf/get :objects-storage-backend :fs)))
      (assoc ::db/connectable pool)))

(s/def ::backend keyword?)
(s/def ::storage
  (s/keys :req [::backends ::db/pool ::db/connectable]
          :opt [::backend]))

(s/def ::storage-with-backend
  (s/and ::storage #(contains? % ::backend)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-metadata
  [params]
  (reduce-kv (fn [res k _]
               (if (qualified-keyword? k)
                 (dissoc res k)
                 res))
             params
             params))

(defn- get-database-object-by-hash
  [connectable backend bucket hash]
  (let [sql (str "select * from storage_object "
                 " where (metadata->>'~:hash') = ? "
                 "   and (metadata->>'~:bucket') = ? "
                 "   and backend = ?"
                 "   and deleted_at is null"
                 " limit 1")]
    (some-> (db/exec-one! connectable [sql hash bucket (name backend)])
            (update :metadata db/decode-transit-pgobject))))

(defn- create-database-object
  [{:keys [::backend ::db/connectable]} {:keys [::content ::expired-at ::touched-at ::touch] :as params}]
  (let [id     (or (:id params) (uuid/random))
        mdata  (cond-> (get-metadata params)
                 (satisfies? impl/IContentHash content)
                 (assoc :hash (impl/get-hash content))

                 :always
                 (dissoc :id))

        touched-at (if touch
                     (or touched-at (dt/now))
                     touched-at)

        ;; NOTE: for now we don't reuse the deleted objects, but in
        ;; futute we can consider reusing deleted objects if we
        ;; found a duplicated one and is marked for deletion but
        ;; still not deleted.
        result (when (and (::deduplicate? params)
                          (:hash mdata)
                          (:bucket mdata))
                 (let [result (get-database-object-by-hash connectable backend
                                                           (:bucket mdata)
                                                           (:hash mdata))]
                   (if touch
                     (do
                       (db/update! connectable :storage-object
                                   {:touched-at touched-at}
                                   {:id (:id result)}
                                   {::db/return-keys false})
                       (assoc result :touced-at touched-at))
                     result)))

        result (or result
                   (-> (db/insert! connectable :storage-object
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
  [{:keys [::db/connectable] :as storage} id]
  (us/assert! ::storage storage)
  (retrieve-database-object connectable id))

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
  [{:keys [::db/connectable] :as storage} object-or-id]
  (us/assert! ::storage storage)
  (let [id (if (impl/object? object-or-id) (:id object-or-id) object-or-id)]
    (-> (db/update! connectable :storage-object
                    {:touched-at (dt/now)}
                    {:id id})
        (db/get-update-count)
        (pos?))))

(defn get-object-data
  "Return an input stream instance of the object content."
  ^InputStream
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
  (when (or (nil? (:expired-at object))
            (dt/is-after? (:expired-at object) (dt/now)))
    (-> (impl/resolve-backend storage (:backend object))
        (impl/get-object-bytes object))))

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
  [{:keys [::db/connectable] :as storage} object-or-id]
  (us/assert! ::storage storage)
  (let [id  (if (impl/object? object-or-id) (:id object-or-id) object-or-id)
        res (db/update! connectable :storage-object
                        {:deleted-at (dt/now)}
                        {:id id})]
    (pos? (db/get-update-count res))))

(dm/export impl/calculate-hash)

(defn configure
  [storage connectable]
  (assoc storage ::db/connectable connectable))

(defn resolve
  "Resolves the storage instance with preconfigured backend. You can
  specify to reuse the database connection from provided
  cfg/system (default false)."
  [cfg & {:as opts}]
  (let [storage (::storage cfg)]
    (if (::db/reuse-conn opts false)
      (configure storage (db/get-connectable cfg))
      storage)))

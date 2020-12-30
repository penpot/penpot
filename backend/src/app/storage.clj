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
   [app.storage.fs :as sfs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as ss3]
   [app.storage.db :as sdb]
   [app.util.time :as dt]
   [lambdaisland.uri :as u]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handler)

(s/def ::backend ::us/keyword)
(s/def ::backends
  (s/map-of ::us/keyword
            (s/or :s3 ::ss3/backend
                  :fs ::sfs/backend
                  :db ::sdb/backend)))

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req-un [::backend ::db/pool ::backends]))

(defmethod ig/prep-key ::storage
  [_ {:keys [backends] :as cfg}]
  (assoc cfg :backends (d/without-nils backends)))

(defmethod ig/init-key ::storage
  [_ {:keys [backends] :as cfg}]
  (assoc cfg :handler (partial handler cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord StorageObject [id size created-at backend])

(def ^:private
  sql:insert-storage-object
  "insert into storage_object (id, size, backend, metadata)
   values (?, ?, ?, ?::jsonb)
   returning *")

(defn- create-database-object
  [conn backend {:keys [content] :as object}]
  (let [id     (uuid/next)
        mdata  (dissoc object :content)
        result (db/exec-one! conn [sql:insert-storage-object id
                                   (count content)
                                   (name backend)
                                   (db/tjson mdata)])]
    (StorageObject. (:id result)
                    (:size result)
                    (:created-at result)
                    backend
                    mdata
                    nil)))

(def ^:private sql:retrieve-storage-object
  "select * from storage_object where id = ? and deleted_at is null")

(defn- retrieve-database-object
  [conn id]
  (when-let [res (db/exec-one! conn [sql:retrieve-storage-object id])]
    (let [mdata (some-> (:metadata res) (db/decode-transit-pgobject))]
      (StorageObject. (:id res)
                      (:size res)
                      (:created-at res)
                      (keyword (:backend res))
                      mdata
                      nil))))

(def sql:delete-storage-object
  "update storage_object set deleted_at=now() where id=? and deleted_at is null")

(defn- delete-database-object
  [conn id]
  (let [result (db/exec-one! conn [sql:delete-storage-object id])]
    (pos? (:next.jdbc/update-count result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare resolve-backend)

(defn content-object
  ([data] (impl/content-object data nil))
  ([data size] (impl/content-object data size)))

(defn get-object
  [{:keys [conn pool]} id]
  (let [id (impl/coerce-id id)]
    (retrieve-database-object (or conn pool) id)))

(defn put-object
  [{:keys [pool conn backend] :as storage} {:keys [content] :as object}]
  (us/assert impl/content-object? content)
  (let [conn   (or conn pool)
        object (create-database-object conn backend object)]
    (-> (resolve-backend storage backend)
        (assoc :conn conn)
        (impl/put-object object content))
    object))

(defn get-object-data
  [{:keys [pool conn] :as storage} object]
  (-> (resolve-backend storage (:backend object))
      (assoc :conn (or conn pool))
      (impl/get-object object)))

(defn get-object-url
  ([storage object]
   (get-object-url storage object nil))
  ([storage object options]
   ;; As this operation does not need the database connection, the
   ;; assoc of the conn to backend is ommited.
   (-> (resolve-backend storage (:backend object))
       (impl/get-object-url object options))))

(defn del-object
  [{:keys [conn pool]} id]
  (let [conn (or conn pool)]
    (delete-database-object conn id)))

;; --- impl

(defn- resolve-backend
  [storage backend]
  (let [backend* (get-in storage [:backends backend])]
    (when-not backend*
      (ex/raise :type :internal
                :code :backend-not-configured
                :hint (str/fmt "backend '%s' not configured" backend)))
    backend*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cache-max-age
  (dt/duration {:hours 24}))

(def signature-max-age
  (dt/duration {:hours 24 :minutes 15}))

(defn- handler
  [storage request]
  (let [id  (get-in request [:path-params :id])
        obj (get-object storage id)]
    (if obj
      (let [mdata   (meta obj)
            backend (resolve-backend storage (:backend obj))]
        (case (:type backend)
          :db
          {:status 200
           :headers {"content-type" (:content-type mdata)
                     "cache-control" (str "max-age=" (inst-ms cache-max-age))}
           :body (get-object-data storage obj)}

          :s3
          (let [url (get-object-url storage obj {:max-age signature-max-age})]
            {:status 307
             :headers {"location" (str url)
                       "x-host"   (:host url)
                     "cache-control" (str "max-age=" (inst-ms cache-max-age))}
             :body ""})

          :fs
          (let [url (get-object-url storage obj)]
            {:status 200
             :headers {"x-accel-redirect" (:path url)
                       "content-type" (:content-type mdata)
                       "cache-control" (str "max-age=" (inst-ms cache-max-age))}
             :body ""})))
      {:status 404
       :body ""})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Garbage Collection Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A task responsible to permanently delete already marked as deleted
;; storage files.

(declare sql:retrieve-deleted-objects)

(defmethod ig/pre-init-spec ::gc-task [_]
  (s/keys :req-un [::storage ::db/pool]))

(defmethod ig/init-key ::gc-task
  [_ {:keys [pool storage] :as cfg}]
  (letfn [(retrieve-deleted-objects [conn]
            (when-let [result (seq (db/exec! conn [sql:retrieve-deleted-objects]))]
              (as-> (group-by (comp keyword :backend) result) $
                (reduce-kv #(assoc %1 %2 (map :id %3)) $ $))))

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
     select s.id from storage_object as s
      where s.deleted_at is not null
      order by s.deleted_at
      limit 500
   )
   delete from storage_object
    where id in (select id from items_part)
   returning *;")



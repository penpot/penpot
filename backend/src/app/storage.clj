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
   [integrant.core :as ig]
   [lambdaisland.uri :as u]
   [promesa.exec :as px]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::backend ::us/keyword)
(s/def ::backends
  (s/map-of ::us/keyword (s/or :s3 (s/nilable ::ss3/backend)
                               :fs (s/nilable ::sfs/backend)
                               :db (s/nilable ::sdb/backend))))

(defmethod ig/pre-init-spec ::storage [_]
  (s/keys :req-un [::backend ::wrk/executor ::db/pool ::backends]))

(defmethod ig/prep-key ::storage
  [_ {:keys [backends] :as cfg}]
  (-> (d/without-nils cfg)
      (assoc :backends (d/without-nils backends))))

(defmethod ig/init-key ::storage
  [_ cfg]
  cfg)

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
  [{:keys [conn backend]} {:keys [content] :as object}]
  (if (instance? StorageObject object)
    (let [id     (uuid/random)
          mdata  (meta object)
          result (db/exec-one! conn [sql:insert-storage-object id
                                     (:size object)
                                     (name (:backend object))
                                     (db/tjson mdata)])]
      (assoc object
             :id (:id result)
             :created-at (:created-at result)))
    (let [id     (uuid/random)
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
                      nil))))

(def ^:private sql:retrieve-storage-object
  "select * from storage_object where id = ? and deleted_at is null")

(defn- retrieve-database-object
  [{:keys [conn] :as storage} id]
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

(defn content
  ([data] (impl/content data nil))
  ([data size] (impl/content data size)))

(defn get-object
  [{:keys [conn pool] :as storage} id]
  (-> (assoc storage :conn (or conn pool))
      (retrieve-database-object id)))

(defn put-object
  [{:keys [pool conn backend executor] :as storage} {:keys [content] :as object}]
  (us/assert impl/content? content)
  (let [storage (assoc storage :conn (or conn pool))
        object  (create-database-object storage object)]

    ;; Schedule to execute in background; in an other transaction and
    ;; register the currently created storage object id for a later
    ;; recheck.
    (px/run! executor #(register-recheck storage backend (:id object)))

    ;; Store the data finally on the underlying storage subsystem.
    (-> (resolve-backend storage backend)
        (impl/put-object object content))

    object))

(defn clone-object
  [{:keys [pool conn executor] :as storage} object]
  (let [storage (assoc storage :conn (or conn pool))
        object* (create-database-object storage object)]

    (with-open [input (-> (resolve-backend storage (:backend object))
                          (impl/get-object-data object))]
      (-> (resolve-backend storage (:backend storage))
          (impl/put-object object* (impl/content input (:size object))))

      object*)))

(defn get-object-data
  [{:keys [pool conn] :as storage} object]
  (-> (assoc storage :conn (or conn pool))
      (resolve-backend (:backend object))
      (impl/get-object-data object)))

(defn get-object-url
  ([storage object]
   (get-object-url storage object nil))
  ([{:keys [conn pool] :as storage} object options]
   ;; As this operation does not need the database connection, the
   ;; assoc of the conn to backend is ommited.
   (-> (assoc storage :conn (or conn pool))
       (resolve-backend (:backend object))
       (impl/get-object-url object options))))

(defn del-object
  [{:keys [conn pool] :as storage} id]
  (-> (assoc storage :conn (or conn pool))
      (delete-database-object id)))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recheck Stalled Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
     select s.id from storage_pending as s
      order by s.created_at
      limit 100
   )
   delete from storage_pending
    where id in (select id from items_part)
   returning *;")

(def sql:exists-storage-object
  "select exists (select id from storage_object where id = ?) as exists")

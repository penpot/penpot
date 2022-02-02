;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.tasks.objects-gc
  "A maintenance task that performs a general purpose garbage collection
  of deleted objects."
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.storage :as sto]
   [app.storage.impl :as simpl]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(def target-tables
  ["profile"
   "team"
   "file"
   "project"
   "team_font_variant"])

(defmulti delete-objects :table)

(def sql:delete-objects
  "with deleted as (
    select id from %(table)s
     where deleted_at is not null
       and deleted_at < now() - ?::interval
     order by deleted_at
     limit %(limit)s
   )
   delete from %(table)s
    where id in (select id from deleted)
   returning *")

;; --- IMPL: generic object deletion

(defmethod delete-objects :default
  [{:keys [conn max-age table] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects
                         {:table table :limit 50})
        result  (db/exec! conn [sql max-age])]

    (doseq [{:keys [id] :as item} result]
      (l/trace :action "delete object" :table table :id id))

    (count result)))


;; --- IMPL: file deletion

(defmethod delete-objects "file"
  [{:keys [conn max-age table storage] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects
                         {:table table :limit 50})
        result  (db/exec! conn [sql max-age])
        backend (simpl/resolve-backend storage (cf/get :fdata-storage-backend))]

    (doseq [{:keys [id] :as item} result]
      (l/trace :action "delete object" :table table :id id)
      (when backend
        (simpl/del-object backend item)))

    (count result)))

;; --- IMPL: team-font-variant deletion

(defmethod delete-objects "team_font_variant"
  [{:keys [conn max-age storage table] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects
                         {:table table :limit 50})
        fonts   (db/exec! conn [sql max-age])
        storage (assoc storage :conn conn)]
    (doseq [{:keys [id] :as font} fonts]
      (l/trace :action "delete object" :table table :id id)
      (some->> (:woff1-file-id font) (sto/del-object storage))
      (some->> (:woff2-file-id font) (sto/del-object storage))
      (some->> (:otf-file-id font)   (sto/del-object storage))
      (some->> (:ttf-file-id font)   (sto/del-object storage)))
    (count fonts)))

;; --- IMPL: team deletion

(defmethod delete-objects "team"
  [{:keys [conn max-age storage table] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects
                         {:table table :limit 50})
        teams   (db/exec! conn [sql max-age])
        storage (assoc storage :conn conn)]

    (doseq [{:keys [id] :as team} teams]
      (l/trace :action "delete object" :table table :id id)
      (some->> (:photo-id team) (sto/del-object storage)))

    (count teams)))

;; --- IMPL: profile deletion

(def sql:retrieve-deleted-profiles
  "select id, photo_id from profile
    where deleted_at is not null
      and deleted_at < now() - ?::interval
    order by deleted_at
    limit %(limit)s
    for update")

(def sql:mark-owned-teams-deleted
  "with owned as (
    select tpr.team_id as id
      from team_profile_rel as tpr
     where tpr.is_owner is true
       and tpr.profile_id = ?
   )
   update team set deleted_at = now() - ?::interval
    where id in (select id from owned)")

(defmethod delete-objects "profile"
  [{:keys [conn max-age storage table] :as cfg}]
  (let [sql      (str/fmt sql:retrieve-deleted-profiles {:limit 50})
        profiles (db/exec! conn [sql max-age])
        storage  (assoc storage :conn conn)]

    (doseq [{:keys [id] :as profile} profiles]
      (l/trace :action "delete object" :table table :id id)

      ;; Mark the owned teams as deleted; this enables them to be processed
      ;; in the same transaction in the "team" table step.
      (db/exec-one! conn [sql:mark-owned-teams-deleted id max-age])

      ;; Mark as deleted the storage object related with the photo-id
      ;; field.
      (some->> (:photo-id profile) (sto/del-object storage))

      ;; And finally, permanently delete the profile.
      (db/delete! conn :profile {:id id}))

    (count profiles)))

;; --- INIT

(defn- process-table
  [{:keys [table] :as cfg}]
  (loop [n 0]
    (let [res (delete-objects cfg)]
      (if (pos? res)
        (recur (+ n res))
        (l/debug :hint "table gc summary" :table table :deleted n)))))

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::sto/storage ::max-age]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool max-age] :as cfg}]
  (fn [task]
    ;; Checking first on task argument allows properly testing it.
    (let [max-age (get task :max-age max-age)]
      (db/with-atomic [conn pool]
        (let [max-age (db/interval max-age)
              cfg     (-> cfg
                          (assoc :max-age max-age)
                          (assoc :conn conn))]
          (doseq [table target-tables]
            (process-table (assoc cfg :table table))))))))

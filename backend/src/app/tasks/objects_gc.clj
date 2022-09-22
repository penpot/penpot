;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.objects-gc
  "A maintenance task that performs a general purpose garbage collection
  of deleted objects."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.storage :as sto]
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
  [{:keys [conn min-age table] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects
                         {:table table :limit 50})
        result  (db/exec! conn [sql min-age])]

    (doseq [{:keys [id] :as item} result]
      (l/debug :hint "permanently delete object" :table table :id id))

    (count result)))

;; --- IMPL: file deletion

(defmethod delete-objects "file"
  [{:keys [conn min-age table] :as cfg}]
  (let [sql    (str/fmt sql:delete-objects {:table table :limit 50})
        result (db/exec! conn [sql min-age])]

    (doseq [{:keys [id] :as item} result]
      (l/debug :hint "permanently delete object" :table table :id id))

    (count result)))

;; --- IMPL: team-font-variant deletion

(defmethod delete-objects "team_font_variant"
  [{:keys [conn min-age storage table] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects {:table table :limit 50})
        fonts   (db/exec! conn [sql min-age])
        storage (media/configure-assets-storage storage conn)]
    (doseq [{:keys [id] :as font} fonts]
      (l/debug :hint "permanently delete object" :table table :id id)
      (some->> (:woff1-file-id font) (sto/touch-object! storage) deref)
      (some->> (:woff2-file-id font) (sto/touch-object! storage) deref)
      (some->> (:otf-file-id font)   (sto/touch-object! storage) deref)
      (some->> (:ttf-file-id font)   (sto/touch-object! storage) deref))
    (count fonts)))

;; --- IMPL: team deletion

(defmethod delete-objects "team"
  [{:keys [conn min-age storage table] :as cfg}]
  (let [sql     (str/fmt sql:delete-objects {:table table :limit 50})
        teams   (db/exec! conn [sql min-age])
        storage (media/configure-assets-storage storage conn)]

    (doseq [{:keys [id] :as team} teams]
      (l/debug :hint "permanently delete object" :table table :id id)
      (some->> (:photo-id team) (sto/touch-object! storage) deref))

    (count teams)))

;; --- IMPL: profile deletion

(def sql:retrieve-deleted-profiles
  "select id, photo_id from profile
    where deleted_at is not null
      and deleted_at < now() - ?::interval
    order by deleted_at
    limit ?
    for update")

(defmethod delete-objects "profile"
  [{:keys [conn min-age storage table] :as cfg}]
  (let [profiles (db/exec! conn [sql:retrieve-deleted-profiles min-age 50])
        storage  (media/configure-assets-storage storage conn)]

    (doseq [{:keys [id] :as profile} profiles]
      (l/debug :hint "permanently delete object" :table table :id id)

      ;; Mark as deleted the storage object related with the photo-id
      ;; field.
      (some->> (:photo-id profile) (sto/touch-object! storage) deref)

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
        (do
          (l/debug :hint "delete summary" :table table :total n)
          n)))))

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::sto/storage]
          :opt-un [::min-age]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (merge {:min-age cf/deletion-delay}
         (d/without-nils cfg)))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [params]
    (db/with-atomic [conn pool]
      (let [min-age (or (:min-age params) (:min-age cfg))
            cfg     (-> cfg
                        (assoc :min-age (db/interval min-age))
                        (assoc :conn conn))]
        (loop [tables (seq target-tables)
               total  0]
          (if-let [table (first tables)]
            (recur (rest tables)
                   (+ total (process-table (assoc cfg :table table))))
            (do
              (l/info :hint "objects gc finished succesfully"
                      :min-age (dt/format-duration min-age)
                      :total total)

              (when (:rollback? params)
                (db/rollback! conn))

              {:processed total})))))))


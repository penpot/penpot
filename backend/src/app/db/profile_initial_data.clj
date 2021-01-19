;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.db.profile-initial-data
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.rpc.mutations.projects :as projects]
   [app.storage :as storage]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]))

(def sql:file
  "select * from file where project_id = ?")

(def sql:file-library-rel
  "with file_ids as (select id from file where project_id = ?)
   select *
     from file_library_rel
     where file_id in (select id from file_ids)")

(def sql:file-media-object
  "with file_ids as (select id from file where project_id = ?)
   select *
     from file_media_object
     where file_id in (select id from file_ids)")

(def sql:file-media-thumbnail
  "with file_ids as (select id from file where project_id = ?),
        media_ids as (select id from file_media_object where file_id in (select id from file_ids))
   select *
     from file_media_thumbnail
     where media_object_id in (select id from media_ids)")

(def sql:storage-object
  "with file_ids as (select id from file where project_id = ?),
        media_ids as (select media_id as id from file_media_object where file_id in (select id from file_ids)),
        thumbs_ids as (select thumbnail_id as id from file_media_object where file_id in (select id from file_ids)),
        storage_ids as (select id from media_ids union select id from thumbs_ids)
   select *
     from storage_object
     where id in (select id from storage_ids)")

(defn change-ids
  "Given a collection and a map from ID to ID. Changes all the `keys` properties
  so they point to the new ID existing in `map-ids`"
  [map-ids coll keys]

  (let [generate-id
        (fn [map-ids {:keys [id]}]
          (assoc map-ids id (uuid/next)))

        remap-key
        (fn [obj map-ids key]
          (cond-> obj
            (contains? obj key)
            (assoc key (get map-ids (get obj key) (get obj key)))))

        change-id
        (fn [map-ids obj]
          (reduce #(remap-key %1 map-ids %2) obj keys))

        new-map-ids (reduce generate-id map-ids coll)]

    [new-map-ids (map (partial change-id new-map-ids) coll)]))

(defn allocate-storage-objects
  "Copies the storage data to a new object and stores the new id into `map-ids`"
  [storage map-ids objects]
  (let [clone-object
        (fn [map-ids object]
          (try
            (let [object (storage/row->storage-object object)
                  new-obj (storage/clone-object storage object)]
              (assoc map-ids (:id object) (:id new-obj)))
            (catch Exception err
              (log/errorf "Error cloning store object %s" (:id object))
              map-ids)))]
    (->> objects
         (reduce clone-object map-ids))))

(defn create-profile-initial-data
  [conn storage profile]

  (when-let [sample-project-id (get cfg/config :initial-data-project-id)]
    (let [sample-project-name (get cfg/config :initial-data-project-name "Penpot Onboarding")

          proj (projects/create-project conn {:profile-id (:id profile)
                                              :team-id (:default-team-id profile)
                                              :name sample-project-name})

          _ (projects/create-project-profile conn {:project-id (:id proj)
                                                   :profile-id (:id profile)})

          _ (projects/create-team-project-profile conn {:team-id (:default-team-id profile)
                                                        :project-id (:id proj)
                                                        :profile-id (:id profile)})

          ;; Retrieve data from templates
          file                 (db/exec! conn [sql:file, sample-project-id])
          file-library-rel     (db/exec! conn [sql:file-library-rel, sample-project-id])
          file-media-object    (db/exec! conn [sql:file-media-object, sample-project-id])
          file-media-thumbnail (db/exec! conn [sql:file-media-thumbnail, sample-project-id])
          storage-object       (db/exec! conn [sql:storage-object, sample-project-id])

          map-ids {}

          ;; Create new ID's and change the references
          [map-ids file]           (change-ids map-ids file #{:id})

          map-ids                  (allocate-storage-objects storage map-ids storage-object)

          [map-ids file-library-rel]     (change-ids map-ids file-library-rel #{:file-id :library-file-id})
          [map-ids file-media-object]    (change-ids map-ids file-media-object #{:id :file-id :media-id :thumbnail-id})
          [map-ids file-media-thumbnail] (change-ids map-ids file-media-thumbnail #{:id :media-object-id})

          file (->> file (map (fn [data] (assoc data :project-id (:id proj)))))
          file-profile-rel (->> file (map (fn [data]
                                            (hash-map :file-id (:id data)
                                                      :profile-id (:id profile)
                                                      :is-owner true
                                                      :is-admin true
                                                      :can-edit true))))]

      ;; Re-insert into the database
      (db/insert-multi! conn :file file)
      (db/insert-multi! conn :file-profile-rel file-profile-rel)
      (db/insert-multi! conn :file-library-rel file-library-rel)
      (db/insert-multi! conn :file-media-object file-media-object)
      (db/insert-multi! conn :file-media-thumbnail file-media-thumbnail))
    {:result "OK"}))

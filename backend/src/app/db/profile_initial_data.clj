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
   [app.util.transit :as tr]
   [clojure.java.io :as io]
   [datoteka.core :as fs]))

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

(defn create-initial-data-dump
  [conn project-id output-path]
  (let [ ;; Retrieve data from templates
        opath                (fs/path output-path)
        file                 (db/exec! conn [sql:file, project-id])
        file-library-rel     (db/exec! conn [sql:file-library-rel, project-id])
        file-media-object    (db/exec! conn [sql:file-media-object, project-id])

        data {:file file
              :file-library-rel file-library-rel
              :file-media-object file-media-object}]
    (with-open [output (io/output-stream opath)]
      (tr/encode-stream data output)
      nil)))

(defn read-initial-data
  [path]
  (when (fs/exists? path)
    (with-open [input (io/input-stream (fs/path path))]
      (tr/decode-stream input))))

(defn create-profile-initial-data
  ([conn profile]
   (when-let [initial-data-path (:initial-data-file cfg/config)]
     (create-profile-initial-data conn initial-data-path profile)))

  ([conn file profile]
   (when-let [{:keys [file file-library-rel file-media-object]} (read-initial-data file)]
     (let [sample-project-name (:initial-data-project-name cfg/config "Penpot Onboarding")

           proj (projects/create-project conn {:profile-id (:id profile)
                                               :team-id (:default-team-id profile)
                                               :name sample-project-name})

           map-ids {}

           ;; Create new ID's and change the references
           [map-ids file]                 (change-ids map-ids file #{:id})
           [map-ids file-library-rel]     (change-ids map-ids file-library-rel #{:file-id :library-file-id})
           [_       file-media-object]    (change-ids map-ids file-media-object #{:id :file-id :media-id :thumbnail-id})

           file             (map #(assoc % :project-id (:id proj)) file)
           file-profile-rel (map #(array-map :file-id (:id %)
                                             :profile-id (:id profile)
                                             :is-owner true
                                             :is-admin true
                                             :can-edit true)
                                 file)]

       (projects/create-project-profile conn {:project-id (:id proj)
                                              :profile-id (:id profile)})

       (projects/create-team-project-profile conn {:team-id (:default-team-id profile)
                                                   :project-id (:id proj)
                                                   :profile-id (:id profile)})

       ;; Re-insert into the database
       (db/insert-multi! conn :file file)
       (db/insert-multi! conn :file-profile-rel file-profile-rel)
       (db/insert-multi! conn :file-library-rel file-library-rel)
       (db/insert-multi! conn :file-media-object file-media-object)))))

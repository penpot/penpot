;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.setup.initial-data
  (:refer-clojure :exclude [load])
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.queries.profile :as profile]))

;; --- DUMP GENERATION

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

(defn dump
  ([system project-id] (dump system project-id nil))
  ([system project-id {:keys [skey project-name]
                       :or {project-name "Penpot Onboarding"}}]
   (db/with-atomic [conn (:app.db/pool system)]
     (let [skey              (or skey (cfg/get :initial-project-skey))
           file              (db/exec! conn [sql:file project-id])
           file-library-rel  (db/exec! conn [sql:file-library-rel project-id])
           file-media-object (db/exec! conn [sql:file-media-object project-id])
           data              {:project-name project-name
                              :file file
                              :file-library-rel file-library-rel
                              :file-media-object file-media-object}]

       (db/delete! conn :server-prop
                   {:id skey})
       (db/insert! conn :server-prop
                   {:id skey
                    :preload false
                    :content (db/tjson data)})
       nil))))


;; --- DUMP LOADING

(defn- remap-ids
  "Given a collection and a map from ID to ID. Changes all the `keys`
  properties so they point to the new ID existing in `map-ids`"
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

(defn- retrieve-data
  [conn skey]
  (when-let [row (db/exec-one! conn ["select content from server_prop where id = ?" skey])]
    (when-let [content (:content row)]
      (when (db/pgobject? content)
        (db/decode-transit-pgobject content)))))

(defn load-initial-project!
  ([conn profile] (load-initial-project! conn profile nil))
  ([conn profile opts]
   (let [skey (or (:skey opts) (cfg/get :initial-project-skey))
         data (retrieve-data conn skey)]
     (when data
       (let [project (projects/create-project conn {:profile-id (:id profile)
                                                    :team-id (:default-team-id profile)
                                                    :name (:project-name data)})

             map-ids {}

             [map-ids file]                 (remap-ids map-ids (:file data) #{:id})
             [map-ids file-library-rel]     (remap-ids map-ids (:file-library-rel data) #{:file-id :library-file-id})
             [_       file-media-object]    (remap-ids map-ids (:file-media-object data) #{:id :file-id :media-id :thumbnail-id})

             file             (map #(assoc % :project-id (:id project)) file)
             file-profile-rel (map #(array-map :file-id (:id %)
                                               :profile-id (:id profile)
                                               :is-owner true
                                               :is-admin true
                                               :can-edit true)
                                   file)]

       (projects/create-project-profile conn {:project-id (:id project)
                                              :profile-id (:id profile)})

       (projects/create-team-project-profile conn {:team-id (:default-team-id profile)
                                                   :project-id (:id project)
                                                   :profile-id (:id profile)})

       ;; Re-insert into the database
       (doseq [params file]
         (db/insert! conn :file params))
       (doseq [params file-profile-rel]
         (db/insert! conn :file-profile-rel params))
       (doseq [params file-library-rel]
         (db/insert! conn :file-library-rel params))
       (doseq [params file-media-object]
         (db/insert! conn :file-media-object params)))))))

(defn load
  [system {:keys [email] :as opts}]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (profile/retrieve-profile-data-by-email conn email)]
      (load-initial-project! conn profile opts)
      true)))


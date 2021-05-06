;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.setup.initial-data
  (:refer-clojure :exclude [load])
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.rpc.mutations.management :refer [duplicate-file]]
   [app.rpc.mutations.projects :refer [create-project create-project-role]]
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
     (let [skey  (or skey (cfg/get :initial-project-skey))
           files (db/exec! conn [sql:file project-id])
           flibs (db/exec! conn [sql:file-library-rel project-id])
           fmeds (db/exec! conn [sql:file-media-object project-id])
           data  {:project-name project-name
                  :files files
                  :flibs flibs
                  :fmeds fmeds}]

       (db/delete! conn :server-prop {:id skey})
       (db/insert! conn :server-prop
                   {:id skey
                    :preload false
                    :content (db/tjson data)})
       skey))))


;; --- DUMP LOADING

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
       (let [index   (reduce #(assoc %1 (:id %2) (uuid/next)) {} (:files data))
             project {:id (uuid/next)
                      :profile-id (:id profile)
                      :team-id (:default-team-id profile)
                      :name (:project-name data)}]

         (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

         (create-project conn project)
         (create-project-role conn {:project-id (:id project)
                                    :profile-id (:id profile)
                                    :role :owner})

         (doseq [file (:files data)]
           (let [params {:profile-id (:id profile)
                         :project-id (:id project)
                         :file file
                         :index index}
                 opts   {:reset-shared-flag false}]
             (duplicate-file conn params opts))))))))

(defn load
  [system {:keys [email] :as opts}]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (some->> email
                                (profile/retrieve-profile-data-by-email conn)
                                (profile/populate-additional-data conn))]
      (load-initial-project! conn profile opts)
      true)))


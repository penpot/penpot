;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.fixtures
  "A initial fixtures."
  (:require
   [clojure.tools.logging :as log]
   [sodi.pwhash :as pwhash]
   [mount.core :as mount]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.common.pages :as cp]
   [uxbox.common.data :as d]
   [uxbox.core]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.migrations]
   [uxbox.services.mutations.profile :as mt.profile]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [vertx.util :as vu]))

(defn- mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/oid (apply str prefix (interpose "-" args))))

;; --- Profiles creation

(def password (pwhash/derive "123123"))

(def sql:create-team
  "insert into team (id, name, photo)
   values ($1, $2, $3)
   returning *;")

(def sql:create-team-profile
  "insert into team_profile_rel (team_id, profile_id, is_owner, is_admin, can_edit)
   values ($1, $2, $3, $4, $5)
   returning *;")

(def sql:create-project
  "insert into project (id, team_id, name)
   values ($1, $2, $3)
   returning *;")

(def sql:create-project-profile
  "insert into project_profile_rel (project_id, profile_id, is_owner, is_admin, can_edit)
   values ($1, $2, $3, $4, $5)
   returning *")

(def sql:create-file-profile
  "insert into file_profile_rel (file_id, profile_id, is_owner, is_admin, can_edit)
   values ($1, $2, $3, $4, $5)
   returning *")

(def sql:create-file
  "insert into file (id, project_id, name)
   values ($1, $2, $3 ) returning *")

(def sql:create-page
  "insert into page (id, file_id, name,
                     version, ordering, data)
   values ($1, $2, $3, $4, $5, $6)
   returning id;")

(def sql:create-icon-library
  "insert into icon_library (team_id, name) 
   values ($1, $2)
   returning id;")

(def sql:create-icon
  "insert into icon_library (library_id, name, content, metadata)
   values ($1, $2, $3, $4)
   returning id;")


(def preset-small
  {:num-teams 5
   :num-profiles 5
   :num-profiles-per-team 5
   :num-projects-per-team 5
   :num-files-per-project 5
   :num-pages-per-file 3
   :num-draft-files-per-profile 10
   :num-draft-pages-per-file 3})

(defn rng-ids
  [rng n max]
  (let [stream (->> (.longs rng 0 max)
                    (.iterator)
                    (iterator-seq))]
    (reduce (fn [acc item]
              (if (= (count acc) n)
                (reduced acc)
                (conj acc item)))
            #{}
            stream)))

(defn rng-vec
  [rng vdata n]
  (let [ids (rng-ids rng n (count vdata))]
    (mapv #(nth vdata %) ids)))

(defn rng-nth
  [rng vdata]
  (let [stream (->> (.longs rng 0 (count vdata))
                    (.iterator)
                    (iterator-seq))]
    (nth vdata (first stream))))

(defn collect
  [f items]
  (reduce (fn [acc n]
            (p/then acc (fn [acc]
                          (p/then (f n)
                                  (fn [res]
                                    (conj acc res))))))
          (p/promise [])
          items))

(defn run
  [opts]
  (let [rng (java.util.Random. 1)

        create-profile
        (fn [conn index]
          (let [id (mk-uuid "profile" index)]
            (log/info "create profile" id)
            (mt.profile/register-profile conn
                                         {:id id
                                          :fullname (str "Profile " index)
                                          :password "123123"
                                          :demo? true
                                          :email (str "profile" index ".test@uxbox.io")})))

        create-profiles
        (fn [conn]
          (log/info "create profiles")
          (collect (partial create-profile conn)
                   (range (:num-profiles opts))))

        create-team
        (fn [conn index]
          (let [sql sql:create-team
                id (mk-uuid "team" index)
                name (str "Team" index)]
            (log/info "create team" id)

            (-> (db/query-one conn [sql id name ""])
                (p/then (constantly id)))))

        create-teams
        (fn [conn]
          (log/info "create teams")
          (collect (partial create-team conn)
                   (range (:num-teams opts))))

        create-page
        (fn [conn owner-id project-id file-id index]
          (p/let [id (mk-uuid "page" project-id file-id index)
                  data cp/default-page-data
                  name (str "page " index)
                  version 0
                  ordering index
                  data (blob/encode data)]
            (log/info "create page" id)
            (db/query-one conn [sql:create-page
                                id file-id name version ordering data])))

        create-pages
        (fn [conn owner-id project-id file-id]
          (log/info "create pages")
          (p/run! (partial create-page conn owner-id project-id file-id)
                  (range (:num-pages-per-file opts))))

        create-file
        (fn [conn owner-id project-id index]
          (p/let [id (mk-uuid "file" project-id index)
                  name (str "file" index)]
            (log/info "create file" id)
            (db/query-one conn [sql:create-file id project-id name])
            (db/query-one conn [sql:create-file-profile
                                id owner-id true true true])
            id))

        create-files
        (fn [conn owner-id project-id]
          (log/info "create files")
          (p/let [file-ids (collect (partial create-file conn owner-id project-id)
                                    (range (:num-files-per-project opts)))]
            (p/run! (partial create-pages conn owner-id project-id) file-ids)))

        create-project
        (fn [conn team-id owner-id index]
          (p/let [id (mk-uuid "project" team-id index)
                  name (str "project " index)]
            (log/info "create project" id)
            (db/query-one conn [sql:create-project id team-id name])
            (db/query-one conn [sql:create-project-profile
                                id owner-id true true true])
            id))

        create-projects
        (fn [conn team-id profile-ids]
          (log/info "create projects")
          (p/let [owner-id (rng-nth rng profile-ids)
                  project-ids (collect (partial create-project conn team-id owner-id)
                                       (range (:num-projects-per-team opts)))]
            (p/run! (partial create-files conn owner-id) project-ids)))

        assign-profile-to-team
        (fn [conn team-id owner? profile-id]
          (let [sql sql:create-team-profile]
            (db/query-one conn [sql team-id profile-id owner? true true])))

        setup-team
        (fn [conn team-id profile-ids]
          (log/info "setup team" team-id profile-ids)
          (p/do!
           (assign-profile-to-team conn team-id true (first profile-ids))
           (p/run! (partial assign-profile-to-team conn team-id false)
                   (rest profile-ids))
           (create-projects conn team-id profile-ids)))

        assign-teams-and-profiles
        (fn [conn teams profiles]
          (log/info "assign teams and profiles")
          (vu/loop [team-id (first teams)
                    teams (rest teams)]
            (when-not (nil? team-id)
              (p/let [n-profiles-team (:num-profiles-per-team opts)
                      selected-profiles (rng-vec rng profiles n-profiles-team)]
                (setup-team conn team-id selected-profiles)
                (p/recur (first teams)
                         (rest teams))))))


        create-draft-pages
        (fn [conn owner-id file-id]
          (log/info "create draft pages")
          (p/run! (partial create-page conn owner-id nil file-id)
                  (range (:num-draft-pages-per-file opts))))

        create-draft-file
        (fn [conn owner index]
          (p/let [owner-id (:id owner)
                  id (mk-uuid "file" "draft" owner-id index)
                  name (str "file" index)
                  project-id (:id (:default-project owner))]
            (log/info "create draft file" id)
            (db/query-one conn [sql:create-file id project-id name])
            (db/query-one conn [sql:create-file-profile
                                id owner-id true true true])
            id))

        create-draft-files
        (fn [conn profile]
          (p/let [file-ids (collect (partial create-draft-file conn profile)
                                    (range (:num-draft-files-per-profile opts)))]
            (p/run! (partial create-draft-pages conn (:id profile)) file-ids)))
        ]

    (db/with-atomic [conn db/pool]
      (p/let [profiles (create-profiles conn)
              teams    (create-teams conn)]
        (assign-teams-and-profiles conn teams (map :id profiles))
        (p/run! (partial create-draft-files conn) profiles)))))

(defn -main
  [& args]
  (try
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.core/system
                      #'uxbox.db/pool
                      #'uxbox.migrations/migrations})
        (mount/start))
    (let [preset (case (first args)
                   (nil "small") preset-small
                   ;; "medium" preset-medium
                   ;; "big" preset-big
                   preset-small)]
      (log/info "Using preset:" (pr-str preset))
      (deref (run preset)))
    (finally
      (mount/stop))))

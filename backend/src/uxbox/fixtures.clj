;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.fixtures
  "A initial fixtures."
  (:require
   [clojure.tools.logging :as log]
   [mount.core :as mount]
   [sodi.pwhash :as pwhash]
   [uxbox.common.data :as d]
   [uxbox.common.pages :as cp]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.migrations]
   [uxbox.services.mutations.profile :as profile]
   [uxbox.util.blob :as blob]))

(defn- mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/zero (apply str prefix (interpose "-" args))))

;; --- Profiles creation

(def password (pwhash/derive "123123"))

(def preset-small
  {:num-teams 5
   :num-profiles 5
   :num-profiles-per-team 5
   :num-projects-per-team 5
   :num-files-per-project 5
   :num-pages-per-file 3
   :num-draft-files-per-profile 10
   :num-draft-pages-per-file 3})

(defn- rng-ids
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

(defn- rng-vec
  [rng vdata n]
  (let [ids (rng-ids rng n (count vdata))]
    (mapv #(nth vdata %) ids)))

(defn- rng-nth
  [rng vdata]
  (let [stream (->> (.longs rng 0 (count vdata))
                    (.iterator)
                    (iterator-seq))]
    (nth vdata (first stream))))

(defn- collect
  [f items]
  (reduce #(conj %1 (f %2)) [] items))

(defn- register-profile
  [conn params]
  (->> (#'profile/create-profile conn params)
       (#'profile/create-profile-relations conn)))

(defn impl-run
  [opts]
  (let [rng (java.util.Random. 1)

        create-profile
        (fn [conn index]
          (let [id (mk-uuid "profile" index)]
            (log/info "create profile" id)
            (register-profile conn
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
          (let [id (mk-uuid "team" index)
                name (str "Team" index)]
            (log/info "create team" id)
            (db/insert! conn :team {:id id
                                    :name name
                                    :photo ""})
            id))

        create-teams
        (fn [conn]
          (log/info "create teams")
          (collect (partial create-team conn)
                   (range (:num-teams opts))))

        create-page
        (fn [conn owner-id project-id file-id index]
          (let [id (mk-uuid "page" project-id file-id index)
                data cp/default-page-data
                name (str "page " index)
                version 0
                ordering index
                data (blob/encode data)]
            (log/info "create page" id)
            (db/insert! conn :page
                        {:id id
                         :file-id file-id
                         :name name
                         :ordering ordering
                         :data data})))

        create-pages
        (fn [conn owner-id project-id file-id]
          (log/info "create pages")
          (run! (partial create-page conn owner-id project-id file-id)
                (range (:num-pages-per-file opts))))

        create-file
        (fn [conn owner-id project-id index]
          (let [id (mk-uuid "file" project-id index)
                name (str "file" index)]
            (log/info "create file" id)
            (db/insert! conn :file
                        {:id id
                         :project-id project-id
                         :name name})
            (db/insert! conn :file-profile-rel
                        {:file-id id
                         :profile-id owner-id
                         :is-owner true
                         :is-admin true
                         :can-edit true})
            id))

        create-files
        (fn [conn owner-id project-id]
          (log/info "create files")
          (let [file-ids (collect (partial create-file conn owner-id project-id)
                                  (range (:num-files-per-project opts)))]
            (run! (partial create-pages conn owner-id project-id) file-ids)))

        create-project
        (fn [conn team-id owner-id index]
          (let [id (mk-uuid "project" team-id index)
                name (str "project " index)]
            (log/info "create project" id)
            (db/insert! conn :project
                        {:id id
                         :team-id team-id
                         :name name})
            (db/insert! conn :project-profile-rel
                        {:project-id id
                         :profile-id owner-id
                         :is-owner true
                         :is-admin true
                         :can-edit true})
            id))

        create-projects
        (fn [conn team-id profile-ids]
          (log/info "create projects")
          (let [owner-id (rng-nth rng profile-ids)
                project-ids (collect (partial create-project conn team-id owner-id)
                                     (range (:num-projects-per-team opts)))]
            (run! (partial create-files conn owner-id) project-ids)))

        assign-profile-to-team
        (fn [conn team-id owner? profile-id]
          (db/insert! conn :team-profile-rel
                      {:team-id team-id
                       :profile-id profile-id
                       :is-owner owner?
                       :is-admin true
                       :can-edit true}))

        setup-team
        (fn [conn team-id profile-ids]
          (log/info "setup team" team-id profile-ids)
          (assign-profile-to-team conn team-id true (first profile-ids))
          (run! (partial assign-profile-to-team conn team-id false)
                (rest profile-ids))
          (create-projects conn team-id profile-ids))

        assign-teams-and-profiles
        (fn [conn teams profiles]
          (log/info "assign teams and profiles")
          (loop [team-id (first teams)
                 teams (rest teams)]
            (when-not (nil? team-id)
              (let [n-profiles-team (:num-profiles-per-team opts)
                    selected-profiles (rng-vec rng profiles n-profiles-team)]
                (setup-team conn team-id selected-profiles)
                (recur (first teams)
                       (rest teams))))))


        create-draft-pages
        (fn [conn owner-id file-id]
          (log/info "create draft pages")
          (run! (partial create-page conn owner-id nil file-id)
                (range (:num-draft-pages-per-file opts))))

        create-draft-file
        (fn [conn owner index]
          (let [owner-id (:id owner)
                id (mk-uuid "file" "draft" owner-id index)
                name (str "file" index)
                project-id (:default-project-id owner)]
            (log/info "create draft file" id)
            (db/insert! conn :file
                        {:id id
                         :project-id project-id
                         :name name})
            (db/insert! conn :file-profile-rel
                        {:file-id id
                         :profile-id owner-id
                         :is-owner true
                         :is-admin true
                         :can-edit true})
            id))

        create-draft-files
        (fn [conn profile]
          (let [file-ids (collect (partial create-draft-file conn profile)
                                    (range (:num-draft-files-per-profile opts)))]
            (run! (partial create-draft-pages conn (:id profile)) file-ids)))
        ]

    (db/with-atomic [conn db/pool]
      (let [profiles (create-profiles conn)
            teams    (create-teams conn)]
        (assign-teams-and-profiles conn teams (map :id profiles))
        (run! (partial create-draft-files conn) profiles)))))

(defn run
  [preset]
  (let [preset (if (map? preset)
                 preset
                 (case preset
                   (nil "small" :small) preset-small
                   ;; "medium" preset-medium
                   ;; "big" preset-big
                   preset-small))]
    (impl-run preset)))

(defn -main
  [& args]
  (try
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.db/pool
                      #'uxbox.migrations/migrations})
        (mount/start))
    (run (first args))
    (finally
      (mount/stop))))

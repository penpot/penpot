;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.cli.fixtures
  "A initial fixtures."
  (:require
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.main :as main]
   [app.rpc.mutations.profile :as profile]
   [app.util.blob :as blob]
   [app.util.logging :as l]
   [buddy.hashers :as hashers]
   [integrant.core :as ig]))

(defn- mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/zero (apply str prefix (interpose "-" args))))

;; --- Profiles creation

(def password (hashers/derive "123123"))

(def preset-small
  {:num-teams 5
   :num-profiles 5
   :num-profiles-per-team 5
   :num-projects-per-team 5
   :num-files-per-project 5
   :num-draft-files-per-profile 10})

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
  [pool opts]
  (let [rng (java.util.Random. 1)]
    (letfn [(create-profile [conn index]
              (let [id   (mk-uuid "profile" index)
                    _    (l/info :action "create profile"
                                 :index index
                                 :id id)

                    prof (register-profile conn
                                           {:id id
                                            :fullname (str "Profile " index)
                                            :password "123123"
                                            :is-demo true
                                            :email (str "profile" index "@example.com")})
                    team-id  (:default-team-id prof)
                    owner-id id]
                (let [project-ids (collect (partial create-project conn team-id owner-id)
                                           (range (:num-projects-per-team opts)))]
                  (run! (partial create-files conn owner-id) project-ids))
                prof))

            (create-profiles [conn]
              (l/info :action "create profiles")
              (collect (partial create-profile conn)
                       (range (:num-profiles opts))))

            (create-team [conn index]
              (let [id (mk-uuid "team" index)
                    name (str "Team" index)]
                (l/info :action "create team"
                        :index index
                        :id id)
                (db/insert! conn :team {:id id
                                        :name name})
                id))

            (create-teams [conn]
              (l/info :action "create teams")
              (collect (partial create-team conn)
                       (range (:num-teams opts))))

            (create-file [conn owner-id project-id index]
              (let [id (mk-uuid "file" project-id index)
                    name (str "file" index)
                    data (cp/make-file-data id)]
                (l/info :action "create file"
                        :index index
                        :id id)
                (db/insert! conn :file
                            {:id id
                             :data (blob/encode data)
                             :project-id project-id
                             :name name})
                (db/insert! conn :file-profile-rel
                            {:file-id id
                             :profile-id owner-id
                             :is-owner true
                             :is-admin true
                             :can-edit true})
                id))

            (create-files [conn owner-id project-id]
              (l/info :action "create files")
              (run! (partial create-file conn owner-id project-id)
                    (range (:num-files-per-project opts))))

            (create-project [conn team-id owner-id index]
              (let [id        (if index
                                (mk-uuid "project" team-id index)
                                (mk-uuid "project" team-id))
                    name      (if index
                                (str "project " index)
                                "Drafts")
                    is-default (nil? index)]
                (l/info :action "create project"
                        :index index
                        :id id)
                (db/insert! conn :project
                            {:id id
                             :team-id team-id
                             :is-default is-default
                             :name name})
                (db/insert! conn :project-profile-rel
                            {:project-id id
                             :profile-id owner-id
                             :is-owner true
                             :is-admin true
                             :can-edit true})
                id))

            (create-projects [conn team-id profile-ids]
              (l/info :action "create projects")
              (let [owner-id (rng-nth rng profile-ids)
                    project-ids (conj
                                  (collect (partial create-project conn team-id owner-id)
                                         (range (:num-projects-per-team opts)))
                                  (create-project conn team-id owner-id nil))]
                (run! (partial create-files conn owner-id) project-ids)))

            (assign-profile-to-team [conn team-id owner? profile-id]
              (db/insert! conn :team-profile-rel
                          {:team-id team-id
                           :profile-id profile-id
                           :is-owner owner?
                           :is-admin true
                           :can-edit true}))

            (setup-team [conn team-id profile-ids]
              (l/info :action "setup team"
                      :team-id team-id
                      :profile-ids (pr-str profile-ids))
              (assign-profile-to-team conn team-id true (first profile-ids))
              (run! (partial assign-profile-to-team conn team-id false)
                    (rest profile-ids))
              (create-projects conn team-id profile-ids))

            (assign-teams-and-profiles [conn teams profiles]
              (l/info :action "assign teams and profiles")
              (loop [team-id (first teams)
                     teams (rest teams)]
                (when-not (nil? team-id)
                  (let [n-profiles-team (:num-profiles-per-team opts)
                        selected-profiles (rng-vec rng profiles n-profiles-team)]
                    (setup-team conn team-id selected-profiles)
                    (recur (first teams)
                           (rest teams))))))

            (create-draft-file [conn owner index]
              (let [owner-id   (:id owner)
                    id         (mk-uuid "file" "draft" owner-id index)
                    name       (str "file" index)
                    project-id (:default-project-id owner)
                    data       (cp/make-file-data id)]

                (l/info :action "create draft file"
                        :index index
                        :id id)
                (db/insert! conn :file
                            {:id id
                             :data (blob/encode data)
                             :project-id project-id
                             :name name})
                (db/insert! conn :file-profile-rel
                            {:file-id id
                             :profile-id owner-id
                             :is-owner true
                             :is-admin true
                             :can-edit true})
                id))

            (create-draft-files [conn profile]
              (run! (partial create-draft-file conn profile)
                    (range (:num-draft-files-per-profile opts))))
            ]
      (db/with-atomic [conn pool]
        (let [profiles (create-profiles conn)
              teams    (create-teams conn)]
          (assign-teams-and-profiles conn teams (map :id profiles))
          (run! (partial create-draft-files conn) profiles))))))

(defn run-in-system
  [system preset]
  (let [pool   (:app.db/pool system)
        preset (if (map? preset)
                 preset
                 (case preset
                   (nil "small" :small) preset-small
                   ;; "medium" preset-medium
                   ;; "big" preset-big
                   preset-small))]
    (impl-run pool preset)))

(defn run
  [{:keys [preset] :or {preset :small}}]
  (let [config (select-keys main/system-config
                            [:app.db/pool
                             :app.telemetry/migrations
                             :app.migrations/migrations
                             :app.migrations/all
                             :app.metrics/metrics])
        _      (ig/load-namespaces config)
        system (-> (ig/prep config)
                   (ig/init))]
    (try
      (run-in-system system preset)
      (catch Exception e
        (l/error :hint "unhandled exception" :cause e))
      (finally
        (ig/halt! system)))))

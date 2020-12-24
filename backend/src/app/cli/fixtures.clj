;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.cli.fixtures
  "A initial fixtures."
  (:require
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.main :as main]
   [app.rpc.mutations.profile :as profile]
   [app.util.blob :as blob]
   [buddy.hashers :as hashers]
   [clojure.tools.logging :as log]
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
                    _    (log/info "create profile" id)

                    prof (register-profile conn
                                           {:id id
                                            :fullname (str "Profile " index)
                                            :password "123123"
                                            :demo? true
                                            :email (str "profile" index ".test@penpot.app")})
                    team-id  (:default-team-id prof)
                    owner-id id]
                (let [project-ids (collect (partial create-project conn team-id owner-id)
                                           (range (:num-projects-per-team opts)))]
                  (run! (partial create-files conn owner-id) project-ids))
                prof))

            (create-profiles [conn]
              (log/info "create profiles")
              (collect (partial create-profile conn)
                       (range (:num-profiles opts))))

            (create-team [conn index]
              (let [id (mk-uuid "team" index)
                    name (str "Team" index)]
                (log/info "create team" id)
                (db/insert! conn :team {:id id
                                        :name name
                                        :photo ""})
                id))

            (create-teams [conn]
              (log/info "create teams")
              (collect (partial create-team conn)
                       (range (:num-teams opts))))

            (create-file [conn owner-id project-id index]
              (let [id (mk-uuid "file" project-id index)
                    name (str "file" index)
                    data (cp/make-file-data id)]
                (log/info "create file" id)
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
              (log/info "create files")
              (run! (partial create-file conn owner-id project-id)
                    (range (:num-files-per-project opts))))

            (create-project [conn team-id owner-id index]
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

            (create-projects [conn team-id profile-ids]
              (log/info "create projects")
              (let [owner-id (rng-nth rng profile-ids)
                    project-ids (collect (partial create-project conn team-id owner-id)
                                         (range (:num-projects-per-team opts)))]
                (run! (partial create-files conn owner-id) project-ids)))

            (assign-profile-to-team [conn team-id owner? profile-id]
              (db/insert! conn :team-profile-rel
                          {:team-id team-id
                           :profile-id profile-id
                           :is-owner owner?
                           :is-admin true
                           :can-edit true}))

            (setup-team [conn team-id profile-ids]
              (log/info "setup team" team-id profile-ids)
              (assign-profile-to-team conn team-id true (first profile-ids))
              (run! (partial assign-profile-to-team conn team-id false)
                    (rest profile-ids))
              (create-projects conn team-id profile-ids))

            (assign-teams-and-profiles [conn teams profiles]
              (log/info "assign teams and profiles")
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

                (log/info "create draft file" id)
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
  (let [config (select-keys (main/build-system-config cfg/config)
                            [:app.db/pool
                             :app.migrations/migrations
                             :app.metrics/metrics])
        _      (ig/load-namespaces config)
        system (-> (ig/prep config)
                   (ig/init))]
    (try
      (run-in-system system preset)
      (catch Exception e
        (log/errorf e "Unhandled exception."))
      (finally
        (ig/halt! system)))))

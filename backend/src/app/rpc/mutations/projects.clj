;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.projects
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)

;; --- Mutation: Create Project

(s/def ::team-id ::us/uuid)
(s/def ::create-project
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sv/defmethod ::create-project
  {::doc/added "1.0"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (quotes/check-quote! conn {::quotes/id ::quotes/projects-per-team
                               ::quotes/profile-id profile-id
                               ::quotes/team-id team-id})

    (let [project (teams/create-project conn params)]
      (teams/create-project-role conn profile-id (:id project) :owner)

      (db/insert! conn :team-project-profile-rel
                  {:project-id (:id project)
                   :profile-id profile-id
                   :team-id team-id
                   :is-pinned true})

      (assoc project :is-pinned true))))

;; --- Mutation: Toggle Project Pin

(def ^:private
  sql:update-project-pin
  "insert into team_project_profile_rel (team_id, project_id, profile_id, is_pinned)
   values (?, ?, ?, ?)
       on conflict (team_id, project_id, profile_id)
       do update set is_pinned=?")

(s/def ::is-pinned ::us/boolean)
(s/def ::project-id ::us/uuid)

(s/def ::update-project-pin
  (s/keys :req-un [::profile-id ::id ::team-id ::is-pinned]))

(sv/defmethod ::update-project-pin
  {::doc/added "1.0"
   ::doc/deprecated "1.18"
   ::webhooks/batch-timeout (dt/duration "5s")
   ::webhooks/batch-key :id
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [id profile-id team-id is-pinned] :as params}]
  (db/with-atomic [conn pool]
    (projects/check-edition-permissions! conn profile-id id)
    (db/exec-one! conn [sql:update-project-pin team-id id profile-id is-pinned is-pinned])
    nil))

;; --- Mutation: Rename Project

(declare rename-project)

(s/def ::rename-project
  (s/keys :req-un [::profile-id ::name ::id]))

(sv/defmethod ::rename-project
  {::doc/added "1.0"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [id profile-id name] :as params}]
  (db/with-atomic [conn pool]
    (projects/check-edition-permissions! conn profile-id id)
    (let [project (db/get-by-id conn :project id)]
      (db/update! conn :project
                  {:name name}
                  {:id id})

      (rph/with-meta (rph/wrap)
        {::audit/props {:team-id (:team-id project)
                        :prev-name (:name project)}}))))

;; --- Mutation: Delete Project

(s/def ::delete-project
  (s/keys :req-un [::id ::profile-id]))

;; TODO: right now, we just don't allow delete default projects, in a
;; future we need to ensure raise a correct exception signaling that
;; this is not allowed.

(sv/defmethod ::delete-project
  {::doc/added "1.0"
   ::doc/deprecated "1.18"
   ::webhooks/event? true}
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (projects/check-edition-permissions! conn profile-id id)
    (let [project (db/update! conn :project
                              {:deleted-at (dt/now)}
                              {:id id :is-default false})]
      (rph/with-meta (rph/wrap)
        {::audit/props {:team-id (:team-id project)
                        :name (:name project)
                        :created-at (:created-at project)
                        :modified-at (:modified-at project)}}))))

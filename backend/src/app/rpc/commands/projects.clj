;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.projects
  (:require
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]))

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)

;; --- Check Project Permissions

(def ^:private sql:project-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    where p.id = ?
      and tpr.profile_id = ?
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    where ppr.project_id = ?
      and ppr.profile_id = ?")

(defn- get-permissions
  [conn profile-id project-id]
  (let [rows     (db/exec! conn [sql:project-permissions
                                 project-id profile-id
                                 project-id profile-id])
        is-owner (boolean (some :is-owner rows))
        is-admin (boolean (some :is-admin rows))
        can-edit (boolean (some :can-edit rows))]
     (when (seq rows)
       {:is-owner is-owner
        :is-admin (or is-owner is-admin)
        :can-edit (or is-owner is-admin can-edit)
        :can-read true})))

(def has-edit-permissions?
  (perms/make-edition-predicate-fn get-permissions))

(def has-read-permissions?
  (perms/make-read-predicate-fn get-permissions))

(def check-edition-permissions!
  (perms/make-check-fn has-edit-permissions?))

(def check-read-permissions!
  (perms/make-check-fn has-read-permissions?))

;; --- QUERY: Get projects

(declare get-projects)

(s/def ::team-id ::us/uuid)
(s/def ::get-projects
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(sv/defmethod ::get-projects
  {::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (get-projects conn profile-id team-id)))

(def sql:projects
  "select p.*,
          coalesce(tpp.is_pinned, false) as is_pinned,
          (select count(*) from file as f
            where f.project_id = p.id
              and deleted_at is null) as count
     from project as p
    inner join team as t on (t.id = p.team_id)
     left join team_project_profile_rel as tpp
            on (tpp.project_id = p.id and
                tpp.team_id = p.team_id and
                tpp.profile_id = ?)
    where p.team_id = ?
      and p.deleted_at is null
      and t.deleted_at is null
    order by p.modified_at desc")

(defn get-projects
  [conn profile-id team-id]
  (db/exec! conn [sql:projects profile-id team-id]))

;; --- QUERY: Get all projects

(declare get-all-projects)

(s/def ::get-all-projects
  (s/keys :req [::rpc/profile-id]))

(sv/defmethod ::get-all-projects
  {::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (dm/with-open [conn (db/open pool)]
    (get-all-projects conn profile-id)))

(def sql:all-projects
  "select p1.*, t.name as team_name, t.is_default as is_default_team
     from project as p1
    inner join team as t on (t.id = p1.team_id)
    where t.id in (select team_id
                     from team_profile_rel as tpr
                    where tpr.profile_id = ?
                      and (tpr.can_edit = true or
                           tpr.is_owner = true or
                           tpr.is_admin = true))
      and t.deleted_at is null
      and p1.deleted_at is null
   union
   select p2.*, t.name as team_name, t.is_default as is_default_team
     from project as p2
    inner join team as t on (t.id = p2.team_id)
    where p2.id in (select project_id
                     from project_profile_rel as ppr
                    where ppr.profile_id = ?
                      and (ppr.can_edit = true or
                           ppr.is_owner = true or
                           ppr.is_admin = true))
      and t.deleted_at is null
      and p2.deleted_at is null
    order by team_name, name;")

(defn get-all-projects
  [conn profile-id]
  (db/exec! conn [sql:all-projects profile-id profile-id]))


;; --- QUERY: Get project

(s/def ::get-project
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]))

(sv/defmethod ::get-project
  {::doc/added "1.18"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (dm/with-open [conn (db/open pool)]
    (let [project (db/get-by-id conn :project id)]
      (check-read-permissions! conn profile-id id)
      project)))



;; --- MUTATION: Create Project

(s/def ::create-project
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::name]
          :opt-un [::id]))

(sv/defmethod ::create-project
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (quotes/check-quote! conn {::quotes/id ::quotes/projects-per-team
                               ::quotes/profile-id profile-id
                               ::quotes/team-id team-id})

    (let [params  (assoc params :profile-id profile-id)
          project (teams/create-project conn params)]
      (teams/create-project-role conn profile-id (:id project) :owner)
      (db/insert! conn :team-project-profile-rel
                  {:project-id (:id project)
                   :profile-id profile-id
                   :team-id team-id
                   :is-pinned true})
      (assoc project :is-pinned true))))


;; --- MUTATION: Toggle Project Pin

(def ^:private
  sql:update-project-pin
  "insert into team_project_profile_rel (team_id, project_id, profile_id, is_pinned)
   values (?, ?, ?, ?)
       on conflict (team_id, project_id, profile_id)
       do update set is_pinned=?")

(s/def ::is-pinned ::us/boolean)
(s/def ::project-id ::us/uuid)
(s/def ::update-project-pin
  (s/keys :req [::rpc/profile-id]
          :req-un [::id ::team-id ::is-pinned]))

(sv/defmethod ::update-project-pin
  {::doc/added "1.18"
   ::webhooks/batch-timeout (dt/duration "5s")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id team-id is-pinned] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (db/exec-one! conn [sql:update-project-pin team-id id profile-id is-pinned is-pinned])
    nil))

;; --- MUTATION: Rename Project

(declare rename-project)

(s/def ::rename-project
  (s/keys :req [::rpc/profile-id]
          :req-un [::name ::id]))

(sv/defmethod ::rename-project
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id name] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (let [project (db/get-by-id conn :project id ::db/for-update? true)]
      (db/update! conn :project
                  {:name name}
                  {:id id})
      (rph/with-meta (rph/wrap)
        {::audit/props {:team-id (:team-id project)
                        :prev-name (:name project)}}))))

;; --- MUTATION: Delete Project

(s/def ::delete-project
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]))

;; TODO: right now, we just don't allow delete default projects, in a
;; future we need to ensure raise a correct exception signaling that
;; this is not allowed.

(sv/defmethod ::delete-project
  {::doc/added "1.18"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (let [project (db/update! conn :project
                              {:deleted-at (dt/now)}
                              {:id id :is-default false})]
      (rph/with-meta (rph/wrap)
        {::audit/props {:team-id (:team-id project)
                        :name (:name project)
                        :created-at (:created-at project)
                        :modified-at (:modified-at project)}}))))



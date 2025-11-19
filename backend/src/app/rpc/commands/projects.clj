;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.projects
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.logical-deletion :as ldel]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]
   [app.worker :as wrk]))

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

(def ^:private sql:projects
  "SELECT p.*,
          coalesce(tpp.is_pinned, false) as is_pinned,
          (SELECT count(*) FROM file AS f
            WHERE f.project_id = p.id
              AND f.deleted_at is null) AS count,
          (SELECT count(*) FROM file AS f
            WHERE f.project_id = p.id) AS total_count
     FROM project AS p
    INNER JOIN team AS t ON (t.id = p.team_id)
     LEFT JOIN team_project_profile_rel AS tpp
            ON (tpp.project_id = p.id AND
                tpp.team_id = p.team_id AND
                tpp.profile_id = ?)
    WHERE p.team_id = ?
      AND t.deleted_at is null
    ORDER BY p.modified_at DESC")

(defn get-projects
  [conn profile-id team-id]
  (db/exec! conn [sql:projects profile-id team-id]))

(def ^:private schema:get-projects
  [:map {:title "get-projects"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-projects
  {::doc/added "1.18"
   ::doc/changes [["2.12" "This endpoint now return deleted but recoverable projects"]]
   ::sm/params schema:get-projects}
  [cfg {:keys [::rpc/profile-id team-id]}]
  (teams/check-read-permissions! cfg profile-id team-id)
  (get-projects cfg profile-id team-id))

;; --- QUERY: Get all projects

(declare get-all-projects)

(def ^:private schema:get-all-projects
  [:map {:title "get-all-projects"}])

(sv/defmethod ::get-all-projects
  {::doc/added "1.18"
   ::sm/params schema:get-all-projects}
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

(def ^:private schema:get-project
  [:map {:title "get-project"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-project
  {::doc/added "1.18"
   ::sm/params schema:get-project}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (dm/with-open [conn (db/open pool)]
    (let [project (db/get-by-id conn :project id)]
      (check-read-permissions! conn profile-id id)
      project)))



;; --- MUTATION: Create Project

(defn- create-project
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/request-at profile-id team-id] :as params}]
  (assert (ct/inst? request-at) "expect request-at assigned")
  (let [params    (-> params
                      (assoc :created-at request-at)
                      (assoc :modified-at request-at))
        project   (teams/create-project conn params)
        timestamp (::rpc/request-at params)]
    (teams/create-project-role conn profile-id (:id project) :owner)
    (db/insert! conn :team-project-profile-rel
                {:project-id (:id project)
                 :profile-id profile-id
                 :created-at timestamp
                 :modified-at timestamp
                 :team-id team-id
                 :is-pinned false})
    (assoc project :is-pinned false)))

(def ^:private schema:create-project
  [:map {:title "create-project"}
   [:team-id ::sm/uuid]
   [:name [:string {:max 250 :min 1}]]
   [:id {:optional true} ::sm/uuid]])

(sv/defmethod ::create-project
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:create-project}
  [cfg {:keys [::rpc/profile-id team-id] :as params}]

  (teams/check-edition-permissions! cfg profile-id team-id)
  (quotes/check! cfg {::quotes/id ::quotes/projects-per-team
                      ::quotes/profile-id profile-id
                      ::quotes/team-id team-id})

  (let [params (assoc params :profile-id profile-id)]
    (db/tx-run! cfg create-project params)))

;; --- MUTATION: Toggle Project Pin

(def ^:private
  sql:update-project-pin
  "insert into team_project_profile_rel (team_id, project_id, profile_id, is_pinned)
   values (?, ?, ?, ?)
       on conflict (team_id, project_id, profile_id)
       do update set is_pinned=?")

(def ^:private schema:update-project-pin
  [:map {:title "update-project-pin"}
   [:team-id ::sm/uuid]
   [:is-pinned ::sm/boolean]
   [:id ::sm/uuid]])

(sv/defmethod ::update-project-pin
  {::doc/added "1.18"
   ::sm/params schema:update-project-pin
   ::webhooks/batch-timeout (ct/duration "5s")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)
   ::webhooks/event? true
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id team-id is-pinned] :as params}]
  (check-read-permissions! conn profile-id id)
  (db/exec-one! conn [sql:update-project-pin team-id id profile-id is-pinned is-pinned])
  nil)

;; --- MUTATION: Rename Project

(declare rename-project)

(def ^:private schema:rename-project
  [:map {:title "rename-project"}
   [:name [:string {:max 250 :min 1}]]
   [:id ::sm/uuid]])

(sv/defmethod ::rename-project
  {::doc/added "1.18"
   ::sm/params schema:rename-project
   ::webhooks/event? true
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id name] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [project (db/get-by-id conn :project id ::sql/for-update true)]
    (db/update! conn :project
                {:name name}
                {:id id})
    (rph/with-meta (rph/wrap)
      {::audit/props {:team-id (:team-id project)
                      :prev-name (:name project)}})))

;; --- MUTATION: Delete Project

(defn- delete-project
  [conn team project-id]
  (let [delay   (ldel/get-deletion-delay team)
        project (db/update! conn :project
                            {:deleted-at (ct/in-future delay)}
                            {:id project-id}
                            {::db/return-keys true})]

    (when (:is-default project)
      (ex/raise :type :validation
                :code :non-deletable-project
                :hint "impossible to delete default project"))

    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :project
                                :deleted-at (:deleted-at project)
                                :id project-id}})

    project))

(def ^:private schema:delete-project
  [:map {:title "delete-project"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-project
  {::doc/added "1.18"
   ::sm/params schema:delete-project
   ::webhooks/event? true
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [team    (teams/get-team conn
                                :profile-id profile-id
                                :project-id id)
        project (delete-project conn team id)]
    (rph/with-meta (rph/wrap)
      {::audit/props {:team-id (:team-id project)
                      :name (:name project)
                      :created-at (:created-at project)
                      :modified-at (:modified-at project)}})))

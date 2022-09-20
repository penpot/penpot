;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.projects
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

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

;; --- Query: Projects

(declare retrieve-projects)

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::projects
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::projects
  [{:keys [pool]} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (retrieve-projects conn profile-id team-id)))

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

(defn retrieve-projects
  [conn profile-id team-id]
  (db/exec! conn [sql:projects profile-id team-id]))


;; --- Query: All projects

(declare retrieve-all-projects)

(s/def ::profile-id ::us/uuid)
(s/def ::all-projects
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::all-projects
  [{:keys [pool]} {:keys [profile-id]}]
  (with-open [conn (db/open pool)]
    (retrieve-all-projects conn profile-id)))

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

(defn retrieve-all-projects
  [conn profile-id]
  (db/exec! conn [sql:all-projects profile-id profile-id]))


;; --- Query: Project

(s/def ::id ::us/uuid)
(s/def ::project
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::project
  [{:keys [pool]} {:keys [profile-id id]}]
  (with-open [conn (db/open pool)]
    (let [project (db/get-by-id conn :project id)]
      (check-read-permissions! conn profile-id id)
      project)))


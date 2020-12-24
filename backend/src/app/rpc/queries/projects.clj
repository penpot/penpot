;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.queries.projects
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
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

(defn check-edition-permissions!
  [conn profile-id project-id]
  (let [rows (db/exec! conn [sql:project-permissions
                             project-id profile-id
                             project-id profile-id])]
    (when (empty? rows)
      (ex/raise :type :not-found))
    (when-not (or (some :can-edit rows)
                  (some :is-admin rows)
                  (some :is-owner rows))
      (ex/raise :type :validation
                :code :not-authorized))))

(defn check-read-permissions!
  [conn profile-id project-id]
  (let [rows (db/exec! conn [sql:project-permissions
                             project-id profile-id
                             project-id profile-id])]

    (when-not (seq rows)
      (ex/raise :type :validation
                :code :not-authorized))))



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
     left join team_project_profile_rel as tpp
            on (tpp.project_id = p.id and
                tpp.team_id = p.team_id and
                tpp.profile_id = ?)
    where p.team_id = ?
      and p.deleted_at is null
    order by p.modified_at desc")

(defn retrieve-projects
  [conn profile-id team-id]
  (db/exec! conn [sql:projects profile-id team-id]))


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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.queries.projects
  (:require
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.services.queries :as sq]
   [app.services.queries.teams :as teams]))

;; --- Check Project Permissions

;; This SQL checks if the: (1) project is part of the team where the
;; profile has edition permissions or (2) the profile has direct
;; edition access granted to this project.

(def sql:project-permissions
  "select tp.can_edit,
          tp.is_admin,
          tp.is_owner
     from team_profile_rel as tp
    where tp.profile_id = ?
      and tp.team_id = ?
    union
   select pp.can_edit,
          pp.is_admin,
          pp.is_owner
     from project_profile_rel as pp
    where pp.profile_id = ?
      and pp.project_id = ?;")

(defn check-edition-permissions!
  [conn profile-id project]
  (let [rows (db/exec! conn [sql:project-permissions
                             profile-id
                             (:team-id project)
                             profile-id
                             (:id project)])]
    (when (empty? rows)
      (ex/raise :type :not-found))

    (when-not (or (some :can-edit rows)
                  (some :is-admin rows)
                  (some :is-owner rows))
      (ex/raise :type :validation
                :code :not-authorized))))


;; --- Query: Projects

(declare retrieve-projects)

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::projects
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::projects
  [{:keys [profile-id team-id]}]
  (with-open [conn (db/open)]
    (teams/check-read-permissions! conn profile-id team-id)
    (retrieve-projects conn team-id)))

(def sql:projects
  "select p.*,
          (select count(*) from file as f
              where f.project_id = p.id
                and deleted_at is null)
     from project as p
    where p.team_id = ?
      and p.deleted_at is null
    order by p.modified_at desc")

(defn retrieve-projects
  [conn team-id]
  (db/exec! conn [sql:projects team-id]))

;; --- Query: Projec by ID

(s/def ::project-id ::us/uuid)
(s/def ::project-by-id
  (s/keys :req-un [::profile-id ::project-id]))

(sq/defquery ::project-by-id
  [{:keys [profile-id project-id]}]
  (with-open [conn (db/open)]
    (let [project (db/get-by-id conn :project project-id)]
      (check-edition-permissions! conn profile-id project)
      project)))

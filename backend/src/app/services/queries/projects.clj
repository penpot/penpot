;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns app.services.queries.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [app.common.spec :as us]
   [app.db :as db]
   [app.services.queries :as sq]
   [app.util.blob :as blob]))

(declare decode-row)

;; TODO: this module should be refactored for to separate the
;; permissions checks from the main queries in the same way as pages
;; and files. This refactor will make this functions more "reusable"
;; and will prevent duplicating queries on `queries.view` ns as
;; example.

;; --- Query: Projects

(def ^:private sql:projects
  "with projects as (
     select p.*,
            (select count(*) from file as f
              where f.project_id = p.id
                and deleted_at is null) as file_count
       from project as p
      inner join team_profile_rel as tpr on (tpr.team_id = p.team_id)
      where tpr.profile_id = ?
        and p.deleted_at is null
        and (tpr.is_admin = true or
             tpr.is_owner = true or
             tpr.can_edit = true)
      union
     select p.*,
            (select count(*) from file as f
              where f.project_id = p.id
                and deleted_at is null)
       from project as p
      inner join project_profile_rel as ppr on (ppr.project_id = p.id)
      where ppr.profile_id = ?
        and p.deleted_at is null
        and (ppr.is_admin = true or
             ppr.is_owner = true or
             ppr.can_edit = true)
   )
   select *
     from projects
    where team_id = ?
    order by modified_at desc")

(def ^:private sql:project-by-id
  "select p.*
     from project as p
    inner join project_profile_rel as ppr on (ppr.project_id = p.id)
    where ppr.profile_id = ?
      and p.id = ?
      and p.deleted_at is null
      and (ppr.is_admin = true or
           ppr.is_owner = true or
           ppr.can_edit = true)")

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)

(s/def ::projects-by-team
  (s/keys :req-un [::profile-id ::team-id]))

(s/def ::project-by-id
  (s/keys :req-un [::profile-id ::project-id]))

(defn retrieve-projects
  [conn profile-id team-id]
  (db/exec! conn [sql:projects profile-id profile-id team-id]))

(defn retrieve-project
  [conn profile-id id]
  (db/exec-one! conn [sql:project-by-id profile-id id]))

(sq/defquery ::projects-by-team
  [{:keys [profile-id team-id]}]
  (retrieve-projects db/pool profile-id team-id))

(sq/defquery ::project-by-id
  [{:keys [profile-id project-id]}]
  (retrieve-project db/pool profile-id project-id))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]))

(declare decode-row)

;; --- Query: Projects

(def ^:private sql:projects
  "with projects as (
     select p.*
       from project as p
      inner join team_profile_rel as tpr on (tpr.team_id = p.team_id)
      where tpr.profile_id = $1
        and (tpr.is_admin = true or
             tpr.is_owner = true or
             tpr.can_edit = true)
      union
     select p.*
       from project as p
      inner join project_profile_rel as ppr on (ppr.project_id = p.id)
      where ppr.profile_id = $1
        and (ppr.is_admin = true or
             ppr.is_owner = true or
             ppr.can_edit = true)
   )
   select *
     from projects
    where team_id = $2
    order by created_at asc")

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::projects-by-team
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::projects-by-team
  [{:keys [profile-id team-id] :as params}]
  (db/query db/pool [sql:projects profile-id team-id]))


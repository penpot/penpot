;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.search
  (:require
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(def ^:private sql:search-files
  "with projects as (
     select p.*
       from project as p
      inner join team_profile_rel as tpr on (tpr.team_id = p.team_id)
      where tpr.profile_id = ?
        and p.team_id = ?
        and (p.deleted_at is null or p.deleted_at > now())
        and (tpr.is_admin = true or
             tpr.is_owner = true or
             tpr.can_edit = true)
      union
     select p.*
       from project as p
      inner join project_profile_rel as ppr on (ppr.project_id = p.id)
      where ppr.profile_id = ?
        and p.team_id = ?
        and (p.deleted_at is null or p.deleted_at > now())
        and (ppr.is_admin = true or
             ppr.is_owner = true or
             ppr.can_edit = true)
   )
   select distinct
          f.id,
          f.revn,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared,
          ft.media_id
     from file as f
     left join file_thumbnail as ft on (ft.file_id = f.id and ft.revn = f.revn)
    inner join projects as pr on (f.project_id = pr.id)
    where f.name ilike ('%' || ? || '%')
      and (f.deleted_at is null or f.deleted_at > now())
    order by f.created_at asc")

(defn search-files
  [conn profile-id team-id search-term]
  (->> (db/exec! conn [sql:search-files
                       profile-id team-id
                       profile-id team-id
                       search-term])
       (mapv (fn [row]
               (if-let [media-id (:media-id row)]
                 (-> row
                     (dissoc :media-id)
                     (assoc :thumbnail-id media-id))
                 (dissoc row :media-id))))))

(def ^:private schema:search-files
  [:map {:title "search-files"}
   [:team-id ::sm/uuid]
   [:search-term {:optional true} :string]])

(sv/defmethod ::search-files
  {::doc/added "1.17"
   ::doc/module :files
   ::sm/params schema:search-files}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id team-id search-term]}]
  (some->> search-term (search-files pool profile-id team-id)))

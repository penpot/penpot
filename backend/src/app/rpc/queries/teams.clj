;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.queries.teams
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.profile :as profile]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Team Edition Permissions

(def ^:private sql:team-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    where tpr.profile_id = ?
      and tpr.team_id = ?")

(defn check-edition-permissions!
  [conn profile-id team-id]
  (let [row (db/exec-one! conn [sql:team-permissions profile-id team-id])]
    (when-not (or (:can-edit row)
                  (:is-admin row)
                  (:is-owner row))
      (ex/raise :type :validation
                :code :not-authorized))
    row))

(defn check-read-permissions!
  [conn profile-id team-id]
  (let [row (db/exec-one! conn [sql:team-permissions profile-id team-id])]
    ;; when row is found this means that read permission is granted.
    (when-not row
      (ex/raise :type :validation
                :code :not-authorized))
    row))


;; --- Query: Teams

(declare retrieve-teams)

(s/def ::profile-id ::us/uuid)
(s/def ::teams
  (s/keys :req-un [::profile-id]))

(sv/defmethod ::teams
  [{:keys [pool] :as cfg} {:keys [profile-id]}]
  (with-open [conn (db/open pool)]
    (retrieve-teams conn profile-id)))

(def sql:teams
  "select t.*,
          tp.is_owner,
          tp.is_admin,
          tp.can_edit,
          (t.id = ?) as is_default
     from team_profile_rel as tp
     join team as t on (t.id = tp.team_id)
    where t.deleted_at is null
      and tp.profile_id = ?
    order by t.created_at asc")

(defn retrieve-teams
  [conn profile-id]
  (let [defaults (profile/retrieve-additional-data conn profile-id)]
    (db/exec! conn [sql:teams (:default-team-id defaults) profile-id])))

;; --- Query: Team (by ID)

(declare retrieve-team)

(s/def ::id ::us/uuid)
(s/def ::team
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::team
  [{:keys [pool] :as cfg} {:keys [profile-id id]}]
  (with-open [conn (db/open pool)]
    (retrieve-team conn profile-id id)))

(defn retrieve-team
  [conn profile-id team-id]
  (let [defaults (profile/retrieve-additional-data conn profile-id)
        sql      (str "WITH teams AS (" sql:teams ") SELECT * FROM teams WHERE id=?")
        result   (db/exec-one! conn [sql (:default-team-id defaults) profile-id team-id])]
    (when-not result
      (ex/raise :type :not-found
                :code :object-does-not-exists))
    result))


;; --- Query: Team Members

(declare retrieve-team-members)

(s/def ::team-id ::us/uuid)
(s/def ::team-members
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-members
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (check-edition-permissions! conn profile-id team-id)
    (retrieve-team-members conn team-id)))

(def sql:team-members
  "select tp.*,
          p.id,
          p.email,
          p.fullname as name,
          p.photo,
          p.is_active
     from team_profile_rel as tp
     join profile as p on (p.id = tp.profile_id)
    where tp.team_id = ?")

(defn retrieve-team-members
  [conn team-id]
  (db/exec! conn [sql:team-members team-id]))


;; --- Query: Team Users

(declare retrieve-users)
(declare retrieve-team-for-file)

(s/def ::file-id ::us/uuid)
(s/def ::team-users
  (s/and (s/keys :req-un [::profile-id]
                 :opt-un [::team-id ::file-id])
         #(or (:team-id %) (:file-id %))))

(sv/defmethod ::team-users
  [{:keys [pool] :as cfg} {:keys [profile-id team-id file-id]}]
  (with-open [conn (db/open pool)]
    (if team-id
      (do
        (check-edition-permissions! conn profile-id team-id)
        (retrieve-users conn team-id))
      (let [{team-id :id} (retrieve-team-for-file conn file-id)]
        (check-edition-permissions! conn profile-id team-id)
        (retrieve-users conn team-id)))))

;; This is a similar query to team members but can contain more data
;; because some user can be explicitly added to project or file (not
;; implemented in UI)

(def sql:team-users
  "select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join team_profile_rel as tpr on (tpr.profile_id = pf.id)
    where tpr.team_id = ?
    union
   select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join project_profile_rel as ppr on (ppr.profile_id = pf.id)
    inner join project as p on (ppr.project_id = p.id)
    where p.team_id = ?
   union
   select pf.id, pf.fullname, pf.photo
     from profile as pf
    inner join file_profile_rel as fpr on (fpr.profile_id = pf.id)
    inner join file as f on (fpr.file_id = f.id)
    inner join project as p on (f.project_id = p.id)
    where p.team_id = ?")

(def sql:team-by-file
  "select p.team_id as id
     from project as p
     join file as f on (p.id = f.project_id)
    where f.id = ?")

(defn retrieve-users
  [conn team-id]
  (db/exec! conn [sql:team-users team-id team-id team-id]))

(defn retrieve-team-for-file
  [conn file-id]
  (->> [sql:team-by-file file-id]
       (db/exec-one! conn)))

;; --- Query: Team Stats

(declare retrieve-team-stats)

(s/def ::team-stats
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-stats
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (retrieve-team-stats conn team-id)))

(def sql:team-stats
  "select (select count(*) from project where team_id = ?) as projects,
          (select count(*) from file as f join project as p on (p.id = f.project_id) where p.team_id = ?) as files")

(defn retrieve-team-stats
  [conn team-id]
  (db/exec-one! conn [sql:team-stats team-id team-id]))

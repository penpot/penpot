;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.services.queries.teams
  (:require
   [clojure.spec.alpha :as s]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.services.queries :as sq]
   [app.services.queries.profile :as profile]
   [app.util.blob :as blob]))

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
                :code :not-authorized))))

(defn check-read-permissions!
  [conn profile-id team-id]
  (let [row (db/exec-one! conn [sql:team-permissions profile-id team-id])]
    ;; when row is found this means that read permission is granted.
    (when-not row
      (ex/raise :type :validation
                :code :not-authorized))))


;; --- Query: Teams

(declare retrieve-teams)

(s/def ::profile-id ::us/uuid)
(s/def ::teams
  (s/keys :req-un [::profile-id]))

(sq/defquery ::teams
  [{:keys [profile-id]}]
  (with-open [conn (db/open)]
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

;; --- Query: Projec by ID

(declare retrieve-team-projects)
(declare retrieve-team)

(s/def ::id ::us/uuid)
(s/def ::team
  (s/keys :req-un [::profile-id ::id]))

(sq/defquery ::team
  [{:keys [profile-id id]}]
  (with-open [conn (db/open)]
    (retrieve-team conn profile-id id)))

(defn- retrieve-team
  [conn profile-id team-id]
  (let [defaults (profile/retrieve-additional-data conn profile-id)
        sql      (str "WITH teams AS (" sql:teams ") SELECT * FROM teams WHERE id=?")]
    (db/exec-one! conn [sql (:default-team-id defaults) profile-id team-id])))

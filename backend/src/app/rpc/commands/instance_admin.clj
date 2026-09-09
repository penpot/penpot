;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.instance-admin
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.types.team :as types.team]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.msgbus :as mbus]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]))

(defn check-admin!
  [cfg id]
  (let [account (profile/get-profile cfg id)]
    (when-not (and (:is-active account)
                   (not (:is-blocked account))
                   (contains? (cf/get :admins #{}) (:email account)))
      (ex/raise :type :restriction :code :only-admins-allowed))))

(defn- affiliations
  [cfg id]
  {:teams (db/exec! cfg
                    ["SELECT t.id, t.name, t.is_default, r.is_owner, r.is_admin, r.can_edit
                        FROM team_profile_rel r JOIN team t ON t.id = r.team_id
                       WHERE r.profile_id = ? AND t.deleted_at IS NULL ORDER BY t.name, t.id" id])
   :projects (db/exec! cfg
                       ["SELECT p.id, p.name, p.team_id, p.is_default, r.is_owner, r.is_admin, r.can_edit
                           FROM project_profile_rel r JOIN project p ON p.id = r.project_id
                           JOIN team t ON t.id = p.team_id
                          WHERE r.profile_id = ? AND p.deleted_at IS NULL AND t.deleted_at IS NULL
                          ORDER BY p.name, p.id" id])})

(sv/defmethod ::get-instance-users
  "List accounts and their explicit team and project memberships."
  {::doc/module :management
   ::sm/params [:map
                [:search {:optional true} [:string {:max 250}]]
                [:offset {:optional true} [::sm/int {:min 0}]]
                [:limit {:optional true} [::sm/int {:min 1 :max 100}]]]}
  [cfg {:keys [::rpc/profile-id search offset limit] :or {search "" offset 0 limit 50}}]
  (check-admin! cfg profile-id)
  (db/run! cfg
           (fn [cfg]
             (let [pattern (str "%" search "%")
                   rows    (db/exec! cfg
                                     ["SELECT id, fullname, email, is_active, is_blocked, auth_backend
                                         FROM profile WHERE deleted_at IS NULL
                                          AND (email ILIKE ? OR fullname ILIKE ?)
                                         ORDER BY email, id LIMIT ? OFFSET ?" pattern pattern limit offset])
                   total   (:total (db/exec-one! cfg
                                                 ["SELECT count(*) AS total FROM profile WHERE deleted_at IS NULL
                                                    AND (email ILIKE ? OR fullname ILIKE ?)" pattern pattern]))]
               {:users (mapv #(merge % (affiliations cfg (:id %))) rows)
                :total total}))))

(sv/defmethod ::get-instance-membership-targets
  "Find shared teams or projects to which an administrator can add accounts."
  {::doc/module :management
   ::sm/params [:map
                [:kind [::sm/one-of #{:team :project}]]
                [:search {:optional true} [:string {:max 250}]]
                [:offset {:optional true} [::sm/int {:min 0}]]]}
  [cfg {:keys [::rpc/profile-id kind search offset] :or {search "" offset 0}}]
  (check-admin! cfg profile-id)
  (let [pattern (str "%" search "%")]
    (if (= kind :team)
      (db/exec! cfg ["SELECT id, name FROM team WHERE deleted_at IS NULL AND NOT is_default
                       AND name ILIKE ? ORDER BY name, id LIMIT 50 OFFSET ?" pattern offset])
      (db/exec! cfg ["SELECT p.id, p.name, t.name AS team_name FROM project p JOIN team t ON t.id = p.team_id
                      WHERE p.deleted_at IS NULL AND t.deleted_at IS NULL AND NOT t.is_default
                        AND p.name ILIKE ? ORDER BY p.name, p.id LIMIT 50 OFFSET ?" pattern offset]))))

(sv/defmethod ::set-instance-user-membership
  "Add, update or remove an explicit membership without an invitation email."
  {::doc/module :management
   ::db/transaction true
   ::sm/params [:map
                [:member-id ::sm/uuid]
                [:kind [::sm/one-of #{:team :project}]]
                [:target-id ::sm/uuid]
                [:role [::sm/one-of #{:admin :editor :viewer :none}]]]}
  [cfg {:keys [::rpc/profile-id member-id kind target-id role]}]
  (check-admin! cfg profile-id)
  (let [member  (profile/get-profile cfg member-id)
        project (when (= kind :project) (db/get-by-id cfg :project target-id))
        team-id (if project (:team-id project) target-id)
        team    (db/get-by-id cfg :team team-id ::sql/for-update true)
        table   (if project :project-profile-rel :team-profile-rel)
        key     (if project :project-id :team-id)
        where   {:profile-id member-id key target-id}
        current (db/get* cfg table where)
        flags   (get types.team/permissions-for-role role)]
    (when (or (:is-default team) (:is-owner current))
      (ex/raise :type :restriction :code :protected-membership
                :hint "Personal workspaces and owners cannot be changed here"))
    (when (and project (not= role :none)
               (nil? (db/get* cfg :team-profile-rel {:team-id team-id :profile-id member-id})))
      (ex/raise :type :restriction :code :team-membership-required
                :hint "Add the user to the enclosing team before granting project access"))
    (cond
      (= role :none)
      (db/delete! cfg table where)

      current
      (db/update! cfg table flags where)

      project
      (teams/create-project-role cfg member-id target-id role)

      :else
      (do
        (quotes/check! (assoc cfg ::quotes/profile-id (:id member)
                              ::quotes/team-id team-id ::quotes/incr 1)
                       {::quotes/id ::quotes/profiles-per-team})
        (teams/add-profile-to-team! cfg (merge where flags))))
    ;; Project grants do not change the user's role in the enclosing team.
    (when (and (not project) (::mbus/msgbus cfg))
      (let [removed? (= role :none)
            viewer?  (and removed? (nitrate/organization-owner-of-team? cfg member-id team-id))
            message  (if (and removed? (not viewer?))
                       {:type :team-membership-change :change :removed
                        :team-id team-id :team-name (:name team)}
                       {:type :team-role-change :topic member-id :team-id team-id
                        :role (if viewer? :viewer role)})]
        (mbus/pub! (::mbus/msgbus cfg) :topic member-id :message message)))
    (affiliations cfg member-id)))

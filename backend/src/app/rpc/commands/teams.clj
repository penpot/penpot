;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.teams
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.team :as types.team]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.email :as eml]
   [app.features.logical-deletion :as ldel]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.media :as media]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [clojure.set :as set]))

;; --- Helpers & Specs

(def ^:private sql:team-permissions
  "select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
     join team as t on (t.id = tpr.team_id)
    where tpr.profile_id = ?
      and tpr.team_id = ?
      and t.deleted_at is null")

(defn get-permissions
  [conn profile-id team-id]
  (let [rows     (db/exec! conn [sql:team-permissions profile-id team-id])
        is-owner (boolean (some :is-owner rows))
        is-admin (boolean (some :is-admin rows))
        can-edit (boolean (some :can-edit rows))]
    (when (seq rows)
      {:is-owner is-owner
       :is-admin (or is-owner is-admin)
       :can-edit (or is-owner is-admin can-edit)
       :can-read true})))

(def has-admin-permissions?
  (perms/make-admin-predicate-fn get-permissions))

(def has-edit-permissions?
  (perms/make-edition-predicate-fn get-permissions))

(def has-read-permissions?
  (perms/make-read-predicate-fn get-permissions))

(def check-admin-permissions!
  (perms/make-check-fn has-admin-permissions?))

(def check-edition-permissions!
  (perms/make-check-fn has-edit-permissions?))

(def check-read-permissions!
  (perms/make-check-fn has-read-permissions?))

(defn decode-row
  [{:keys [features subscription] :as row}]
  (when row
    (cond-> row
      (some? features) (assoc :features (db/decode-pgarray features #{}))
      (some? subscription) (assoc :subscription (db/decode-transit-pgobject subscription)))))

;; FIXME: move

(defn check-profile-muted
  "Check if the member's email is part of the global bounce report"
  [conn member]
  (let [email (profile/clean-email (:email member))]
    (when (and member (not (eml/allow-send-emails? conn member)))
      (ex/raise :type :validation
                :code :member-is-muted
                :email email
                :hint "the profile has reported repeatedly as spam or has bounces"))))

(defn check-email-bounce
  "Check if the email is part of the global complain report"
  [conn email show?]
  (when (eml/has-bounce-reports? conn email)
    (ex/raise :type :restriction
              :code :email-has-permanent-bounces
              :email (if show? email "private")
              :hint "this email has been repeatedly reported as bounce")))

(defn check-email-spam
  "Check if the member email is part of the global complain report"
  [conn email show?]
  (when (eml/has-complaint-reports? conn email)
    (ex/raise :type :restriction
              :code :email-has-complaints
              :email (if show? email "private")
              :hint "this email has been repeatedly reported as spam")))


;; --- Query: Teams

(def sql:get-teams-with-permissions
  "SELECT t.*,
          tp.is_owner,
          tp.is_admin,
          tp.can_edit,
          (t.id = ?) AS is_default
     FROM team_profile_rel AS tp
     JOIN team AS t ON (t.id = tp.team_id)
    WHERE t.deleted_at IS null
      AND tp.profile_id = ?
    ORDER BY tp.created_at ASC")

(def sql:get-teams-with-permissions-and-subscription
  "SELECT t.*,
          tp.is_owner,
          tp.is_admin,
          tp.can_edit,
          (t.id = ?) AS is_default,

          jsonb_build_object(
            '~:type', COALESCE(p.props->'~:subscription'->>'~:type', 'professional'),
            '~:status', CASE COALESCE(p.props->'~:subscription'->>'~:type', 'professional')
                          WHEN 'professional' THEN 'active'
                          ELSE COALESCE(p.props->'~:subscription'->>'~:status', 'incomplete')
                       END,
            '~:seats', p.props->'~:subscription'->'~:quantity'
          ) AS subscription
     FROM team_profile_rel AS tp
     JOIN team AS t ON (t.id = tp.team_id)
     JOIN team_profile_rel AS tpr
       ON (tpr.team_id = t.id AND tpr.is_owner IS true)
     JOIN profile AS p
       ON (tpr.profile_id = p.id)
    WHERE t.deleted_at IS null
      AND tp.profile_id = ?
    ORDER BY tp.created_at ASC")

(defn process-permissions
  [team]
  (let [is-owner    (:is-owner team)
        is-admin    (:is-admin team)
        can-edit    (:can-edit team)
        permissions {:type :membership
                     :is-owner is-owner
                     :is-admin (or is-owner is-admin)
                     :can-edit (or is-owner is-admin can-edit)}]
    (-> team
        (dissoc :is-owner :is-admin :can-edit)
        (assoc :permissions permissions))))

(def ^:private
  xform:process-teams
  (comp
   (map decode-row)
   (map process-permissions)))

(defn get-teams
  [conn profile-id]
  (let [profile (profile/get-profile conn profile-id)
        sql     (if (contains? cf/flags :subscriptions)
                  sql:get-teams-with-permissions-and-subscription
                  sql:get-teams-with-permissions)]

    (->> (db/exec! conn [sql (:default-team-id profile) profile-id])
         (into [] xform:process-teams))))

(def ^:private schema:get-teams
  [:map {:title "get-teams"}])

(sv/defmethod ::get-teams
  {::doc/added "1.17"
   ::sm/params schema:get-teams}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (get-teams conn profile-id)))

(def ^:private sql:get-owned-teams
  "SELECT t.id, t.name,
          (SELECT count(*) FROM team_profile_rel WHERE team_id=t.id) AS total_members,
          (SELECT count(*) FROM team_profile_rel WHERE team_id=t.id AND can_edit=true) AS total_editors
     FROM team AS t
     JOIN team_profile_rel AS tpr ON (tpr.team_id = t.id)
    WHERE t.is_default IS false
      AND tpr.is_owner IS true
      AND tpr.profile_id = ?
      AND t.deleted_at IS NULL")

(defn- get-owned-teams
  [cfg profile-id]
  (->> (db/exec! cfg [sql:get-owned-teams profile-id])
       (into [] (map decode-row))))

(sv/defmethod ::get-owned-teams
  {::doc/added "2.8.0"
   ::sm/params schema:get-teams}
  [cfg {:keys [::rpc/profile-id]}]
  (get-owned-teams cfg profile-id))

;; --- Query: Team (by ID)

(declare get-team)

(def ^:private schema:get-team
  [:and
   [:map {:title "get-team"}
    [:id {:optional true} ::sm/uuid]
    [:file-id {:optional true} ::sm/uuid]]

   [:fn (fn [params]
          (or (contains? params :id)
              (contains? params :file-id)))]])

(sv/defmethod ::get-team
  {::doc/added "1.17"
   ::sm/params schema:get-team}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id file-id]}]
  (get-team pool :profile-id profile-id :team-id id :file-id file-id))

(defn get-team
  [conn & {:keys [profile-id team-id project-id file-id] :as params}]

  (assert (uuid? profile-id) "profile-id is mandatory")
  (assert (or (db/connection? conn)
              (db/pool? conn))
          "connection or pool is mandatory")

  (let [{:keys [default-team-id] :as profile}
        (profile/get-profile conn profile-id)

        sql
        (if (contains? cf/flags :subscriptions)
          sql:get-teams-with-permissions-and-subscription
          sql:get-teams-with-permissions)

        result
        (cond
          (some? team-id)
          (let [sql (str "WITH teams AS (" sql ") "
                         "SELECT * FROM teams WHERE id=?")]
            (db/exec-one! conn [sql default-team-id profile-id team-id]))

          (some? project-id)
          (let [sql (str "WITH teams AS (" sql ") "
                         "SELECT t.* FROM teams AS t "
                         "  JOIN project AS p ON (p.team_id = t.id) "
                         " WHERE p.id=?")]
            (db/exec-one! conn [sql default-team-id profile-id project-id]))

          (some? file-id)
          (let [sql (str "WITH teams AS (" sql ") "
                         "SELECT t.* FROM teams AS t "
                         "  JOIN project AS p ON (p.team_id = t.id) "
                         "  JOIN file AS f ON (f.project_id = p.id) "
                         " WHERE f.id=?")]
            (db/exec-one! conn [sql default-team-id profile-id file-id]))

          :else
          (throw (IllegalArgumentException. "invalid arguments")))]

    (when-not result
      (ex/raise :type :not-found
                :code :team-does-not-exist))
    (-> result
        (decode-row)
        (process-permissions))))

;; --- Query: Team Members

(def sql:team-members
  "SELECT tp.*,
          p.id,
          p.email,
          p.fullname AS name,
          p.fullname AS fullname,
          p.photo_id,
          p.is_active
     FROM team_profile_rel AS tp
     JOIN profile AS p ON (p.id = tp.profile_id)
    WHERE tp.team_id = ?")

(defn get-team-members
  [conn team-id]
  (db/exec! conn [sql:team-members team-id]))

(def ^:private schema:get-team-memebrs
  [:map {:title "get-team-members"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-team-members
  {::doc/added "1.17"
   ::sm/params schema:get-team-memebrs}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (get-team-members conn team-id)))

;; --- Query: Team Users

(declare get-users)
(declare get-team-for-file)

(def ^:private schema:get-team-users
  [:and {:title "get-team-users"}
   [:map
    [:team-id {:optional true} ::sm/uuid]
    [:file-id {:optional true} ::sm/uuid]]
   [:fn #(or (contains? % :team-id)
             (contains? % :file-id))]])

;; FIXME: split in two separated requests

(sv/defmethod ::get-team-users
  "Get team users by team-id or by file-id"
  {::doc/added "1.17"
   ::sm/params schema:get-team-users}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id file-id]}]
  (dm/with-open [conn (db/open pool)]
    (if team-id
      (do
        (check-read-permissions! conn profile-id team-id)
        (get-users conn team-id))
      (let [{team-id :id} (get-team-for-file conn file-id)]
        (check-read-permissions! conn profile-id team-id)
        (get-users conn team-id)))))

;; This is a similar query to team members but can contain more data
;; because some user can be explicitly added to project or file (not
;; implemented in UI)

(def sql:team-users
  "select pf.id, pf.fullname, pf.photo_id, pf.email
     from profile as pf
    inner join team_profile_rel as tpr on (tpr.profile_id = pf.id)
    where tpr.team_id = ?
    union
   select pf.id, pf.fullname, pf.photo_id, pf.email
     from profile as pf
    inner join project_profile_rel as ppr on (ppr.profile_id = pf.id)
    inner join project as p on (ppr.project_id = p.id)
    where p.team_id = ?
   union
   select pf.id, pf.fullname, pf.photo_id, pf.email
     from profile as pf
    inner join file_profile_rel as fpr on (fpr.profile_id = pf.id)
    inner join file as f on (fpr.file_id = f.id)
    inner join project as p on (f.project_id = p.id)
    where p.team_id = ?")

(defn get-users
  [conn team-id]
  (db/exec! conn [sql:team-users team-id team-id team-id]))

;; Get the users but add the props property
(def sql:team-users+props
  "select pf.id, pf.fullname, pf.photo_id, pf.email, pf.props
     from profile as pf
    inner join team_profile_rel as tpr on (tpr.profile_id = pf.id)
    where tpr.team_id = ?
    union
   select pf.id, pf.fullname, pf.photo_id, pf.email, pf.props
     from profile as pf
    inner join project_profile_rel as ppr on (ppr.profile_id = pf.id)
    inner join project as p on (ppr.project_id = p.id)
    where p.team_id = ?
   union
   select pf.id, pf.fullname, pf.photo_id, pf.email, pf.props
     from profile as pf
    inner join file_profile_rel as fpr on (fpr.profile_id = pf.id)
    inner join file as f on (fpr.file_id = f.id)
    inner join project as p on (f.project_id = p.id)
    where p.team_id = ?")

(defn get-users+props
  [conn team-id]
  (db/exec! conn [sql:team-users+props team-id team-id team-id]))

(def sql:get-team-by-file
  "SELECT t.*
     FROM team AS t
     JOIN project AS p ON (p.team_id = t.id)
     JOIN file AS f ON (f.project_id = p.id)
    WHERE f.id = ?")

(defn get-team-for-file
  [conn file-id]
  (let [team (->> (db/exec! conn [sql:get-team-by-file file-id])
                  (remove db/is-row-deleted?)
                  (map decode-row)
                  (first))]
    (when-not team
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "database object not found"))

    team))

;; --- Query: Team Stats

(declare get-team-stats)

(def ^:private schema:get-team-stats
  [:map {:title "get-team-stats"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-team-stats
  {::doc/added "1.17"
   ::sm/params schema:get-team-stats}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (get-team-stats conn team-id)))

(def sql:team-stats
  "select (select count(*) from project where team_id = ?) as projects,
          (select count(*) from file as f join project as p on (p.id = f.project_id) where p.team_id = ?) as files")

(defn get-team-stats
  [conn team-id]
  (db/exec-one! conn [sql:team-stats team-id team-id]))

;; --- Query: Team invitations

(def ^:private schema:get-team-invitations
  [:map {:title "get-team-invitations"}
   [:team-id ::sm/uuid]])

(def sql:team-invitations
  "select email_to as email, role, (valid_until < now()) as expired
   from team_invitation where team_id = ? order by valid_until desc, created_at desc")

(defn get-team-invitations
  [conn team-id]
  (->> (db/exec! conn [sql:team-invitations team-id])
       (mapv #(update % :role keyword))))

(sv/defmethod ::get-team-invitations
  {::doc/added "1.17"
   ::sm/params schema:get-team-invitations}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (get-team-invitations conn team-id)))


;; --- COMMAND QUERY: get-team-info

(defn get-team-info
  [{:keys [::db/conn] :as cfg} {:keys [id] :as params}]
  (-> (db/get* conn :team
               {:id id}
               {::sql/columns [:id :is-default :features]})
      (decode-row)))

(sv/defmethod ::get-team-info
  "Retrieve minimal team info by its ID."
  {::rpc/auth false
   ::doc/added "2.2.0"
   ::sm/params schema:get-team}
  [cfg params]
  (db/tx-run! cfg get-team-info params))


;; --- Mutation: Create Team

(declare create-team)
(declare create-project)
(declare create-project-role)
(declare ^:private create-team*)
(declare ^:private create-team-role)
(declare ^:private create-team-default-project)

(def ^:private schema:create-team
  [:map {:title "create-team"}
   [:name [:string {:max 250}]]
   [:features {:optional true} ::cfeat/features]
   [:id {:optional true} ::sm/uuid]])

(sv/defmethod ::create-team
  {::doc/added "1.17"
   ::sm/params schema:create-team}
  [cfg {:keys [::rpc/profile-id] :as params}]

  (quotes/check! cfg {::quotes/id ::quotes/teams-per-profile
                      ::quotes/profile-id profile-id})

  (let [features (-> (cfeat/get-enabled-features cf/flags)
                     (set/difference cfeat/frontend-only-features)
                     (cfeat/check-client-features! (:features params)))
        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features features))
        team     (db/tx-run! cfg create-team params)]

    (with-meta team
      {::audit/props {:id (:id team)}})))

(defn create-team
  "This is a complete team creation process, it creates the team
  object and all related objects (default role and default project)."
  [cfg-or-conn params]
  (let [conn    (db/get-connection cfg-or-conn)
        team    (create-team* conn params)
        params  (assoc params
                       :team-id (:id team)
                       :role :owner)
        project (create-team-default-project conn params)]
    (create-team-role conn params)
    (assoc team :default-project-id (:id project))))

(defn- create-team*
  [conn {:keys [id name is-default features] :as params}]
  (let [id         (or id (uuid/next))
        is-default (if (boolean? is-default) is-default false)
        features   (db/create-array conn "text" features)
        team       (db/insert! conn :team
                               {:id id
                                :name name
                                :features features
                                :is-default is-default})]
    (decode-row team)))

(defn- create-team-role
  [conn {:keys [profile-id team-id role] :as params}]
  (let [params {:team-id team-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :team-profile-rel))))

(defn- create-team-default-project
  [conn {:keys [profile-id team-id] :as params}]
  (let [project {:id (uuid/next)
                 :team-id team-id
                 :name "Drafts"
                 :is-default true}
        project (create-project conn project)]
    (create-project-role conn profile-id (:id project) :owner)
    project))

;; NOTE: we have project creation here because there are cyclic
;; dependency between teams and projects namespaces, and the project
;; creation happens in both sides, on team creation and on simple
;; project creation, so it make sense to have this functions in this
;; namespace too.

(defn create-project
  [conn {:keys [id team-id name is-default created-at modified-at]}]
  (let [id         (or id (uuid/next))
        is-default (if (boolean? is-default) is-default false)
        params     {:id id
                    :name name
                    :team-id team-id
                    :is-default is-default
                    :created-at created-at
                    :modified-at modified-at}]
    (db/insert! conn :project (d/without-nils params))))

(defn create-project-role
  [conn profile-id project-id role]
  (let [params {:project-id project-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :project-profile-rel))))

;; --- Mutation: Update Team

(def ^:private schema:update-team
  [:map {:title "update-team"}
   [:name [:string {:max 250}]]
   [:id ::sm/uuid]])

(sv/defmethod ::update-team
  {::doc/added "1.17"
   ::sm/params schema:update-team
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id name]}]
  (check-edition-permissions! conn profile-id id)
  (db/update! conn :team
              {:name name}
              {:id id})
  nil)


;; --- Mutation: Leave Team

(defn leave-team
  [conn {:keys [profile-id id reassign-to]}]
  (let [perms   (get-permissions conn profile-id id)
        members (get-team-members conn id)]

    (cond
      ;; we can only proceed if there are more members in the team
      ;; besides the current profile
      (<= (count members) 1)
      (ex/raise :type :validation
                :code :no-enough-members-for-leave
                :context {:members (count members)})

      ;; if the `reassign-to` is filled and has a different value
      ;; than the current profile-id, we proceed to reassing the
      ;; owner role to profile identified by the `reassign-to`.
      (and reassign-to (not= reassign-to profile-id))
      (let [member (d/seek #(= reassign-to (:id %)) members)]
        (when-not member
          (ex/raise :type :not-found :code :member-does-not-exist))

        ;; unasign owner role to current profile
        (db/update! conn :team-profile-rel
                    {:is-owner false}
                    {:team-id id
                     :profile-id profile-id})

        ;; assign owner role to new profile
        (db/update! conn :team-profile-rel
                    (get types.team/permissions-for-role :owner)
                    {:team-id id :profile-id reassign-to}))

      ;; and finally, if all other conditions does not match and the
      ;; current profile is owner, we dont allow it because there
      ;; must always be an owner.
      (:is-owner perms)
      (ex/raise :type :validation
                :code :owner-cant-leave-team
                :hint "releasing owner before leave"))

    (db/delete! conn :team-profile-rel
                {:profile-id profile-id
                 :team-id id})

    nil))

(def ^:private schema:leave-team
  [:map {:title "leave-team"}
   [:id ::sm/uuid]
   [:reassign-to {:optional true} ::sm/uuid]])

(sv/defmethod ::leave-team
  {::doc/added "1.17"
   ::sm/params schema:leave-team
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (leave-team conn (assoc params :profile-id profile-id)))

;; --- Mutation: Delete Team

(defn- delete-team
  "Mark a team for deletion"
  [conn {:keys [id] :as team}]

  (let [delay (ldel/get-deletion-delay team)
        team  (db/update! conn :team
                          {:deleted-at (ct/in-future delay)}
                          {:id id}
                          {::db/return-keys true})]

    (when (:is-default team)
      (ex/raise :type :validation
                :code :non-deletable-team
                :hint "impossible to delete default team"))

    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :team
                                :deleted-at (:deleted-at team)
                                :id id}})
    team))

(def ^:private schema:delete-team
  [:map {:title "delete-team"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-team
  {::doc/added "1.17"
   ::sm/params schema:delete-team
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (let [team  (get-team conn :profile-id profile-id :team-id id)
        perms (get team :permissions)]

    (when-not (:is-owner perms)
      (ex/raise :type :validation
                :code :only-owner-can-delete-team))

    (delete-team conn team)
    nil))

;; --- Mutation: Team Update Role

(defn update-team-member-role
  [{:keys [::db/conn ::mbus/msgbus]} {:keys [profile-id team-id member-id role] :as params}]
  ;; We retrieve all team members instead of query the
  ;; database for a single member. This is just for
  ;; convenience, if this becomes a bottleneck or problematic,
  ;; we will change it to more efficient fetch mechanisms.
  (let [perms   (get-permissions conn profile-id team-id)
        members (get-team-members conn team-id)
        member  (d/seek #(= member-id (:id %)) members)
        is-owner? (:is-owner perms)
        is-admin? (:is-admin perms)]

    ;; If no member is found, just 404
    (when-not member
      (ex/raise :type :not-found
                :code :member-does-not-exist))

    ;; First check if we have permissions to change roles
    (when-not (or is-owner? is-admin?)
      (ex/raise :type :validation
                :code :insufficient-permissions))

    ;; Don't allow change role of owner member
    (when (:is-owner member)
      (ex/raise :type :validation
                :code :cant-change-role-to-owner))

    ;; Don't allow promote to owner to admin users.
    (when (and (not is-owner?) (= role :owner))
      (ex/raise :type :validation
                :code :cant-promote-to-owner))

    (mbus/pub! msgbus
               :topic member-id
               :message {:type :team-role-change
                         :topic member-id
                         :team-id team-id
                         :role role})

    (let [params (get types.team/permissions-for-role role)]
      ;; Only allow single owner on team
      (when (= role :owner)
        (db/update! conn :team-profile-rel
                    {:is-owner false}
                    {:team-id team-id
                     :profile-id profile-id}))

      (db/update! conn :team-profile-rel
                  params
                  {:team-id team-id
                   :profile-id member-id})
      nil)))

(def ^:private schema:update-team-member-role
  [:map {:title "update-team-member-role"}
   [:team-id ::sm/uuid]
   [:member-id ::sm/uuid]
   [:role types.team/schema:role]])

(sv/defmethod ::update-team-member-role
  {::doc/added "1.17"
   ::sm/params schema:update-team-member-role}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg update-team-member-role (assoc params :profile-id profile-id)))

;; --- Mutation: Delete Team Member

(def ^:private schema:delete-team-member
  [:map {:title "delete-team-member"}
   [:team-id ::sm/uuid]
   [:member-id ::sm/uuid]])

(sv/defmethod ::delete-team-member
  {::doc/added "1.17"
   ::sm/params schema:delete-team-member
   ::db/transaction true}
  [{:keys [::db/conn ::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id team-id member-id] :as params}]
  (let [team  (get-team conn :profile-id profile-id :team-id team-id)
        perms (get-permissions conn profile-id team-id)]
    (when-not (or (:is-owner perms)
                  (:is-admin perms))
      (ex/raise :type :validation
                :code :insufficient-permissions))

    (when (= member-id profile-id)
      (ex/raise :type :validation
                :code :cant-remove-yourself))

    (db/delete! conn :team-profile-rel {:profile-id member-id
                                        :team-id team-id})
    (mbus/pub! msgbus
               :topic member-id
               :message {:type :team-membership-change
                         :change :removed
                         :team-id team-id
                         :team-name (:name team)})

    nil))

;; --- Mutation: Update Team Photo

(declare upload-photo)
(declare ^:private update-team-photo)

(def ^:private schema:update-team-photo
  [:map {:title "update-team-photo"}
   [:team-id ::sm/uuid]
   [:file media/schema:upload]])

(sv/defmethod ::update-team-photo
  {::doc/added "1.17"
   ::sm/params schema:update-team-photo}
  [cfg {:keys [::rpc/profile-id file] :as params}]
  ;; Validate incoming mime type

  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (update-team-photo cfg (assoc params :profile-id profile-id)))

(defn update-team-photo
  [{:keys [::db/pool ::sto/storage] :as cfg} {:keys [profile-id team-id] :as params}]
  (let [team  (get-team pool :profile-id profile-id :team-id team-id)
        photo (profile/upload-photo cfg params)]

    (check-admin-permissions! pool profile-id team-id)

    ;; Mark object as touched for make it ellegible for tentative
    ;; garbage collection.
    (when-let [id (:photo-id team)]
      (sto/touch-object! storage id))

    ;; Save new photo
    (db/update! pool :team
                {:photo-id (:id photo)}
                {:id team-id})

    (assoc team :photo-id (:id photo))))

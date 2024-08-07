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
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.email :as eml]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

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
  [{:keys [features] :as row}]
  (cond-> row
    (some? features) (assoc :features (db/decode-pgarray features #{}))))



(defn- check-valid-email-muted
  "Check if the member's email is part of the global bounce report."
  [conn member show?]
  (let [email  (profile/clean-email (:email member))]
    (when (and member (not (eml/allow-send-emails? conn member)))
      (ex/raise :type :validation
                :code :member-is-muted
                :email (if show? email "private")
                :hint "the profile has reported repeatedly as spam or has bounces"))))

(defn- check-valid-email-bounce
  "Check if the email is part of the global complain report"
  [conn email show?]
  (when (eml/has-bounce-reports? conn email)
    (ex/raise :type :restriction
              :code :email-has-permanent-bounces
              :email (if show? email "private")
              :hint "this email has been repeatedly reported as bounce")))

(defn- check-valid-email-spam
  "Check if the member email is part of the global complain report"
  [conn email show?]
  (when (eml/has-complaint-reports? conn email)
    (ex/raise :type :restriction
              :code :email-has-complaints
              :email (if show? email "private")
              :hint "this email has been repeatedly reported as spam")))


;; --- Query: Teams

(declare get-teams)

(def ^:private schema:get-teams
  [:map {:title "get-teams"}])

(sv/defmethod ::get-teams
  {::doc/added "1.17"
   ::sm/params schema:get-teams}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (get-teams conn profile-id)))

(def sql:get-teams-with-permissions
  "select t.*,
          tp.is_owner,
          tp.is_admin,
          tp.can_edit,
          (t.id = ?) as is_default
     from team_profile_rel as tp
     join team as t on (t.id = tp.team_id)
    where t.deleted_at is null
      and tp.profile_id = ?
    order by tp.created_at asc")

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

(defn get-teams
  [conn profile-id]
  (let [profile (profile/get-profile conn profile-id)]
    (->> (db/exec! conn [sql:get-teams-with-permissions (:default-team-id profile) profile-id])
         (map decode-row)
         (map process-permissions)
         (vec))))

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

  (dm/assert!
   "connection or pool is mandatory"
   (or (db/connection? conn)
       (db/pool? conn)))

  (dm/assert!
   "profile-id is mandatory"
   (uuid? profile-id))

  (let [{:keys [default-team-id] :as profile} (profile/get-profile conn profile-id)
        result (cond
                 (some? team-id)
                 (let [sql (str "WITH teams AS (" sql:get-teams-with-permissions
                                ") SELECT * FROM teams WHERE id=?")]
                   (db/exec-one! conn [sql default-team-id profile-id team-id]))

                 (some? project-id)
                 (let [sql (str "WITH teams AS (" sql:get-teams-with-permissions ") "
                                "SELECT t.* FROM teams AS t "
                                "  JOIN project AS p ON (p.team_id = t.id) "
                                " WHERE p.id=?")]
                   (db/exec-one! conn [sql default-team-id profile-id project-id]))

                 (some? file-id)
                 (let [sql (str "WITH teams AS (" sql:get-teams-with-permissions ") "
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
  "select tp.*,
          p.id,
          p.email,
          p.fullname as name,
          p.fullname as fullname,
          p.photo_id,
          p.is_active
     from team_profile_rel as tp
     join profile as p on (p.id = tp.profile_id)
    where tp.team_id = ?")

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
  "select pf.id, pf.fullname, pf.photo_id
     from profile as pf
    inner join team_profile_rel as tpr on (tpr.profile_id = pf.id)
    where tpr.team_id = ?
    union
   select pf.id, pf.fullname, pf.photo_id
     from profile as pf
    inner join project_profile_rel as ppr on (ppr.profile_id = pf.id)
    inner join project as p on (ppr.project_id = p.id)
    where p.team_id = ?
   union
   select pf.id, pf.fullname, pf.photo_id
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

(defn get-users
  [conn team-id]
  (db/exec! conn [sql:team-users team-id team-id team-id]))

(defn get-team-for-file
  [conn file-id]
  (->> [sql:team-by-file file-id]
       (db/exec-one! conn)))

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

(defn- get-team-info
  [{:keys [::db/conn] :as cfg} {:keys [id] :as params}]
  (db/get* conn :team
           {:id id}
           {::sql/columns [:id :is-default]}))

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
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (quotes/check-quote! conn {::quotes/id ::quotes/teams-per-profile
                                               ::quotes/profile-id profile-id})

                    (let [features (-> (cfeat/get-enabled-features cf/flags)
                                       (cfeat/check-client-features! (:features params)))
                          team (create-team cfg (assoc params
                                                       :profile-id profile-id
                                                       :features features))]
                      (with-meta team
                        {::audit/props {:id (:id team)}})))))

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
   ::sm/params schema:update-team}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id name] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (db/update! conn :team
                {:name name}
                {:id id})
    nil))


;; --- Mutation: Leave Team

(declare role->params)

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
                    (role->params :owner)
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
   ::sm/params schema:leave-team}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (leave-team conn (assoc params :profile-id profile-id))))

;; --- Mutation: Delete Team

(defn- delete-team
  "Mark a team for deletion"
  [conn team-id]

  (let [deleted-at (dt/now)
        team       (db/update! conn :team
                               {:deleted-at deleted-at}
                               {:id team-id}
                               {::db/return-keys true})]

    (when (:is-default team)
      (ex/raise :type :validation
                :code :non-deletable-team
                :hint "impossible to delete default team"))

    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :team
                                :deleted-at deleted-at
                                :id team-id}})
    team))

(def ^:private schema:delete-team
  [:map {:title "delete-team"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-team
  {::doc/added "1.17"
   ::sm/params schema:delete-team}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (get-permissions conn profile-id id)]
      (when-not (:is-owner perms)
        (ex/raise :type :validation
                  :code :only-owner-can-delete-team))

      (delete-team conn id)
      nil)))

;; --- Mutation: Team Update Role

;; Temporarily disabled viewer role
;; https://tree.taiga.io/project/penpot/issue/1083
(def valid-roles
  #{:owner :admin :editor #_:viewer})

(def schema:role
  [::sm/one-of valid-roles])

(defn role->params
  [role]
  (case role
    :admin  {:is-owner false :is-admin true :can-edit true}
    :editor {:is-owner false :is-admin false :can-edit true}
    :owner  {:is-owner true  :is-admin true :can-edit true}
    :viewer {:is-owner false :is-admin false :can-edit false}))

(defn update-team-member-role
  [conn {:keys [profile-id team-id member-id role] :as params}]
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

    (let [params (role->params role)]
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
   [:role schema:role]])

(sv/defmethod ::update-team-member-role
  {::doc/added "1.17"
   ::sm/params schema:update-team-member-role}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (update-team-member-role conn (assoc params :profile-id profile-id))))

;; --- Mutation: Delete Team Member

(def ^:private schema:delete-team-member
  [:map {:title "delete-team-member"}
   [:team-id ::sm/uuid]
   [:member-id ::sm/uuid]])

(sv/defmethod ::delete-team-member
  {::doc/added "1.17"
   ::sm/params schema:delete-team-member}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id member-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (get-permissions conn profile-id team-id)]
      (when-not (or (:is-owner perms)
                    (:is-admin perms))
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (when (= member-id profile-id)
        (ex/raise :type :validation
                  :code :cant-remove-yourself))

      (db/delete! conn :team-profile-rel {:profile-id member-id
                                          :team-id team-id})

      nil)))

;; --- Mutation: Update Team Photo

(declare upload-photo)
(declare ^:private update-team-photo)

(def ^:private schema:update-team-photo
  [:map {:title "update-team-photo"}
   [:team-id ::sm/uuid]
   [:file ::media/upload]])

(sv/defmethod ::update-team-photo
  {::doc/added "1.17"
   ::sm/params schema:update-team-photo}
  [cfg {:keys [::rpc/profile-id file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (update-team-photo cfg (assoc params :profile-id profile-id))))

(defn update-team-photo
  [{:keys [::db/pool ::sto/storage] :as cfg} {:keys [profile-id team-id] :as params}]
  (let [team  (get-team pool :profile-id profile-id :team-id team-id)
        photo (profile/upload-photo cfg params)]

    (db/with-atomic [conn pool]
      (check-admin-permissions! conn profile-id team-id)
      ;; Mark object as touched for make it ellegible for tentative
      ;; garbage collection.
      (when-let [id (:photo-id team)]
        (sto/touch-object! storage id))

      ;; Save new photo
      (db/update! pool :team
                  {:photo-id (:id photo)}
                  {:id team-id})

      (assoc team :photo-id (:id photo)))))

;; --- Mutation: Create Team Invitation

(def sql:upsert-team-invitation
  "insert into team_invitation(id, team_id, email_to, role, valid_until)
   values (?, ?, ?, ?, ?)
       on conflict(team_id, email_to) do
          update set role = ?, valid_until = ?, updated_at = now()
   returning *")

(defn- create-invitation-token
  [cfg {:keys [profile-id valid-until team-id member-id member-email role]}]
  (tokens/generate (::setup/props cfg)
                   {:iss :team-invitation
                    :exp valid-until
                    :profile-id profile-id
                    :role role
                    :team-id team-id
                    :member-email member-email
                    :member-id member-id}))

(defn- create-profile-identity-token
  [cfg profile]
  (tokens/generate (::setup/props cfg)
                   {:iss :profile-identity
                    :profile-id (:id profile)
                    :exp (dt/in-future {:days 30})}))

(defn- create-invitation
  [{:keys [::db/conn] :as cfg} {:keys [team profile role email] :as params}]
  (let [email  (profile/clean-email email)
        member (profile/get-profile-by-email conn email)]

    (check-valid-email-muted conn member true)
    (check-valid-email-bounce conn email true)
    (check-valid-email-spam conn email true)


    ;; When we have email verification disabled and invitation user is
    ;; already present in the database, we proceed to add it to the
    ;; team as-is, without email roundtrip.

    ;; TODO: if member does not exists and email verification is
    ;; disabled, we should proceed to create the profile (?)
    (if (and (not (contains? cf/flags :email-verification))
             (some? member))
      (let [params (merge {:team-id (:id team)
                           :profile-id (:id member)}
                          (role->params role))]

        ;; Insert the invited member to the team
        (db/insert! conn :team-profile-rel params
                    {::db/on-conflict-do-nothing? true})

        ;; If profile is not yet verified, mark it as verified because
        ;; accepting an invitation link serves as verification.
        (when-not (:is-active member)
          (db/update! conn :profile
                      {:is-active true}
                      {:id (:id member)}))

        nil)

      (let [id         (uuid/next)
            expire     (dt/in-future "168h") ;; 7 days
            invitation (db/exec-one! conn [sql:upsert-team-invitation id
                                           (:id team) (str/lower email)
                                           (name role) expire
                                           (name role) expire])
            updated?   (not= id (:id invitation))
            tprops     {:profile-id (:id profile)
                        :invitation-id (:id invitation)
                        :valid-until expire
                        :team-id (:id team)
                        :member-email (:email-to invitation)
                        :member-id (:id member)
                        :role role}
            itoken     (create-invitation-token cfg tprops)
            ptoken     (create-profile-identity-token cfg profile)]

        (when (contains? cf/flags :log-invitation-tokens)
          (l/info :hint "invitation token" :token itoken))


        (let [props  (-> (dissoc tprops :profile-id)
                         (audit/clean-props))
              evname (if updated?
                       "update-team-invitation"
                       "create-team-invitation")
              event (-> (audit/event-from-rpc-params params)
                        (assoc ::audit/name evname)
                        (assoc ::audit/props props))]
          (audit/submit! cfg event))

        (eml/send! {::eml/conn conn
                    ::eml/factory eml/invite-to-team
                    :public-uri (cf/get :public-uri)
                    :to email
                    :invited-by (:fullname profile)
                    :team (:name team)
                    :token itoken
                    :extra-data ptoken})

        itoken))))

(defn- add-user-to-team
  [conn profile team email role]

  (let [team-id (:id team)
        member (db/get* conn :profile
                        {:email (str/lower email)}
                        {:columns [:id :email]})
        params (merge
                {:team-id team-id
                 :profile-id (:id member)}
                (role->params role))]

      ;; Do not allow blocked users to join teams.
    (when (:is-blocked member)
      (ex/raise :type :restriction
                :code :profile-blocked))

    (quotes/check-quote! conn
                         {::quotes/id ::quotes/profiles-per-team
                          ::quotes/profile-id (:id member)
                          ::quotes/team-id team-id})

    ;; Insert the member to the team
    (db/insert! conn :team-profile-rel params {::db/on-conflict-do-nothing? true})

    ;; Delete any request
    (db/delete! conn :team-request
                {:team-id team-id :requester-id (:id member)})

    ;; Delete any invitation
    (db/delete! conn :team-invitation
                {:team-id team-id :email-to (:email member)})

    (eml/send! {::eml/conn conn
                ::eml/factory eml/join-team
                :public-uri (cf/get :public-uri)
                :to email
                :invited-by (:fullname profile)
                :team (:name team)
                :team-id (:id team)})))


(def sql:valid-requests-email
  "select p.email from team_request tr, profile p
     where tr.team_id = ?
       and tr.auto_join_until > now()
       and tr.requester_id = p.id")

(defn- get-valid-requests-email
  [conn team-id]
  (db/exec! conn [sql:valid-requests-email team-id]))

(def ^:private schema:create-team-invitations
  [:map {:title "create-team-invitations"}
   [:team-id ::sm/uuid]
   [:role schema:role]
   [:emails ::sm/set-of-emails]])

(sv/defmethod ::create-team-invitations
  "A rpc call that allow to send a single or multiple invitations to
  join the team."
  {::doc/added "1.17"
   ::sm/params schema:create-team-invitations}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (get-permissions conn profile-id team-id)
          profile  (db/get-by-id conn :profile profile-id)
          team     (db/get-by-id conn :team team-id)
          emails   (into #{} (map profile/clean-email) emails)]

      (run! (partial quotes/check-quote! conn)
            (list {::quotes/id ::quotes/invitations-per-team
                   ::quotes/profile-id profile-id
                   ::quotes/team-id (:id team)
                   ::quotes/incr (count emails)}
                  {::quotes/id ::quotes/profiles-per-team
                   ::quotes/profile-id profile-id
                   ::quotes/team-id (:id team)
                   ::quotes/incr (count emails)}))

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      ;; Check if the current profile is allowed to send emails.
      (check-valid-email-muted conn profile true)


      (let [requested      (->> (get-valid-requests-email conn team-id)
                                (map :email)
                                set)
            emails-to-add (filter #(contains? requested %) emails)
            emails        (remove #(contains? requested %) emails)
            cfg           (assoc cfg ::db/conn conn)
            members     (->> (db/exec! conn [sql:team-members team-id])
                             (into #{} (map :email)))

            invitations (into #{}
                              (comp
                               ;;  We don't re-send inviation to already existing members
                               (remove (partial contains? members))
                               (map (fn [email]
                                      (-> params
                                          (assoc :email email)
                                          (assoc :team team)
                                          (assoc :profile profile)
                                          (assoc :role role))))
                               (keep (partial create-invitation cfg)))
                              emails)]
          ;; For requested invitations, do not send invitation emails, add the user directly to the team
        (doseq [e emails-to-add]
          (add-user-to-team conn profile team e role))

        (with-meta {:total (count invitations)
                    :invitations invitations}
          {::audit/props {:invitations (count invitations)}})))))

;; --- Mutation: Create Team & Invite Members

(def ^:private schema:create-team-with-invitations
  [:map {:title "create-team-with-invitations"}
   [:name [:string {:max 250}]]
   [:features {:optional true} ::cfeat/features]
   [:id {:optional true} ::sm/uuid]
   [:emails ::sm/set-of-emails]
   [:role schema:role]])

(sv/defmethod ::create-team-with-invitations
  {::doc/added "1.17"
   ::sm/params schema:create-team-with-invitations}
  [cfg {:keys [::rpc/profile-id emails role name] :as params}]

  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (let [features (-> (cfeat/get-enabled-features cf/flags)
                                   (cfeat/check-client-features! (:features params)))

                      params   (-> params
                                   (assoc :profile-id profile-id)
                                   (assoc :features features))

                      cfg      (assoc cfg ::db/conn conn)
                      team     (create-team cfg params)
                      profile  (db/get-by-id conn :profile profile-id)
                      emails   (into #{} (map profile/clean-email) emails)]

                  (let [props {:name name :features features}
                        event (-> (audit/event-from-rpc-params params)
                                  (assoc ::audit/name "create-team")
                                  (assoc ::audit/props props))]
                    (audit/submit! cfg event))

                  ;; Create invitations for all provided emails.
                  (->> emails
                       (map (fn [email]
                              (-> params
                                  (assoc :team team)
                                  (assoc :profile profile)
                                  (assoc :email email)
                                  (assoc :role role))))
                       (run! (partial create-invitation cfg)))

                  (run! (partial quotes/check-quote! conn)
                        (list {::quotes/id ::quotes/teams-per-profile
                               ::quotes/profile-id profile-id}
                              {::quotes/id ::quotes/invitations-per-team
                               ::quotes/profile-id profile-id
                               ::quotes/team-id (:id team)
                               ::quotes/incr (count emails)}
                              {::quotes/id ::quotes/profiles-per-team
                               ::quotes/profile-id profile-id
                               ::quotes/team-id (:id team)
                               ::quotes/incr (count emails)}))

                  (vary-meta team assoc ::audit/props {:invitations (count emails)})))))

;; --- Query: get-team-invitation-token

(def ^:private schema:get-team-invitation-token
  [:map {:title "get-team-invitation-token"}
   [:team-id ::sm/uuid]
   [:email ::sm/email]])

(sv/defmethod ::get-team-invitation-token
  {::doc/added "1.17"
   ::sm/params schema:get-team-invitation-token}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email] :as params}]
  (check-read-permissions! pool profile-id team-id)
  (let [email (profile/clean-email email)
        invit (-> (db/get pool :team-invitation
                          {:team-id team-id
                           :email-to email})
                  (update :role keyword))

        member (profile/get-profile-by-email pool (:email-to invit))
        token  (create-invitation-token cfg {:team-id (:team-id invit)
                                             :profile-id profile-id
                                             :valid-until (:valid-until invit)
                                             :role (:role invit)
                                             :member-id (:id member)
                                             :member-email (or (:email member)
                                                               (profile/clean-email (:email-to invit)))})]
    {:token token}))

;; --- Mutation: Update invitation role

(def ^:private schema:update-team-invitation-role
  [:map {:title "update-team-invitation-role"}
   [:team-id ::sm/uuid]
   [:email ::sm/email]
   [:role schema:role]])

(sv/defmethod ::update-team-invitation-role
  {::doc/added "1.17"
   ::sm/params schema:update-team-invitation-role}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (db/update! conn :team-invitation
                  {:role (name role) :updated-at (dt/now)}
                  {:team-id team-id :email-to (profile/clean-email email)})
      nil)))

;; --- Mutation: Delete invitation

(def ^:private schema:delete-team-invition
  [:map {:title "delete-team-invitation"}
   [:team-id ::sm/uuid]
   [:email ::sm/email]])

(sv/defmethod ::delete-team-invitation
  {::doc/added "1.17"
   ::sm/params schema:delete-team-invition}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (let [invitation (db/delete! conn :team-invitation
                                   {:team-id team-id
                                    :email-to (profile/clean-email email)}
                                   {::db/return-keys true})]
        (rph/wrap nil {::audit/props {:invitation-id (:id invitation)}})))))




;; --- Mutation: Request Team Invitation

(def sql:upsert-team-request
  "insert into team_request(id, team_id, requester_id, valid_until, auto_join_until)
   values (?, ?, ?, ?, ?)
       on conflict(id) do
          update set valid_until = ?, auto_join_until = ?, updated_at = now()
   returning *")


(def sql:team-request
  "select id, (valid_until < now()) as expired
   from team_request where team_id = ? and requester_id = ?")

(def sql:team-owner
  "select profile_id
   from team_profile_rel where team_id = ? and is_owner = 't'")


(defn- create-team-request
  [{:keys [::db/conn] :as cfg} {:keys [team requester team-owner file is-viewer] :as params}]
  (let [old-request (->> (db/exec! conn [sql:team-request (:id team) (:id requester)])
                         (map decode-row)
                         (first))]
    (when (false? (:expired old-request))
      (ex/raise :type :validation
                :code :request-already-sent
                :hint "you have already made a request to join this team less than 24 hours ago"))

    (let [id              (or (:id old-request) (uuid/next))
          valid_until     (dt/in-future "24h")
          auto_join_until (dt/in-future "168h") ;; 7 days
          request         (db/exec-one! conn [sql:upsert-team-request
                                              id (:id team) (:id requester) valid_until auto_join_until
                                              valid_until auto_join_until])
          factory         (cond
                            (and (some? file) (:is-default team) is-viewer)
                            eml/request-file-access-yourpenpot-view
                            (and (some? file) (:is-default team))
                            eml/request-file-access-yourpenpot
                            (some? file)
                            eml/request-file-access
                            :else
                            eml/request-team-access)
          page-id         (when (some? file)
                            (-> file :data :pages first))]

      ;; TODO needs audit?

      (eml/send! {::eml/conn conn
                  ::eml/factory factory
                  :public-uri (cf/get :public-uri)
                  :to (:email team-owner)
                  :requested-by (:fullname requester)
                  :requested-by-email (:email requester)
                  :team-name (:name team)
                  :team-id (:id team)
                  :file-name (:name file)
                  :file-id (:id file)
                  :page-id page-id})

      request)))


(def ^:private schema:create-team-request
  [:and
   [:map {:title "create-team-request"}
    [:file-id {:optional true} ::sm/uuid]
    [:team-id {:optional true} ::sm/uuid]
    [:is-viewer {:optional true} :boolean]]

   [:fn (fn [params]
          (or (contains? params :file-id)
              (contains? params :team-id)))]])


(sv/defmethod ::create-team-request
  "A rpc call that allow to request for an invitations to join the team."
  {::doc/added "2.2.0"
   ::sm/params schema:create-team-request}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id team-id is-viewer] :as params}]
  (db/with-atomic [conn pool]

    (let [requester     (db/get-by-id conn :profile profile-id)
          team-id       (if (some? team-id)
                          team-id
                          (:id (get-team-for-file conn file-id)))
          team          (db/get-by-id conn :team team-id)
          owner-id      (->> (db/exec! conn [sql:team-owner (:id team)])
                             (map decode-row)
                             (first)
                             :profile-id)
          team-owner    (db/get-by-id conn :profile owner-id)
          file          (when (some? file-id)
                          (db/get* conn :file
                                   {:id file-id}
                                   {::sql/columns [:id :name :data]}))
          file          (when (some? file)
                          (assoc file :data (blob/decode (:data file))))]

      ;;TODO needs quotes?

      (when (or (nil? requester) (nil? team) (nil? team-owner) (and (some? file-id) (nil? file)))
        (ex/raise :type :validation
                  :code :invalid-parameters))

      ;; Check that the requester is not muted
      (check-valid-email-muted conn requester true)

      ;; Check that the owner is not marked as bounce nor spam
      (check-valid-email-bounce conn (:email team-owner) false)
      (check-valid-email-spam conn (:email team-owner) true)

      (let [cfg     (assoc cfg ::db/conn conn)
            request (create-team-request
                     cfg {:team team :requester requester :team-owner team-owner :file file :is-viewer is-viewer})]
        (when request
          (with-meta {:request request}
            {::audit/props {:request 1}}))))))

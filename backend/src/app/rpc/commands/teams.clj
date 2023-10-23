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
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
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
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)

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
  (when row
    (cond-> row
      features (assoc :features (db/decode-pgarray features #{})))))

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
    (->> (db/exec! conn [sql:teams (:default-team-id profile) profile-id])
         (map decode-row)
         (map process-permissions)
         (vec))))

;; --- Query: Team (by ID)

(declare get-team)

(def ^:private schema:get-team
  [:map {:title "get-team"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-team
  {::doc/added "1.17"
   ::sm/params schema:get-team}
  [cfg {:keys [::rpc/profile-id id]}]
  (db/tx-run! cfg #(get-team % :profile-id profile-id :team-id id)))

(defn get-team
  [conn & {:keys [profile-id team-id project-id file-id] :as params}]
  (dm/assert!
   "profile-id is mandatory"
   (uuid? profile-id))

  (let [{:keys [default-team-id] :as profile} (profile/get-profile conn profile-id)
        result (cond
                 (some? team-id)
                 (let [sql (str "WITH teams AS (" sql:teams ") SELECT * FROM teams WHERE id=?")]
                   (db/exec-one! conn [sql default-team-id profile-id team-id]))

                 (some? project-id)
                 (let [sql (str "WITH teams AS (" sql:teams ") "
                                "SELECT t.* FROM teams AS t "
                                "  JOIN project AS p ON (p.team_id = t.id) "
                                " WHERE p.id=?")]
                   (db/exec-one! conn [sql default-team-id profile-id project-id]))

                 (some? file-id)
                 (let [sql (str "WITH teams AS (" sql:teams ") "
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

;; --- Mutation: Create Team

(declare create-team)
(declare create-project)
(declare create-project-role)
(declare ^:private create-team*)
(declare ^:private create-team-role)
(declare ^:private create-team-default-project)

(def ^:private schema:create-team
  [:map {:title "create-team"}
   [:name :string]
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
                                       (cfeat/check-client-features! (:features params)))]
                      (create-team cfg (assoc params
                                              :profile-id profile-id
                                              :features features))))))

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
  [conn {:keys [id team-id name is-default] :as params}]
  (let [id         (or id (uuid/next))
        is-default (if (boolean? is-default) is-default false)]
    (db/insert! conn :project
                {:id id
                 :name name
                 :team-id team-id
                 :is-default is-default})))

(defn create-project-role
  [conn profile-id project-id role]
  (let [params {:project-id project-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :project-profile-rel))))

;; --- Mutation: Update Team

(s/def ::update-team
  (s/keys :req [::rpc/profile-id]
          :req-un [::name ::id]))

(sv/defmethod ::update-team
  {::doc/added "1.17"}
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

(s/def ::reassign-to ::us/uuid)
(s/def ::leave-team
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]
          :opt-un [::reassign-to]))

(sv/defmethod ::leave-team
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (leave-team conn (assoc params :profile-id profile-id))))

;; --- Mutation: Delete Team

(s/def ::delete-team
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]))

;; TODO: right now just don't allow delete default team, in future it
;; should raise a specific exception for signal that this action is
;; not allowed.

(sv/defmethod ::delete-team
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (get-permissions conn profile-id id)]
      (when-not (:is-owner perms)
        (ex/raise :type :validation
                  :code :only-owner-can-delete-team))

      (db/update! conn :team
                  {:deleted-at (dt/now)}
                  {:id id :is-default false})
      nil)))


;; --- Mutation: Team Update Role

(s/def ::team-id ::us/uuid)
(s/def ::member-id ::us/uuid)
(s/def ::role #{:owner :admin :editor})

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

(s/def ::update-team-member-role
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::member-id ::role]))

(sv/defmethod ::update-team-member-role
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (update-team-member-role conn (assoc params :profile-id profile-id))))


;; --- Mutation: Delete Team Member

(s/def ::delete-team-member
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::member-id]))

(sv/defmethod ::delete-team-member
  {::doc/added "1.17"}
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

(s/def ::file ::media/upload)
(s/def ::update-team-photo
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::file]))

(sv/defmethod ::update-team-photo
  {::doc/added "1.17"}
  [cfg {:keys [::rpc/profile-id file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (update-team-photo cfg (assoc params :profile-id profile-id))))

(defn update-team-photo
  [{:keys [::db/pool ::sto/storage] :as cfg} {:keys [profile-id team-id] :as params}]
  (let [team  (get-team pool profile-id team-id)
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
  (tokens/generate (::main/props cfg)
                   {:iss :team-invitation
                    :exp valid-until
                    :profile-id profile-id
                    :role role
                    :team-id team-id
                    :member-email member-email
                    :member-id member-id}))

(defn- create-profile-identity-token
  [cfg profile]
  (tokens/generate (::main/props cfg)
                   {:iss :profile-identity
                    :profile-id (:id profile)
                    :exp (dt/in-future {:days 30})}))

(defn- create-invitation
  [{:keys [::db/conn] :as cfg} {:keys [team profile role email] :as params}]
  (let [member (profile/get-profile-by-email conn email)]

    (when (and member (not (eml/allow-send-emails? conn member)))
      (ex/raise :type :validation
                :code :member-is-muted
                :email email
                :hint "the profile has reported repeatedly as spam or has bounces"))

    ;; Secondly check if the invited member email is part of the global spam/bounce report.
    (when (eml/has-bounce-reports? conn email)
      (ex/raise :type :validation
                :code :email-has-permanent-bounces
                :email email
                :hint "the email you invite has been repeatedly reported as spam or bounce"))

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
        (db/insert! conn :team-profile-rel params {:on-conflict-do-nothing true})

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

        (audit/submit! cfg
                       {::audit/type "action"
                        ::audit/name (if updated?
                                       "update-team-invitation"
                                       "create-team-invitation")
                        ::audit/profile-id (:id profile)
                        ::audit/props (-> (dissoc tprops :profile-id)
                                          (d/without-nils))})

        (eml/send! {::eml/conn conn
                    ::eml/factory eml/invite-to-team
                    :public-uri (cf/get :public-uri)
                    :to email
                    :invited-by (:fullname profile)
                    :team (:name team)
                    :token itoken
                    :extra-data ptoken})

        itoken))))

(def ^:private schema:create-team-invitations
  [:map {:title "create-team-invitations"}
   [:team-id ::sm/uuid]
   [:role [::sm/one-of #{:owner :admin :editor}]]
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
          team     (db/get-by-id conn :team team-id)]

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

      ;; First check if the current profile is allowed to send emails.
      (when-not (eml/allow-send-emails? conn profile)
        (ex/raise :type :validation
                  :code :profile-is-muted
                  :hint "looks like the profile has reported repeatedly as spam or has permanent bounces"))

      (let [cfg         (assoc cfg ::db/conn conn)
            members     (->> (db/exec! conn [sql:team-members team-id])
                             (into #{} (map :email)))

            invitations (into #{}
                              (comp
                               ;;  We don't re-send inviation to already existing members
                               (remove (partial contains? members))
                               (map (fn [email]
                                      {:email (str/lower email)
                                       :team team
                                       :profile profile
                                       :role role}))
                               (keep (partial create-invitation cfg)))
                              emails)]
        (with-meta {:total (count invitations)
                    :invitations invitations}
          {::audit/props {:invitations (count invitations)}})))))


;; --- Mutation: Create Team & Invite Members

(s/def ::emails ::us/set-of-valid-emails)
(s/def ::create-team-with-invitations
  (s/merge ::create-team
           (s/keys :req-un [::emails ::role])))


(def ^:private schema:create-team-with-invitations
  [:map {:title "create-team-with-invitations"}
   [:name :string]
   [:features {:optional true} ::cfeat/features]
   [:id {:optional true} ::sm/uuid]
   [:emails ::sm/set-of-emails]
   [:role schema:role]])

(sv/defmethod ::create-team-with-invitations
  {::doc/added "1.17"
   ::sm/params schema:create-team-with-invitations}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [params   (assoc params :profile-id profile-id)
          cfg      (assoc cfg ::db/conn conn)
          team     (create-team cfg params)
          profile  (db/get-by-id conn :profile profile-id)]

      ;; Create invitations for all provided emails.
      (->> emails
           (map (fn [email]
                  {:team team
                   :profile profile
                   :email (str/lower email)
                   :role role}))
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

      (audit/submit! cfg
                     {::audit/type "command"
                      ::audit/name "create-team-invitations"
                      ::audit/profile-id profile-id
                      ::audit/props {:emails emails
                                     :role role
                                     :profile-id profile-id
                                     :invitations (count emails)}})

      (vary-meta team assoc ::audit/props {:invitations (count emails)}))))

;; --- Query: get-team-invitation-token

(s/def ::get-team-invitation-token
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::email]))

(sv/defmethod ::get-team-invitation-token
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email] :as params}]
  (check-read-permissions! pool profile-id team-id)
  (let [invit (-> (db/get pool :team-invitation
                          {:team-id team-id
                           :email-to (str/lower email)})
                  (update :role keyword))
        member (profile/get-profile-by-email pool (:email-to invit))
        token  (create-invitation-token cfg {:team-id (:team-id invit)
                                             :profile-id profile-id
                                             :valid-until (:valid-until invit)
                                             :role (:role invit)
                                             :member-id (:id member)
                                             :member-email (or (:email member) (:email-to invit))})]
    {:token token}))

;; --- Mutation: Update invitation role

(s/def ::update-team-invitation-role
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::email ::role]))

(sv/defmethod ::update-team-invitation-role
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (db/update! conn :team-invitation
                  {:role (name role) :updated-at (dt/now)}
                  {:team-id team-id :email-to (str/lower email)})
      nil)))

;; --- Mutation: Delete invitation

(s/def ::delete-team-invitation
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::email]))

(sv/defmethod ::delete-team-invitation
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (let [invitation (db/delete! conn :team-invitation
                                   {:team-id team-id
                                    :email-to (str/lower email)})]
        (rph/wrap nil {::audit/props {:invitation-id (:id invitation)}})))))

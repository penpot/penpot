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
   [app.common.logging :as l]
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

;; --- Query: Teams

(declare retrieve-teams)

(def counter (volatile! 0))

(s/def ::get-teams
  (s/keys :req [::rpc/profile-id]))

(sv/defmethod ::get-teams
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (dm/with-open [conn (db/open pool)]
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

(defn retrieve-teams
  [conn profile-id]
  (let [profile (profile/get-profile conn profile-id)]
    (->> (db/exec! conn [sql:teams (:default-team-id profile) profile-id])
         (mapv process-permissions))))

;; --- Query: Team (by ID)

(declare retrieve-team)

(s/def ::get-team
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]))

(sv/defmethod ::get-team
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (dm/with-open [conn (db/open pool)]
    (retrieve-team conn profile-id id)))

(defn retrieve-team
  [conn profile-id team-id]
  (let [profile (profile/get-profile conn profile-id)
        sql     (str "WITH teams AS (" sql:teams ") SELECT * FROM teams WHERE id=?")
        result  (db/exec-one! conn [sql (:default-team-id profile) profile-id team-id])]

    (when-not result
      (ex/raise :type :not-found
                :code :team-does-not-exist))

    (process-permissions result)))

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

(defn retrieve-team-members
  [conn team-id]
  (db/exec! conn [sql:team-members team-id]))

(s/def ::team-id ::us/uuid)
(s/def ::get-team-members
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(sv/defmethod ::get-team-members
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (retrieve-team-members conn team-id)))


;; --- Query: Team Users

(declare retrieve-users)
(declare retrieve-team-for-file)

(s/def ::get-team-users
  (s/and (s/keys :req [::rpc/profile-id]
                 :opt-un [::team-id ::file-id])
         #(or (:team-id %) (:file-id %))))

(sv/defmethod ::get-team-users
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id file-id]}]
  (dm/with-open [conn (db/open pool)]
    (if team-id
      (do
        (check-read-permissions! conn profile-id team-id)
        (retrieve-users conn team-id))
      (let [{team-id :id} (retrieve-team-for-file conn file-id)]
        (check-read-permissions! conn profile-id team-id)
        (retrieve-users conn team-id)))))

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

(defn retrieve-users
  [conn team-id]
  (db/exec! conn [sql:team-users team-id team-id team-id]))

(defn retrieve-team-for-file
  [conn file-id]
  (->> [sql:team-by-file file-id]
       (db/exec-one! conn)))

;; --- Query: Team Stats

(declare retrieve-team-stats)

(s/def ::get-team-stats
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(sv/defmethod ::get-team-stats
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id team-id)
    (retrieve-team-stats conn team-id)))

(def sql:team-stats
  "select (select count(*) from project where team_id = ?) as projects,
          (select count(*) from file as f join project as p on (p.id = f.project_id) where p.team_id = ?) as files")

(defn retrieve-team-stats
  [conn team-id]
  (db/exec-one! conn [sql:team-stats team-id team-id]))


;; --- Query: Team invitations

(s/def ::get-team-invitations
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(def sql:team-invitations
  "select email_to as email, role, (valid_until < now()) as expired
   from team_invitation where team_id = ? order by valid_until desc, created_at desc")

(defn get-team-invitations
  [conn team-id]
  (->> (db/exec! conn [sql:team-invitations team-id])
       (mapv #(update % :role keyword))))

(sv/defmethod ::get-team-invitations
  {::doc/added "1.17"}
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

(s/def ::create-team
  (s/keys :req [::rpc/profile-id]
          :req-un [::name]
          :opt-un [::id]))

(sv/defmethod ::create-team
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (db/with-atomic [conn pool]
    (quotes/check-quote! conn {::quotes/id ::quotes/teams-per-profile
                               ::quotes/profile-id profile-id})

    (create-team conn (assoc params :profile-id profile-id))))

(defn create-team
  "This is a complete team creation process, it creates the team
  object and all related objects (default role and default project)."
  [conn params]
  (let [team    (create-team* conn params)
        params  (assoc params
                       :team-id (:id team)
                       :role :owner)
        project (create-team-default-project conn params)]
    (create-team-role conn params)
    (assoc team :default-project-id (:id project))))

(defn- create-team*
  [conn {:keys [id name is-default] :as params}]
  (let [id         (or id (uuid/next))
        is-default (if (boolean? is-default) is-default false)]
    (db/insert! conn :team
                {:id id
                 :name name
                 :is-default is-default})))

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
        members (retrieve-team-members conn id)]

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
;; Temporarily disabled viewer role
;; https://tree.taiga.io/project/penpot/issue/1083
;; (s/def ::role #{:owner :admin :editor :viewer})
(s/def ::role #{:owner :admin :editor})

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
        members (retrieve-team-members conn team-id)
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
  (let [team  (retrieve-team pool profile-id team-id)
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

(s/def ::email ::us/email)
(s/def ::emails ::us/set-of-valid-emails)
(s/def ::create-team-invitations
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id ::role]
          :opt-un [::email ::emails]))

(sv/defmethod ::create-team-invitations
  "A rpc call that allow to send a single or multiple invitations to
  join the team."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (get-permissions conn profile-id team-id)
          profile  (db/get-by-id conn :profile profile-id)
          team     (db/get-by-id conn :team team-id)

          ;; Members emails. We don't re-send inviation to already existing members
          member?  (into #{}
                         (map :email)
                         (db/exec! conn [sql:team-members team-id]))

          emails   (cond-> (or emails #{}) (string? email) (conj email))]

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
            invitations (into []
                              (comp
                               (remove member?)
                               (map (fn [email]
                                      {:email (str/lower email)
                                       :team team
                                       :profile profile
                                       :role role}))
                               (keep (partial create-invitation cfg)))
                              emails)]
        (with-meta invitations
          {::audit/props {:invitations (count invitations)}})))))


;; --- Mutation: Create Team & Invite Members

(s/def ::emails ::us/set-of-valid-emails)
(s/def ::create-team-with-invitations
  (s/merge ::create-team
           (s/keys :req-un [::emails ::role])))

(sv/defmethod ::create-team-with-invitations
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [params   (assoc params :profile-id profile-id)
          team     (create-team conn params)
          profile  (db/get-by-id conn :profile profile-id)
          cfg      (assoc cfg ::db/conn conn)]

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

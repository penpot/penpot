;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.teams
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.profile :as profile]
   [app.rpc.queries.teams :as teams]
   [app.rpc.semaphore :as rsem]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [promesa.exec :as px]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)

;; --- Mutation: Create Team

(declare create-team)
(declare create-team-entry)
(declare create-team-role)
(declare create-team-default-project)

(s/def ::create-team
  (s/keys :req-un [::profile-id ::name]
          :opt-un [::id]))

(sv/defmethod ::create-team
  [{:keys [pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (create-team conn params)))

(defn create-team
  "This is a complete team creation process, it creates the team
  object and all related objects (default role and default project)."
  [conn params]
  (let [team    (create-team-entry conn params)
        params  (assoc params
                       :team-id (:id team)
                       :role :owner)
        project (create-team-default-project conn params)]
    (create-team-role conn params)
    (assoc team :default-project-id (:id project))))

(defn- create-team-entry
  [conn {:keys [id name is-default] :as params}]
  (let [id         (or id (uuid/next))
        is-default (if (boolean? is-default) is-default false)]
    (db/insert! conn :team
                {:id id
                 :name name
                 :is-default is-default})))

(defn- create-team-role
  [conn {:keys [team-id profile-id role] :as params}]
  (let [params {:team-id team-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :team-profile-rel))))

(defn- create-team-default-project
  [conn {:keys [team-id profile-id] :as params}]
  (let [project {:id (uuid/next)
                 :team-id team-id
                 :name "Drafts"
                 :is-default true}
        project (projects/create-project conn project)]
    (projects/create-project-role conn {:project-id (:id project)
                                        :profile-id profile-id
                                        :role :owner})
    project))

;; --- Mutation: Update Team

(s/def ::update-team
  (s/keys :req-un [::profile-id ::name ::id]))

(sv/defmethod ::update-team
  [{:keys [pool] :as cfg} {:keys [id name profile-id] :as params}]
  (db/with-atomic [conn pool]
    (teams/check-edition-permissions! conn profile-id id)
    (db/update! conn :team
                {:name name}
                {:id id})
    nil))


;; --- Mutation: Leave Team

(declare role->params)

(s/def ::reassign-to ::us/uuid)
(s/def ::leave-team
  (s/keys :req-un [::profile-id ::id]
          :opt-un [::reassign-to]))

(sv/defmethod ::leave-team
  [{:keys [pool] :as cfg} {:keys [id profile-id reassign-to]}]
  (db/with-atomic [conn pool]
    (let [perms   (teams/get-permissions conn profile-id id)
          members (teams/retrieve-team-members conn id)]

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

      nil)))

;; --- Mutation: Delete Team

(s/def ::delete-team
  (s/keys :req-un [::profile-id ::id]))

;; TODO: right now just don't allow delete default team, in future it
;; should raise a specific exception for signal that this action is
;; not allowed.

(sv/defmethod ::delete-team
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (teams/get-permissions conn profile-id id)]
      (when-not (:is-owner perms)
        (ex/raise :type :validation
                  :code :only-owner-can-delete-team))

      (db/update! conn :team
                  {:deleted-at (dt/now)}
                  {:id id :is-default false})
      nil)))


;; --- Mutation: Team Update Role

(declare retrieve-team-member)

(s/def ::team-id ::us/uuid)
(s/def ::member-id ::us/uuid)
;; Temporarily disabled viewer role
;; https://tree.taiga.io/project/uxboxproject/issue/1083
;; (s/def ::role #{:owner :admin :editor :viewer})
(s/def ::role #{:owner :admin :editor})

(s/def ::update-team-member-role
  (s/keys :req-un [::profile-id ::team-id ::member-id ::role]))

(sv/defmethod ::update-team-member-role
  [{:keys [pool] :as cfg} {:keys [team-id profile-id member-id role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (teams/get-permissions conn profile-id team-id)
          ;; We retrieve all team members instead of query the
          ;; database for a single member. This is just for
          ;; convenience, if this becomes a bottleneck or problematic,
          ;; we will change it to more efficient fetch mechanisms.
          members (teams/retrieve-team-members conn team-id)
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
        nil))))

(defn role->params
  [role]
  (case role
    :admin  {:is-owner false :is-admin true :can-edit true}
    :editor {:is-owner false :is-admin false :can-edit true}
    :owner  {:is-owner true  :is-admin true :can-edit true}
    :viewer {:is-owner false :is-admin false :can-edit false}))


;; --- Mutation: Delete Team Member

(s/def ::delete-team-member
  (s/keys :req-un [::profile-id ::team-id ::member-id]))

(sv/defmethod ::delete-team-member
  [{:keys [pool] :as cfg} {:keys [team-id profile-id member-id] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (teams/get-permissions conn profile-id team-id)]
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

(declare ^:private upload-photo)
(declare ^:private update-team-photo)

(s/def ::file ::media/upload)
(s/def ::update-team-photo
  (s/keys :req-un [::profile-id ::team-id ::file]))

(sv/defmethod ::update-team-photo
  [cfg {:keys [file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (let [cfg (update cfg :storage media/configure-assets-storage)]
    (update-team-photo cfg params)))

(defn update-team-photo
  [{:keys [pool storage executor] :as cfg} {:keys [profile-id team-id] :as params}]
  (p/let [team  (px/with-dispatch executor
                  (teams/retrieve-team pool profile-id team-id))
          photo (upload-photo cfg params)]

    ;; Mark object as touched for make it ellegible for tentative
    ;; garbage collection.
    (when-let [id (:photo-id team)]
      (sto/touch-object! storage id))

    ;; Save new photo
    (db/update! pool :team
                {:photo-id (:id photo)}
                {:id team-id})

    (assoc team :photo-id (:id photo))))

(defn upload-photo
  [{:keys [storage semaphores] :as cfg} {:keys [file]}]
  (letfn [(get-info [content]
            (rsem/with-dispatch (:process-image semaphores)
              (media/run {:cmd :info :input content})))

          (generate-thumbnail [info]
            (rsem/with-dispatch (:process-image semaphores)
              (media/run {:cmd :profile-thumbnail
                          :format :jpeg
                          :quality 85
                          :width 256
                          :height 256
                          :input info})))

          ;; Function responsible of calculating cryptographyc hash of
          ;; the provided data.
          (calculate-hash [data]
            (rsem/with-dispatch (:process-image semaphores)
              (sto/calculate-hash data)))]

    (p/let [info    (get-info file)
            thumb   (generate-thumbnail info)
            hash    (calculate-hash (:data thumb))
            content (-> (sto/content (:data thumb) (:size thumb))
                        (sto/wrap-with-hash hash))]
      (rsem/with-dispatch (:process-image semaphores)
        (sto/put-object! storage {::sto/content content
                                  ::sto/deduplicate? true
                                  :bucket "profile"
                                  :content-type (:mtype thumb)})))))

;; --- Mutation: Invite Member

(declare create-team-invitation)

(s/def ::email ::us/email)
(s/def ::emails ::us/set-of-valid-emails)
(s/def ::invite-team-member
  (s/keys :req-un [::profile-id ::team-id ::role]
          :opt-un [::email ::emails]))

(sv/defmethod ::invite-team-member
  "A rpc call that allow to send a single or multiple invitations to
  join the team."
  [{:keys [pool] :as cfg} {:keys [profile-id team-id email emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (teams/get-permissions conn profile-id team-id)
          profile  (db/get-by-id conn :profile profile-id)
          team     (db/get-by-id conn :team team-id)
          emails   (cond-> (or emails #{}) (string? email) (conj email))]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      ;; First check if the current profile is allowed to send emails.
      (when-not (eml/allow-send-emails? conn profile)
        (ex/raise :type :validation
                  :code :profile-is-muted
                  :hint "looks like the profile has reported repeatedly as spam or has permanent bounces"))

      (let [invitations (->> emails
                             (map (fn [email]
                                    (assoc cfg
                                           :email email
                                           :conn conn
                                           :team team
                                           :profile profile
                                           :role role)))
                             (map create-team-invitation))]
        (with-meta (vec invitations)
          {::audit/props {:invitations (count invitations)}})))))

(def sql:upsert-team-invitation
  "insert into team_invitation(team_id, email_to, role, valid_until)
   values (?, ?, ?, ?)
       on conflict(team_id, email_to) do
          update set role = ?, valid_until = ?, updated_at = now();")

(defn- create-team-invitation
  [{:keys [conn sprops team profile role email] :as cfg}]
  (let [member    (profile/retrieve-profile-data-by-email conn email)
        token-exp (dt/in-future "168h") ;; 7 days
        email     (str/lower email)
        itoken    (tokens/generate sprops
                                   {:iss :team-invitation
                                    :exp token-exp
                                    :profile-id (:id profile)
                                    :role role
                                    :team-id (:id team)
                                    :member-email (:email member email)
                                    :member-id (:id member)})
        ptoken    (tokens/generate sprops
                                   {:iss :profile-identity
                                    :profile-id (:id profile)
                                    :exp (dt/in-future {:days 30})})]

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

    (when (contains? cf/flags :log-invitation-tokens)
      (l/trace :hint "invitation token" :token itoken))

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
                      {:id (:id member)})))
      (do
        (db/exec-one! conn [sql:upsert-team-invitation
                            (:id team) (str/lower email) (name role)
                            token-exp (name role) token-exp])
        (eml/send! {::eml/conn conn
                    ::eml/factory eml/invite-to-team
                    :public-uri (:public-uri cfg)
                    :to email
                    :invited-by (:fullname profile)
                    :team (:name team)
                    :token itoken
                    :extra-data ptoken})))

    itoken))

;; --- Mutation: Create Team & Invite Members

(s/def ::emails ::us/set-of-valid-emails)
(s/def ::create-team-and-invite-members
  (s/and ::create-team (s/keys :req-un [::emails ::role])))

(sv/defmethod ::create-team-and-invite-members
  [{:keys [pool] :as cfg} {:keys [profile-id emails role] :as params}]
  (db/with-atomic [conn pool]
    (let [team     (create-team conn params)
          audit-fn (:audit cfg)
          profile  (db/get-by-id conn :profile profile-id)]

      ;; Create invitations for all provided emails.
      (doseq [email emails]
        (create-team-invitation
         (assoc cfg
                :conn conn
                :team team
                :profile profile
                :email email
                :role role)))

      (with-meta team
        {::audit/props {:invitations (count emails)}

         :before-complete
         #(audit-fn :cmd :submit
                    :type "mutation"
                    :name "invite-team-member"
                    :profile-id profile-id
                    :props {:emails emails
                            :role role
                            :profile-id profile-id
                            :invitations (count emails)})}))))

;; --- Mutation: Update invitation role

(s/def ::update-team-invitation-role
  (s/keys :req-un [::profile-id ::team-id ::email ::role]))

(sv/defmethod ::update-team-invitation-role
  [{:keys [pool] :as cfg} {:keys [profile-id team-id email role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (teams/get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (db/update! conn :team-invitation
                  {:role (name role) :updated-at (dt/now)}
                  {:team-id team-id :email-to (str/lower email)})
      nil)))

;; --- Mutation: Delete invitation

(s/def ::delete-team-invitation
  (s/keys :req-un [::profile-id ::team-id ::email]))

(sv/defmethod ::delete-team-invitation
  [{:keys [pool] :as cfg} {:keys [profile-id team-id email] :as params}]
  (db/with-atomic [conn pool]
    (let [perms    (teams/get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (db/delete! conn :team-invitation
                {:team-id team-id :email-to (str/lower email)})
      nil)))

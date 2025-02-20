;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.teams-invitations
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.types.team :as types.team]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [cuerdas.core :as str]))

;; --- Mutation: Create Team Invitation

(def sql:upsert-team-invitation
  "insert into team_invitation(id, team_id, email_to, created_by, role, valid_until)
   values (?, ?, ?, ?, ?, ?)
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
  [cfg profile-id]

  (dm/assert!
   "expected valid uuid for profile-id"
   (uuid? profile-id))

  (tokens/generate (::setup/props cfg)
                   {:iss :profile-identity
                    :profile-id profile-id
                    :exp (dt/in-future {:days 30})}))

(def ^:private schema:create-invitation
  [:map {:title "params:create-invitation"}
   [::rpc/profile-id ::sm/uuid]
   [:team
    [:map
     [:id ::sm/uuid]
     [:name :string]]]
   [:profile
    [:map
     [:id ::sm/uuid]
     [:fullname :string]]]
   [:role ::types.team/role]
   [:email ::sm/email]])

(def ^:private check-create-invitation-params
  (sm/check-fn schema:create-invitation))

(defn- allow-invitation-emails?
  [member]
  (let [notifications (dm/get-in member [:props :notifications])]
    (not= :none (:email-invites notifications))))

(defn- create-invitation
  [{:keys [::db/conn] :as cfg} {:keys [team profile role email] :as params}]

  (assert (db/connection? conn) "expected valid connection on cfg parameter")
  (assert (check-create-invitation-params params))

  (let [email  (profile/clean-email email)
        member (profile/get-profile-by-email conn email)]

    ;; When we have email verification disabled and invitation user is
    ;; already present in the database, we proceed to add it to the
    ;; team as-is, without email roundtrip.

    ;; TODO: if member does not exists and email verification is
    ;; disabled, we should proceed to create the profile (?)
    (if (and (not (contains? cf/flags :email-verification))
             (some? member))
      (let [params (merge {:team-id (:id team)
                           :profile-id (:id member)}
                          (get types.team/permissions-for-role role))]

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

      (do
        (some->> member (teams/check-profile-muted conn))
        (teams/check-email-bounce conn email true)
        (teams/check-email-spam conn email true)

        (let [id         (uuid/next)
              expire     (dt/in-future "168h") ;; 7 days
              invitation (db/exec-one! conn [sql:upsert-team-invitation id
                                             (:id team) (str/lower email)
                                             (:id profile)
                                             (name role) expire
                                             (name role) expire])
              updated?   (not= id (:id invitation))
              profile-id (:id profile)
              tprops     {:profile-id profile-id
                          :invitation-id (:id invitation)
                          :valid-until expire
                          :team-id (:id team)
                          :member-email (:email-to invitation)
                          :member-id (:id member)
                          :role role}
              itoken     (create-invitation-token cfg tprops)
              ptoken     (create-profile-identity-token cfg profile-id)]

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

          (when (allow-invitation-emails? member)
            (eml/send! {::eml/conn conn
                        ::eml/factory eml/invite-to-team
                        :public-uri (cf/get :public-uri)
                        :to email
                        :invited-by (:fullname profile)
                        :team (:name team)
                        :token itoken
                        :extra-data ptoken}))

          itoken)))))

(defn- add-member-to-team
  [conn profile team role member]

  (let [team-id (:id team)
        params  (merge
                 {:team-id team-id
                  :profile-id (:id member)}
                 (get types.team/permissions-for-role role))]

    ;; Do not allow blocked users to join teams.
    (when (:is-blocked member)
      (ex/raise :type :restriction
                :code :profile-blocked))

    (quotes/check!
     {::db/conn conn
      ::quotes/id ::quotes/profiles-per-team
      ::quotes/profile-id (:id member)
      ::quotes/team-id team-id})

    ;; Insert the member to the team
    (db/insert! conn :team-profile-rel params {::db/on-conflict-do-nothing? true})

    ;; Delete any request
    (db/delete! conn :team-access-request
                {:team-id team-id :requester-id (:id member)})

    ;; Delete any invitation
    (db/delete! conn :team-invitation
                {:team-id team-id :email-to (:email member)})

    (eml/send! {::eml/conn conn
                ::eml/factory eml/join-team
                :public-uri (cf/get :public-uri)
                :to (:email member)
                :invited-by (:fullname profile)
                :team (:name team)
                :team-id (:id team)})))

(def ^:private sql:valid-access-request-profiles
  "SELECT p.id, p.email, p.is_blocked
     FROM team_access_request AS tr
     JOIN profile AS p ON (tr.requester_id = p.id)
    WHERE tr.team_id = ?
      AND tr.auto_join_until > now()
      AND (p.deleted_at IS NULL OR
           p.deleted_at > now())")

(defn- get-valid-access-request-profiles
  [conn team-id]
  (db/exec! conn [sql:valid-access-request-profiles team-id]))

(def ^:private xf:map-email (map :email))

(defn- create-team-invitations
  [{:keys [::db/conn] :as cfg} {:keys [profile team role emails] :as params}]
  (let [emails           (set emails)

        join-requests    (->> (get-valid-access-request-profiles conn (:id team))
                              (d/index-by :email))

        team-members     (into #{} xf:map-email
                               (teams/get-team-members conn (:id team)))

        invitations      (into #{}
                               (comp
                                ;;  We don't re-send inviation to
                                ;;  already existing members
                                (remove team-members)
                                ;; We don't send invitations to
                                ;; join-requested members
                                (remove join-requests)
                                (map (fn [email] (assoc params :email email)))
                                (keep (partial create-invitation cfg)))
                               emails)]

    ;; For requested invitations, do not send invitation emails, add
    ;; the user directly to the team
    (->> join-requests
         (filter #(contains? emails (key %)))
         (map val)
         (run! (partial add-member-to-team conn profile team role)))

    invitations))

(def ^:private schema:create-team-invitations
  [:map {:title "create-team-invitations"}
   [:team-id ::sm/uuid]
   [:role ::types.team/role]
   [:emails [::sm/set ::sm/email]]])

(def ^:private max-invitations-by-request-threshold
  "The number of invitations can be sent in a single rpc request"
  25)

(sv/defmethod ::create-team-invitations
  "A rpc call that allow to send a single or multiple invitations to
  join the team."
  {::doc/added "1.17"
   ::doc/module :teams
   ::sm/params schema:create-team-invitations}
  [cfg {:keys [::rpc/profile-id team-id emails] :as params}]
  (let [perms    (teams/get-permissions cfg profile-id team-id)
        profile  (db/get-by-id cfg :profile profile-id)
        emails   (into #{} (map profile/clean-email) emails)]

    (when-not (:is-admin perms)
      (ex/raise :type :validation
                :code :insufficient-permissions))

    (when (> (count emails) max-invitations-by-request-threshold)
      (ex/raise :type :validation
                :code :max-invitations-by-request
                :hint "the maximum of invitation on single request is reached"
                :threshold max-invitations-by-request-threshold))

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/team-id team-id)
        (assoc ::quotes/incr (count emails))
        (quotes/check! {::quotes/id ::quotes/invitations-per-team}
                       {::quotes/id ::quotes/profiles-per-team}))

    ;; Check if the current profile is allowed to send emails
    (teams/check-profile-muted cfg profile)

    (let [team        (db/get-by-id cfg :team team-id)
          ;; NOTE: Is important pass RPC method params down to the
          ;; `create-team-invitations` because it uses the implicit
          ;; RPC properties from params for fill necessary data on
          ;; emiting an entry to the audit-log
          invitations (db/tx-run! cfg create-team-invitations
                                  (-> params
                                      (assoc :profile profile)
                                      (assoc :team team)
                                      (assoc :emails emails)))]

      (with-meta {:total (count invitations)
                  :invitations invitations}
        {::audit/props {:invitations (count invitations)}}))))

;; --- Mutation: Create Team & Invite Members

(def ^:private schema:create-team-with-invitations
  [:map {:title "create-team-with-invitations"}
   [:name [:string {:max 250}]]
   [:features {:optional true} ::cfeat/features]
   [:id {:optional true} ::sm/uuid]
   [:emails [::sm/set ::sm/email]]
   [:role ::types.team/role]])

(sv/defmethod ::create-team-with-invitations
  {::doc/added "1.17"
   ::doc/module :teams
   ::sm/params schema:create-team-with-invitations
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id emails role name] :as params}]
  (let [features (-> (cfeat/get-enabled-features cf/flags)
                     (cfeat/check-client-features! (:features params)))

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features features))

        team     (teams/create-team cfg params)
        emails   (into #{} (map profile/clean-email) emails)]

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/team-id (:id team))
        (assoc ::quotes/incr (count emails))
        (quotes/check! {::quotes/id ::quotes/teams-per-profile}
                       {::quotes/id ::quotes/invitations-per-team}
                       {::quotes/id ::quotes/profiles-per-team}))

    (when (> (count emails) max-invitations-by-request-threshold)
      (ex/raise :type :validation
                :code :max-invitations-by-request
                :hint "the maximum of invitation on single request is reached"
                :threshold max-invitations-by-request-threshold))

    (let [props {:name name :features features}
          event (-> (audit/event-from-rpc-params params)
                    (assoc ::audit/name "create-team")
                    (assoc ::audit/props props))]
      (audit/submit! cfg event))

    ;; Create invitations for all provided emails.
    (let [profile     (db/get-by-id conn :profile profile-id)
          params      (-> params
                          (assoc :team team)
                          (assoc :profile profile)
                          (assoc :role role))
          invitations (->> emails
                           (map (fn [email] (assoc params :email email)))
                           (map (partial create-invitation cfg)))]

      (vary-meta team assoc ::audit/props {:invitations (count invitations)}))))

;; --- Query: get-team-invitation-token

(def ^:private schema:get-team-invitation-token
  [:map {:title "get-team-invitation-token"}
   [:team-id ::sm/uuid]
   [:email ::sm/email]])

(sv/defmethod ::get-team-invitation-token
  {::doc/added "1.17"
   ::doc/module :teams
   ::sm/params schema:get-team-invitation-token}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email] :as params}]
  (teams/check-read-permissions! pool profile-id team-id)
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
   [:role ::types.team/role]])

(sv/defmethod ::update-team-invitation-role
  {::doc/added "1.17"
   ::doc/module :teams
   ::sm/params schema:update-team-invitation-role}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id email role] :as params}]
  (db/with-atomic [conn pool]
    (let [perms (teams/get-permissions conn profile-id team-id)]

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
    (let [perms (teams/get-permissions conn profile-id team-id)]

      (when-not (:is-admin perms)
        (ex/raise :type :validation
                  :code :insufficient-permissions))

      (let [invitation (db/delete! conn :team-invitation
                                   {:team-id team-id
                                    :email-to (profile/clean-email email)}
                                   {::db/return-keys true})]
        (rph/wrap nil {::audit/props {:invitation-id (:id invitation)}})))))


;; --- Mutation: Request Team Invitation

(def ^:private sql:get-team-owner
  "SELECT p.*
     FROM profile AS p
     JOIN team_profile_rel AS tpr ON (tpr.profile_id = p.id)
    WHERE tpr.team_id = ?
      AND tpr.is_owner IS TRUE")

(defn- get-team-owner
  "Return a complete profile of the team owner"
  [conn team-id]
  (->> (db/exec! conn [sql:get-team-owner team-id])
       (remove db/is-row-deleted?)
       (map profile/decode-row)
       (first)))

(defn- check-existing-team-access-request
  "Checks if an existing team access request is still valid"
  [conn team-id profile-id]
  (when-let [request (db/get* conn :team-access-request
                              {:team-id team-id
                               :requester-id profile-id})]
    (when (dt/is-after? (:valid-until request) (dt/now))
      (ex/raise :type :validation
                :code :request-already-sent
                :hint "you have already made a request to join this team less than 24 hours ago"))))

(def ^:private sql:upsert-team-access-request
  "INSERT INTO team_access_request (id, team_id, requester_id, valid_until, auto_join_until)
   VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (team_id, requester_id)
       DO UPDATE SET valid_until = ?, auto_join_until = ?, updated_at = now()
   RETURNING *")

(defn- upsert-team-access-request
  "Create or update team access request for provided team and profile-id"
  [conn team-id requester-id]
  (check-existing-team-access-request conn team-id requester-id)
  (let [valid-until     (dt/in-future {:hours 24})
        auto-join-until (dt/in-future {:days 7})
        request-id      (uuid/next)]
    (db/exec-one! conn [sql:upsert-team-access-request
                        request-id team-id requester-id
                        valid-until auto-join-until
                        valid-until auto-join-until])))

(defn- get-file-for-team-access-request
  "A specific method for obtain a file with name and page-id used for
  team request access procediment"
  [cfg file-id]
  (let [file (files/get-file cfg file-id :migrate? false)]
    (-> file
        (dissoc :data)
        (dissoc :deleted-at)
        (assoc :page-id (-> file :data :pages first)))))

(def ^:private schema:create-team-access-request
  [:and
   [:map {:title "create-team-access-request"}
    [:file-id {:optional true} ::sm/uuid]
    [:team-id {:optional true} ::sm/uuid]
    [:is-viewer {:optional true} ::sm/boolean]]

   [:fn (fn [params]
          (or (contains? params :file-id)
              (contains? params :team-id)))]])

(sv/defmethod ::create-team-access-request
  "A rpc call that allow to request for an invitations to join the team."
  {::doc/added "2.2.0"
   ::doc/module :teams
   ::sm/params schema:create-team-access-request
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg}
   {:keys [::rpc/profile-id file-id team-id is-viewer] :as params}]

  (let [requester  (profile/get-profile conn profile-id)
        team       (if team-id
                     (->> (db/get-by-id conn :team team-id)
                          (teams/decode-row))
                     (teams/get-team-for-file conn file-id))

        team-id    (:id team)

        team-owner (get-team-owner conn team-id)

        file       (when (some? file-id)
                     (get-file-for-team-access-request cfg file-id))]

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/team-id team-id)
        (quotes/check! {::quotes/id ::quotes/team-access-requests-per-team}
                       {::quotes/id ::quotes/team-access-requests-per-requester}))

    (teams/check-profile-muted conn requester)
    (teams/check-email-bounce conn (:email team-owner) false)
    (teams/check-email-spam conn (:email team-owner) true)

    (let [request (upsert-team-access-request conn team-id profile-id)
          factory (cond
                    (and (some? file) (:is-default team) is-viewer)
                    eml/request-file-access-yourpenpot-view

                    (and (some? file) (:is-default team))
                    eml/request-file-access-yourpenpot

                    (some? file)
                    eml/request-file-access

                    :else
                    eml/request-team-access)]

      (eml/send! {::eml/conn conn
                  ::eml/factory factory
                  :public-uri (cf/get :public-uri)
                  :to (:email team-owner)
                  :requested-by (:fullname requester)
                  :requested-by-email (:email requester)
                  :team-name (:name team)
                  :team-id team-id
                  :file-name (:name file)
                  :file-id file-id
                  :page-id (:page-id file)})

      (with-meta {:request request}
        {::audit/props {:request 1}}))))

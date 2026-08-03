;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.verify-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.team :as types.team]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.tokens.spec.team-invitation :as-alias spec.team-invitation]
   [app.util.services :as sv]))

(defmulti process-token (fn [_ _ claims] (:iss claims)))

(def ^:private schema:verify-token
  [:map {:title "verify-token"}
   [:token [:string {:max 5000}]]])

(sv/defmethod ::verify-token
  {::rpc/auth false
   ::doc/added "1.15"
   ::doc/module :auth
   ::sm/params schema:verify-token}
  [cfg {:keys [token] :as params}]
  (let [claims (tokens/verify cfg {:token token})]
    (db/tx-run! cfg process-token params claims)))

(defmethod process-token :change-email
  [{:keys [::db/conn] :as cfg} _params {:keys [profile-id email] :as claims}]
  (let [email (profile/clean-email email)]
    (when (profile/get-profile-by-email conn email)
      (ex/raise :type :validation
                :code :email-already-exists))

    (db/update! conn :profile
                {:email email}
                {:id profile-id})

    (rph/with-meta claims
      {::audit/name "update-profile-email"
       ::audit/props {:email email}
       ::audit/profile-id profile-id})))

(defmethod process-token :verify-email
  [{:keys [::db/conn] :as cfg} _ {:keys [profile-id] :as claims}]
  (let [profile (profile/get-profile conn profile-id)
        claims  (assoc claims :profile profile)]

    (when-not (:is-active profile)
      (when (not= (:email profile)
                  (:email claims))
        (ex/raise :type :validation
                  :code :invalid-token))

      (db/update! conn :profile
                  {:is-active true}
                  {:id (:id profile)}))

    ;; NOTE: `claims` is returned verbatim (besides :profile). When the
    ;; verify-email JWE was minted by `register-profile` for a not-yet-
    ;; active profile that came from an invitation flow, `:invitation-
    ;; token` will be present here and the frontend will use it to
    ;; complete the team-invitation flow after login.
    (-> claims
        (rph/with-transform (session/create-fn cfg profile))
        (rph/with-meta {::audit/name "verify-profile-email"
                        ::audit/props (audit/profile->props profile)
                        ::audit/profile-id (:id profile)}))))

(defn- with-nitrate-licence
  [profile cfg]
  (if (contains? cf/flags :admin-console)
    (nitrate/add-nitrate-licence-to-profile cfg profile)
    profile))

(defmethod process-token :auth
  [{:keys [::db/conn] :as cfg} _params {:keys [profile-id] :as claims}]
  (let [profile (-> (profile/get-profile conn profile-id)
                    (profile/strip-private-attrs)
                    (update :props profile/filter-props)
                    (with-nitrate-licence cfg))]
    (assoc claims :profile profile)))

;; --- Team Invitation

(def ^:private sql:get-organization-invitation
  "SELECT *
     FROM team_invitation
    WHERE email_to = ?
      AND org_id = ?")

(def ^:private sql:delete-organization-invitation
  "DELETE FROM team_invitation
    WHERE email_to = ?
      AND org_id = ?")

(defn- accept-invitation
  [{:keys [::db/conn] :as cfg}
   {:keys [team-id organization-id role member-email] :as claims} invitation member]
  (let [;; Update the role if there is an invitation
        role   (or (some-> invitation :role keyword) role)
        id-member (:id member)]

    ;; Do not allow blocked users accept invitations.
    (when (:is-blocked member)
      (ex/raise :type :restriction
                :code :profile-blocked))

    (when team-id
      (quotes/check! cfg {::quotes/id ::quotes/profiles-per-team
                          ::quotes/profile-id id-member
                          ::quotes/team-id team-id}))

    (let [params (merge
                  {:team-id team-id
                   :profile-id id-member}
                  (get types.team/permissions-for-role role))

          accepted-team-id (if organization-id
                             ;; Insert the invited member to the organization
                             (when (contains? cf/flags :admin-console)
                               (teams/initialize-user-in-organization cfg id-member organization-id member-email))
                             ;; Insert the invited member to the team
                             (do (teams/add-profile-to-team! cfg params {::db/on-conflict-do-nothing? true})
                                 team-id))]

      (when-not accepted-team-id
        (ex/raise :type :internal
                  :code :accept-invitation-failed
                  :hint "the accept invitation has failed"))


      ;; If profile is not yet verified, mark it as verified because
      ;; accepting an invitation link serves as verification.
      (when-not (:is-active member)
        (db/update! conn :profile
                    {:is-active true}
                    {:id id-member}))

      ;; Delete the invitation
      (if organization-id
        (db/exec-one! conn [sql:delete-organization-invitation member-email organization-id])
        (db/delete! conn :team-invitation
                    {:email-to member-email
                     :team-id team-id}))

      ;; Delete any request (only applicable for team invitations)
      (when team-id
        (db/delete! conn :team-access-request
                    {:team-id team-id :requester-id id-member}))

      accepted-team-id)))

(def schema:team-invitation-claims
  [:and
   [:map {:title "TeamInvitationClaims"}
    [:iss :keyword]
    [:exp ::ct/inst]
    [:profile-id ::sm/uuid]
    [:role types.team/schema:role]
    [:team-id {:optional true} ::sm/uuid]
    [:organization-id {:optional true} ::sm/uuid]
    [:member-email ::sm/email]
    [:member-id {:optional true} ::sm/uuid]]
   [:fn {:error/message "team-id or organization-id must be present"}
    (fn [m] (or (:team-id m) (:organization-id m)))]])

(def valid-team-invitation-claims?
  (sm/lazy-validator schema:team-invitation-claims))

(defmethod process-token :team-invitation
  [{:keys [::db/conn] :as cfg}
   {:keys [::rpc/profile-id token] :as params}
   {:keys [member-id team-id organization-id member-email] :as claims}]

  (when-not (valid-team-invitation-claims? claims)
    (ex/raise :type :validation
              :code :invalid-invitation-token
              :hint "invitation token contains unexpected data"))

  (let [invitation             (if organization-id
                                 (db/exec-one! conn [sql:get-organization-invitation member-email organization-id])
                                 (db/get* conn :team-invitation
                                          {:email-to member-email
                                           :team-id team-id}))
        profile                (db/get* conn :profile
                                        {:id profile-id}
                                        {:columns [:id :email :default-team-id]})
        registration-disabled? (not (contains? cf/flags :registration))

        organization-invitation?        (and (contains? cf/flags :admin-console) organization-id)]

    (if profile
      (do
        (when-not (or (= member-id profile-id)
                      (= member-email (:email profile)))
          (ex/raise :type :validation
                    :code :invalid-token
                    :reason :email-mismatch
                    :hint "logged-in user does not matches the invitation"))

        (when (nil? invitation)
          (ex/raise :type :validation
                    :code (if organization-id :canceled-invitation :invalid-token)
                    :hint (if organization-id
                            "the invitation has been canceled"
                            "no invitation associated with the token")))

        ;; Membership only makes sense for a logged-in profile with an
        ;; existing invitation; querying it when the invitation is absent
        ;; would call nitrate needlessly and could mask the clean
        ;; :canceled-invitation/:invalid-token response with a generic error.
        (let [membership
              (when (contains? cf/flags :admin-console)
                (cond
                  organization-id
                  (nitrate/call cfg :get-organization-membership {:profile-id profile-id
                                                                  :organization-id organization-id})

                  team-id
                  (nitrate/call cfg :get-organization-membership-by-team {:profile-id profile-id
                                                                          :team-id team-id})))

              organization-id-on-add
              (when (and (:organization-id membership)
                         (not (:is-member membership)))
                (:organization-id membership))

              organization-add-source
              (when organization-id-on-add
                (if organization-id
                  "direct-organization-invitation"
                  "team-invitation"))

              organization-event-origin
              (when organization-id-on-add
                (if organization-id
                  "organization-invitation-acceptance"
                  "team-invitation-acceptance"))

              organization-member-count-before
              (when organization-id-on-add
                (count
                 (nitrate/call cfg :get-organization-members
                               {:organization-id organization-id-on-add})))]

          (when (:is-member membership)
            (when organization-invitation?
              (ex/raise :type :validation
                        :code :already-an-organization-member
                        :team-id (:default-team-id membership)
                        :hint "the user is already a member of the organization")))

          (when (and organization-invitation? (not (:organization-id membership)))
            (ex/raise :type :validation
                      :code :organization-not-found
                      :team-id (:default-team-id profile)
                      :hint "the organization doesn't exist"))

          ;; if we have logged-in user and it matches the invitation we proceed
          ;; with accepting the invitation and joining the current profile to the
          ;; invited team.
          (let [props {:team-id (:team-id claims)
                       :role (:role claims)
                       :invitation-id (:id invitation)}]

            (when team-id
              (audit/submit cfg
                            (-> (audit/event-from-rpc-params params)
                                (assoc :name "accept-team-invitation")
                                (assoc :props props)))

              ;; NOTE: Backward compatibility; old invitations can
              ;; have the `created-by` to be nil; so in this case we
              ;; don't submit this event to the audit-log
              (when-let [created-by (:created-by invitation)]
                (audit/submit cfg
                              (-> (audit/event-from-rpc-params params)
                                  (assoc :profile-id created-by)
                                  (assoc :name "accept-team-invitation-from")
                                  (assoc :props (assoc props
                                                       :profile-id (:id profile)
                                                       :email (:email profile)))))))

            (let [accepted-team-id (accept-invitation cfg claims invitation profile)]
              (when organization-id-on-add
                (audit/submit
                 cfg
                 (-> (audit/event-from-rpc-params params)
                     (assoc :name "accept-organization-invitation")
                     (assoc :props
                            (-> props
                                (assoc :organization-id organization-id-on-add)
                                (audit/clean-props))))))

              (cond-> (assoc claims :state :created)
                ;; when the invitation is to an organization, instead of a team, add the
                ;; accepted-team-id as :organization-team-id
                (:organization-id claims)
                (assoc :organization-team-id accepted-team-id)

                organization-id-on-add
                (assoc :organization-invitation-audit
                       {:origin organization-event-origin
                        :props
                        (-> props
                            (assoc :organization-id organization-id-on-add
                                   :organization-member-add-source organization-add-source
                                   :belongs-to-team-on-add (boolean team-id)
                                   :organization-member-count-before
                                   organization-member-count-before)
                            (audit/clean-props))}))))))

      (do
        ;; If the user is not logged-in and the invitation has been canceled
        ;; we return a specific error code so the frontend can redirect to
        ;; login with an appropriate message instead of showing the error page.
        ;; This only applies to organization invitations; team invitations keep the
        ;; existing :invalid-token behavior.
        (when (nil? invitation)
          (ex/raise :type :validation
                    :code (if organization-id :canceled-invitation :invalid-token)
                    :hint (if organization-id
                            "the invitation has been canceled"
                            "no invitation associated with the token")))

        ;; If we have not logged-in user, and invitation comes with member-id we
        ;; redirect user to login, if no member-id is present and  in the invitation
        ;; token and registration is enabled, we redirect user the the register page.
        {:invitation-token token
         :iss :team-invitation
         :redirect-to (if (or member-id registration-disabled?) :auth-login :auth-register)
         :state :pending}))))

;; --- Default

(defmethod process-token :default
  [_ _ _]
  (ex/raise :type :validation
            :code :invalid-token))

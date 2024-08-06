;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.verify-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.tokens.spec.team-invitation :as-alias spec.team-invitation]
   [app.util.services :as sv]
   [app.util.time :as dt]))

(defmulti process-token (fn [_ _ claims] (:iss claims)))

(def ^:private schema:verify-token
  [:map {:title "verify-token"}
   [:token [:string {:max 1000}]]])

(sv/defmethod ::verify-token
  {::rpc/auth false
   ::doc/added "1.15"
   ::doc/module :auth
   ::sm/params schema:verify-token}
  [{:keys [::db/pool] :as cfg} {:keys [token] :as params}]
  (db/with-atomic [conn pool]
    (let [claims (tokens/verify (::setup/props cfg) {:token token})
          cfg    (assoc cfg :conn conn)]
      (process-token cfg params claims))))

(defmethod process-token :change-email
  [{:keys [conn] :as cfg} _params {:keys [profile-id email] :as claims}]
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
  [{:keys [conn] :as cfg} _ {:keys [profile-id] :as claims}]
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

    (-> claims
        (rph/with-transform (session/create-fn cfg profile-id))
        (rph/with-meta {::audit/name "verify-profile-email"
                        ::audit/props (audit/profile->props profile)
                        ::audit/profile-id (:id profile)}))))

(defmethod process-token :auth
  [{:keys [conn] :as cfg} _params {:keys [profile-id] :as claims}]
  (let [profile  (profile/get-profile conn profile-id {::sql/for-update true})
        props    (merge (:props profile)
                        (:props claims))]
    (when (not= props (:props profile))
      (db/update! conn :profile
                  {:props (db/tjson props)}
                  {:id profile-id}))

    (let [profile (assoc profile :props props)]
      (assoc claims :profile profile))))

;; --- Team Invitation

(defn- accept-invitation
  [{:keys [conn] :as cfg} {:keys [team-id role member-email] :as claims} invitation member]
  (let [;; Update the role if there is an invitation
        role   (or (some-> invitation :role keyword) role)
        params (merge
                {:team-id team-id
                 :profile-id (:id member)}
                (teams/role->params role))]

    ;; Do not allow blocked users accept invitations.
    (when (:is-blocked member)
      (ex/raise :type :restriction
                :code :profile-blocked))

    (quotes/check-quote! conn
                         {::quotes/id ::quotes/profiles-per-team
                          ::quotes/profile-id (:id member)
                          ::quotes/team-id team-id})

    ;; Insert the invited member to the team
    (db/insert! conn :team-profile-rel params {::db/on-conflict-do-nothing? true})

    ;; If profile is not yet verified, mark it as verified because
    ;; accepting an invitation link serves as verification.
    (when-not (:is-active member)
      (db/update! conn :profile
                  {:is-active true}
                  {:id (:id member)}))

    ;; Delete the invitation
    (db/delete! conn :team-invitation
                {:team-id team-id :email-to member-email})

    (assoc member :is-active true)))

(def schema:team-invitation-claims
  [:map {:title "TeamInvitationClaims"}
   [:iss :keyword]
   [:exp ::dt/instant]
   [:profile-id ::sm/uuid]
   [:role teams/schema:role]
   [:team-id ::sm/uuid]
   [:member-email ::sm/email]
   [:member-id {:optional true} ::sm/uuid]])

(def valid-team-invitation-claims?
  (sm/lazy-validator schema:team-invitation-claims))

(defmethod process-token :team-invitation
  [{:keys [conn] :as cfg}
   {:keys [::rpc/profile-id token] :as params}
   {:keys [member-id team-id member-email] :as claims}]

  (when-not (valid-team-invitation-claims? claims)
    (ex/raise :type :validation
              :code :invalid-invitation-token
              :hint "invitation token contains unexpected data"))

  (let [invitation (db/get* conn :team-invitation
                            {:team-id team-id :email-to member-email})
        profile    (db/get* conn :profile
                            {:id profile-id}
                            {:columns [:id :email]})]
    (when (nil? invitation)
      (ex/raise :type :validation
                :code :invalid-token
                :hint "no invitation associated with the token"))

    (if (some? profile)
      (if (or (= member-id profile-id)
              (= member-email (:email profile)))

        ;; if we have logged-in user and it matches the invitation we proceed
        ;; with accepting the invitation and joining the current profile to the
        ;; invited team.
        (let [props {:team-id (:team-id claims)
                     :role (:role claims)
                     :invitation-id (:id invitation)}
              event (-> (audit/event-from-rpc-params params)
                        (assoc ::audit/name "accept-team-invitation")
                        (assoc ::audit/props props))]

          (accept-invitation cfg claims invitation profile)
          (audit/submit! cfg event)
          (assoc claims :state :created))

        (ex/raise :type :validation
                  :code :invalid-token
                  :hint "logged-in user does not matches the invitation"))

      ;; If we have not logged-in user, and invitation comes with member-id we
      ;; redirect user to login, if no memeber-id is present in the invitation
      ;; token, we redirect user the the register page.

      {:invitation-token token
       :iss :team-invitation
       :redirect-to (if member-id :auth-login :auth-register)
       :state :pending})))

;; --- Default

(defmethod process-token :default
  [_ _ _]
  (ex/raise :type :validation
            :code :invalid-token))


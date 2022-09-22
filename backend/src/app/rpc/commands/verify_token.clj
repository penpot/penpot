;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.verify-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.mutations.teams :as teams]
   [app.rpc.queries.profile :as profile]
   [app.tokens :as tokens]
   [app.tokens.spec.team-invitation :as-alias spec.team-invitation]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(s/def ::iss keyword?)
(s/def ::exp ::us/inst)

(defmulti process-token (fn [_ _ claims] (:iss claims)))

(s/def ::verify-token
  (s/keys :req-un [::token]
          :opt-un [::profile-id]))

(sv/defmethod ::verify-token
  {:auth false
   ::doc/added "1.15"}
  [{:keys [pool sprops] :as cfg} {:keys [token] :as params}]
  (db/with-atomic [conn pool]
    (let [claims (tokens/verify sprops {:token token})
          cfg    (assoc cfg :conn conn)]
      (process-token cfg params claims))))

(defmethod process-token :change-email
  [{:keys [conn] :as cfg} _params {:keys [profile-id email] :as claims}]
  (when (profile/retrieve-profile-data-by-email conn email)
    (ex/raise :type :validation
              :code :email-already-exists))

  (db/update! conn :profile
              {:email email}
              {:id profile-id})

  (with-meta claims
    {::audit/name "update-profile-email"
     ::audit/props {:email email}
     ::audit/profile-id profile-id}))

(defmethod process-token :verify-email
  [{:keys [conn session] :as cfg} _ {:keys [profile-id] :as claims}]
  (let [profile (profile/retrieve-profile conn profile-id)
        claims  (assoc claims :profile profile)]

    (when-not (:is-active profile)
      (when (not= (:email profile)
                  (:email claims))
        (ex/raise :type :validation
                  :code :invalid-token))

      (db/update! conn :profile
                  {:is-active true}
                  {:id (:id profile)}))

    (with-meta claims
      {:transform-response ((:create session) profile-id)
       ::audit/name "verify-profile-email"
       ::audit/props (audit/profile->props profile)
       ::audit/profile-id (:id profile)})))

(defmethod process-token :auth
  [{:keys [conn] :as cfg} _params {:keys [profile-id] :as claims}]
  (let [profile (profile/retrieve-profile conn profile-id)]
    (assoc claims :profile profile)))

;; --- Team Invitation

(defn- accept-invitation
  [{:keys [conn] :as cfg} {:keys [member-id team-id role member-email] :as claims} invitation]
  (let [member (profile/retrieve-profile conn member-id)

        ;; Update the role if there is an invitation
        role   (or (some-> invitation :role keyword) role)
        params (merge
                {:team-id team-id
                 :profile-id member-id}
                (teams/role->params role))]

    ;; Insert the invited member to the team
    (db/insert! conn :team-profile-rel params {:on-conflict-do-nothing true})

    ;; If profile is not yet verified, mark it as verified because
    ;; accepting an invitation link serves as verification.
    (when-not (:is-active member)
      (db/update! conn :profile
                  {:is-active true}
                  {:id member-id}))

    ;; Delete the invitation
    (db/delete! conn :team-invitation
                {:team-id team-id :email-to member-email})

    (assoc member :is-active true)))


(s/def ::spec.team-invitation/profile-id ::us/uuid)
(s/def ::spec.team-invitation/role ::us/keyword)
(s/def ::spec.team-invitation/team-id ::us/uuid)
(s/def ::spec.team-invitation/member-email ::us/email)
(s/def ::spec.team-invitation/member-id (s/nilable ::us/uuid))

(s/def ::team-invitation-claims
  (s/keys :req-un [::iss ::exp
                   ::spec.team-invitation/profile-id
                   ::spec.team-invitation/role
                   ::spec.team-invitation/team-id
                   ::spec.team-invitation/member-email]
          :opt-un [::spec.team-invitation/member-id]))

(defmethod process-token :team-invitation
  [{:keys [conn session] :as cfg} {:keys [profile-id token]} {:keys [member-id team-id member-email] :as claims}]
  (us/assert ::team-invitation-claims claims)

  (let [invitation (db/get-by-params conn :team-invitation
                                     {:team-id team-id :email-to member-email}
                                     {:check-not-found false})]
    (when (nil? invitation)
      (ex/raise :type :validation
                :code :invalid-token
                :hint "no invitation associated with the token"))

    (cond
      ;; This happens when token is filled with member-id and current
      ;; user is already logged in with exactly invited account.
      (and (uuid? profile-id) (uuid? member-id))
      (if (= member-id profile-id)
        (let [profile (accept-invitation cfg claims invitation)]
          (with-meta
            (assoc claims :state :created)
            {::audit/name "accept-team-invitation"
             ::audit/props (merge
                            (audit/profile->props profile)
                            {:team-id (:team-id claims)
                             :role (:role claims)})
             ::audit/profile-id member-id}))
        (ex/raise :type :validation
                  :code :invalid-token
                  :hint "logged-in user does not matches the invitation"))

      ;; This happens when an unlogged user, uses an invitation link.
      (and (not profile-id) (uuid? member-id))
      (let [profile (accept-invitation cfg claims invitation)]
        (with-meta
          (assoc claims :state :created)
          {:transform-response ((:create session) (:id profile))
           ::audit/name "accept-team-invitation"
           ::audit/props (merge
                          (audit/profile->props profile)
                          {:team-id (:team-id claims)
                           :role (:role claims)})
           ::audit/profile-id member-id}))

      ;; This case means that invitation token does not match with
      ;; registred user, so we need to indicate to frontend to redirect
      ;; it to register page.
      (and (not profile-id) (nil? member-id))
      {:invitation-token token
       :iss :team-invitation
       :redirect-to :auth-register
       :state :pending}

      ;; In all other cases, just tell to fontend to redirect the user
      ;; to the login page.
      :else
      {:invitation-token token
       :iss :team-invitation
       :redirect-to :auth-login
       :state :pending})))

;; --- Default

(defmethod process-token :default
  [_ _ _]
  (ex/raise :type :validation
            :code :invalid-token))


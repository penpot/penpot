;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.verify-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.metrics :as mtx]
   [app.rpc.mutations.teams :as teams]
   [app.rpc.queries.profile :as profile]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(defmulti process-token (fn [_ _ claims] (:iss claims)))

(s/def ::verify-token
  (s/keys :req-un [::token]
          :opt-un [::profile-id]))

(sv/defmethod ::verify-token {:auth false}
  [{:keys [pool tokens] :as cfg} {:keys [token] :as params}]
  (db/with-atomic [conn pool]
    (let [claims (tokens :verify {:token token})
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

(defn- annotate-profile-activation
  "A helper for properly increase the profile-activation metric once the
  transaction is completed."
  [metrics]
  (fn []
    (let [mobj (get-in metrics [:definitions :profile-activation])]
      ((::mtx/fn mobj) {:by 1}))))

(defmethod process-token :verify-email
  [{:keys [conn session metrics] :as cfg} _ {:keys [profile-id] :as claims}]
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
       :before-complete (annotate-profile-activation metrics)
       ::audit/name "verify-profile-email"
       ::audit/props (audit/profile->props profile)
       ::audit/profile-id (:id profile)})))

(defmethod process-token :auth
  [{:keys [conn] :as cfg} _params {:keys [profile-id] :as claims}]
  (let [profile (profile/retrieve-profile conn profile-id)]
    (assoc claims :profile profile)))


;; --- Team Invitation

(s/def ::iss keyword?)
(s/def ::exp ::us/inst)

(s/def :internal.tokens.team-invitation/profile-id ::us/uuid)
(s/def :internal.tokens.team-invitation/role ::us/keyword)
(s/def :internal.tokens.team-invitation/team-id ::us/uuid)
(s/def :internal.tokens.team-invitation/member-email ::us/email)
(s/def :internal.tokens.team-invitation/member-id (s/nilable ::us/uuid))

(s/def ::team-invitation-claims
  (s/keys :req-un [::iss ::exp
                   :internal.tokens.team-invitation/profile-id
                   :internal.tokens.team-invitation/role
                   :internal.tokens.team-invitation/team-id
                   :internal.tokens.team-invitation/member-email]
          :opt-un [:internal.tokens.team-invitation/member-id]))

(defn- accept-invitation
  [{:keys [conn] :as cfg} {:keys [member-id team-id role] :as claims}]
  (let [params (merge {:team-id team-id
                       :profile-id member-id}
                      (teams/role->params role))
        member (profile/retrieve-profile conn member-id)]

    ;; Insert the invited member to the team
    (db/insert! conn :team-profile-rel params {:on-conflict-do-nothing true})

    ;; If profile is not yet verified, mark it as verified because
    ;; accepting an invitation link serves as verification.
    (when-not (:is-active member)
      (db/update! conn :profile
                  {:is-active true}
                  {:id member-id}))
    (assoc member :is-active true)))

(defmethod process-token :team-invitation
  [cfg {:keys [profile-id token]} {:keys [member-id] :as claims}]
  (us/assert ::team-invitation-claims claims)
  (cond
    ;; This happens when token is filled with member-id and current
    ;; user is already logged in with exactly invited account.
    (and (uuid? profile-id) (uuid? member-id) (= member-id profile-id))
    (let [profile (accept-invitation cfg claims)]
      (with-meta
        (assoc claims :state :created)
        {::audit/name "accept-team-invitation"
         ::audit/props (merge
                        (audit/profile->props profile)
                        {:team-id (:team-id claims)
                         :role (:role claims)})
         ::audit/profile-id member-id}))

    ;; This case means that invitation token does not match with
    ;; registred user, so we need to indicate to frontend to redirect
    ;; it to register page.
    (nil? member-id)
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
     :state :pending}))

;; --- Default

(defmethod process-token :default
  [_ _ _]
  (ex/raise :type :validation
            :code :invalid-token))


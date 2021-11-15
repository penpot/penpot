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
  [{:keys [session] :as cfg} {:keys [profile-id token]} {:keys [member-id] :as claims}]
  (us/assert ::team-invitation-claims claims)
  (cond
    ;; This happens when token is filled with member-id and current
    ;; user is already logged in with some account.
    (and (uuid? profile-id)
         (uuid? member-id))
    (let [profile (accept-invitation cfg claims)]
      (if (= member-id profile-id)
        ;; If the current session is already matches the invited
        ;; member, then just return the token and leave the frontend
        ;; app redirect to correct team.
        (assoc claims :state :created)

        ;; If the session does not matches the invited member, replace
        ;; the session with a new one matching the invited member.
        ;; This technique should be considered secure because the
        ;; user clicking the link he already has access to the email
        ;; account.
        (with-meta
          (assoc claims :state :created)
          {:transform-response ((:create session) member-id)
           ::audit/name "accept-team-invitation"
           ::audit/props (merge
                          (audit/profile->props profile)
                          {:team-id (:team-id claims)
                           :role (:role claims)})
           ::audit/profile-id profile-id})))

    ;; This happens when member-id is not filled in the invitation but
    ;; the user already has an account (probably with other mail) and
    ;; is already logged-in.
    (and (uuid? profile-id)
         (nil? member-id))
    (let [profile (accept-invitation cfg (assoc claims :member-id profile-id))]
      (with-meta
        (assoc claims :state :created)
        {::audit/name "accept-team-invitation"
         ::audit/props (merge
                        (audit/profile->props profile)
                        {:team-id (:team-id claims)
                         :role (:role claims)})
         ::audit/profile-id profile-id}))

    ;; This happens when member-id is filled but the accessing user is
    ;; not logged-in. In this case we proceed to accept invitation and
    ;; leave the user logged-in.
    (and (nil? profile-id)
         (uuid? member-id))
    (let [profile (accept-invitation cfg claims)]
      (with-meta
        (assoc claims :state :created)
        {:transform-response ((:create session) member-id)
         ::audit/name "accept-team-invitation"
         ::audit/props (merge
                        (audit/profile->props profile)
                        {:team-id (:team-id claims)
                         :role (:role claims)})
         ::audit/profile-id member-id}))

    ;; In this case, we wait until frontend app redirect user to
    ;; registration page, the user is correctly registered and the
    ;; register mutation call us again with the same token to finally
    ;; create the corresponding team-profile relation from the first
    ;; condition of this if.
    :else
    {:invitation-token token
     :iss :team-invitation
     :state :pending}))


;; --- Default

(defmethod process-token :default
  [_ _ _]
  (ex/raise :type :validation
            :code :invalid-token))


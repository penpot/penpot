;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

;; TODO: session

(ns app.rpc.mutations.verify-token
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.http.session :as session]
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
  claims)

(defmethod process-token :verify-email
  [{:keys [conn] :as cfg} _params {:keys [profile-id] :as claims}]
  (let [profile (db/get-by-id conn :profile profile-id {:for-update true})]
    (when (:is-active profile)
      (ex/raise :type :validation
                :code :email-already-validated))
    (when (not= (:email profile)
                (:email claims))
      (ex/raise :type :validation
                :code :invalid-token))

    (db/update! conn :profile
                {:is-active true}
                {:id (:id profile)})
    claims))

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

(defmethod process-token :team-invitation
  [{:keys [conn session] :as cfg} {:keys [profile-id token]} {:keys [member-id team-id role] :as claims}]
  (us/assert ::team-invitation-claims claims)
  (if (uuid? member-id)
    (let [params (merge {:team-id team-id
                         :profile-id member-id}
                        (teams/role->params role))
          claims (assoc claims :state :created)]
      (db/insert! conn :team-profile-rel params)
      (if (and (uuid? profile-id)
               (= member-id profile-id))
        ;; If the current session is already matches the invited
        ;; member, then just return the token and leave the frontend
        ;; app redirect to correct team.
        claims

        ;; If the session does not matches the invited member id,
        ;; replace the session with a new one matching the invited
        ;; member. This techinique should be considered secure because
        ;; the user clicking the link he already has access to the
        ;; email account.
        (with-meta claims
          {:transform-response
           (fn [request response]
             (let [uagent (get-in request [:headers "user-agent"])
                   id     (session/create! session {:profile-id member-id
                                                    :user-agent uagent})]
               (assoc response
                      :cookies (session/cookies session {:value id}))))})))

    ;; In this case, we waint until frontend app redirect user to
    ;; registeration page, the user is correctly registered and the
    ;; register mutation call us again with the same token to finally
    ;; create the corresponding team-profile relation from the first
    ;; condition of this if.
    (assoc claims
           :token token
           :state :pending)))


;; --- Default

(defmethod process-token :default
  [_ _ _]
  (ex/raise :type :validation
            :code :invalid-token))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.ldap
  (:require
   [app.auth.ldap :as ldap]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.http.session :as session]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.util.services :as sv]))

;; --- COMMAND: login-with-ldap

(declare login-or-register)

(def schema:login-with-ldap
  [:map {:title "login-with-ldap"}
   [:email ::sm/email]
   [:password auth/schema:password]
   [:invitation-token {:optional true} auth/schema:token]])

(sv/defmethod ::login-with-ldap
  "Performs the authentication using LDAP backend. Only works if LDAP
  is properly configured and enabled with `login-with-ldap` flag."
  {::rpc/auth false
   ::doc/added "1.15"
   ::doc/module :auth
   ::sm/params schema:login-with-ldap}
  [{:keys [::ldap/provider] :as cfg} params]
  (when-not provider
    (ex/raise :type :restriction
              :code :ldap-not-initialized
              :hide "ldap auth provider is not initialized"))

  (let [info (ldap/authenticate provider params)]
    (when-not info
      (ex/raise :type :validation
                :code :wrong-credentials))

    (let [profile (login-or-register cfg info)]

      (when (:is-blocked profile)
        (ex/raise :type :restriction
                  :code :profile-blocked))

      (if-let [token (:invitation-token params)]
        ;; If invitation token comes in params, this is because the
        ;; user comes from team-invitation process; in this case,
        ;; regenerate token and send back to the user a new invitation
        ;; token (and mark current session as logged).
        (let [claims (tokens/verify cfg {:token token :iss :team-invitation})
              claims (assoc claims
                            :member-id  (:id profile)
                            :member-email (:email profile))
              token  (tokens/generate cfg claims)]
          (-> {:invitation-token token}
              (rph/with-transform (session/create-fn cfg (:id profile)))
              (rph/with-meta {::audit/props (:props profile)
                              ::audit/profile-id (:id profile)})))

        (-> (profile/strip-private-attrs profile)
            (rph/with-transform (session/create-fn cfg (:id profile)))
            (rph/with-meta {::audit/props (:props profile)
                            ::audit/profile-id (:id profile)}))))))

(defn- login-or-register
  [cfg info]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (or (some->> (:email info)
                             (profile/clean-email)
                             (profile/get-profile-by-email conn))
                    (->> (assoc info :is-active true :is-demo false)
                         (auth/create-profile! conn)
                         (auth/create-profile-rels! conn)
                         (profile/strip-private-attrs))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.ldap
  (:require
   [app.auth.ldap :as ldap]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.db :as db]
   [app.http.session :as session]
   [app.loggers.audit :as-alias audit]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.queries.profile :as profile]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- COMMAND: login-with-ldap

(declare login-or-register)

(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::invitation-token ::us/string)

(s/def ::login-with-ldap
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(sv/defmethod ::login-with-ldap
  "Performs the authentication using LDAP backend. Only works if LDAP
  is properly configured and enabled with `login-with-ldap` flag."
  {::rpc/auth false
   ::doc/added "1.15"}
  [{:keys [::main/props ::ldap/provider session] :as cfg} params]
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
        (let [claims (tokens/verify props {:token token :iss :team-invitation})
              claims (assoc claims
                            :member-id  (:id profile)
                            :member-email (:email profile))
              token  (tokens/generate props claims)]
          (-> {:invitation-token token}
              (rph/with-transform (session/create-fn session (:id profile)))
              (rph/with-meta {::audit/props (:props profile)
                              ::audit/profile-id (:id profile)})))

        (-> profile
            (rph/with-transform (session/create-fn session (:id profile)))
            (rph/with-meta {::audit/props (:props profile)
                            ::audit/profile-id (:id profile)}))))))

(defn- login-or-register
  [{:keys [pool] :as cfg} info]
  (db/with-atomic [conn pool]
    (or (some->> (:email info)
                 (profile/retrieve-profile-data-by-email conn)
                 (profile/populate-additional-data conn)
                 (profile/decode-profile-row))
        (->> (assoc info :is-active true :is-demo false)
             (cmd.auth/create-profile conn)
             (cmd.auth/create-profile-relations conn)
             (profile/strip-private-attrs)))))


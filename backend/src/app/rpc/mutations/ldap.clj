;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.ldap
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.rpc.mutations.profile :refer [login-or-register]]
   [app.util.services :as sv]
   [clj-ldap.client :as ldap]
   [clojure.spec.alpha :as s]
   [clojure.string]))

(defn ^java.lang.AutoCloseable connect
  []
  (let [params {:ssl?      (cfg/get :ldap-ssl)
                :startTLS? (cfg/get :ldap-starttls)
                :bind-dn   (cfg/get :ldap-bind-dn)
                :password  (cfg/get :ldap-bind-password)
                :host      {:address (cfg/get :ldap-host)
                            :port    (cfg/get :ldap-port)}}]
    (try
      (ldap/connect params)
      (catch Exception e
        (ex/raise :type :restriction
                  :code :ldap-disabled
                  :hint "ldap disabled or unable to connect"
                  :cause e)))))

;; --- Mutation: login-with-ldap

(declare authenticate)

(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::invitation-token ::us/string)

(s/def ::login-with-ldap
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(sv/defmethod ::login-with-ldap {:auth false :rlimit :password}
  [{:keys [pool session tokens] :as cfg} {:keys [email password invitation-token] :as params}]
  (let [info (authenticate params)
        cfg  (assoc cfg :conn pool)]
    (when-not info
      (ex/raise :type :validation
                :code :wrong-credentials))
    (let [profile (login-or-register cfg {:email (:email info)
                                          :backend (:backend info)
                                          :fullname (:fullname info)})]
      (if-let [token (:invitation-token params)]
        ;; If invitation token comes in params, this is because the
        ;; user comes from team-invitation process; in this case,
        ;; regenerate token and send back to the user a new invitation
        ;; token (and mark current session as logged).
        (let [claims (tokens :verify {:token token :iss :team-invitation})
              claims (assoc claims
                            :member-id  (:id profile)
                            :member-email (:email profile))
              token  (tokens :generate claims)]
          (with-meta
            {:invitation-token token}
            {:transform-response ((:create session) (:id profile))}))

        (with-meta profile
          {:transform-response ((:create session) (:id profile))})))))

(defn- replace-several [s & {:as replacements}]
  (reduce-kv clojure.string/replace s replacements))

(defn- get-ldap-user
  [cpool {:keys [email] :as params}]
  (let [query   (-> (cfg/get :ldap-user-query)
                    (replace-several ":username" email))

        attrs   [(cfg/get :ldap-attrs-username)
                 (cfg/get :ldap-attrs-email)
                 (cfg/get :ldap-attrs-photo)
                 (cfg/get :ldap-attrs-fullname)]

        base-dn (cfg/get :ldap-base-dn)
        params  {:filter query :sizelimit 1 :attributes attrs}]
    (first (ldap/search cpool base-dn params))))

(defn- authenticate
  [{:keys [password] :as params}]
  (with-open [conn (connect)]
    (when-let [{:keys [dn] :as luser} (get-ldap-user conn params)]
      (when (ldap/bind? conn dn password)
        {:photo    (get luser (keyword (cfg/get :ldap-attrs-photo)))
         :fullname (get luser (keyword (cfg/get :ldap-attrs-fullname)))
         :email    (get luser (keyword (cfg/get :ldap-attrs-email)))
         :backend  "ldap"}))))

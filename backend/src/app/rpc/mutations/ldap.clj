;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.ldap
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc.mutations.profile :as profile-m]
   [app.rpc.queries.profile :as profile-q]
   [app.util.services :as sv]
   [clj-ldap.client :as ldap]
   [clojure.spec.alpha :as s]
   [clojure.string]))


(s/def ::fullname ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::backend ::us/not-empty-string)

(s/def ::info-data
  (s/keys :req-un [::fullname ::email ::backend]))

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
(declare login-or-register)

(s/def ::email ::us/email)
(s/def ::password ::us/string)
(s/def ::invitation-token ::us/string)

(s/def ::login-with-ldap
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(sv/defmethod ::login-with-ldap {:auth false}
  [{:keys [pool session tokens] :as cfg} params]
  (db/with-atomic [conn pool]
    (let [info (authenticate params)
          cfg  (assoc cfg :conn conn)]

      (when-not info
        (ex/raise :type :validation
                  :code :wrong-credentials))

      (when-not (s/valid? ::info-data info)
        (let [explain (s/explain-str ::info-data info)]
          (l/warn ::l/raw (str "invalid response from ldap, looks like ldap is not configured correctly\n" explain))
          (ex/raise :type :restriction
                    :code :wrong-ldap-response
                    :reason explain)))

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
            (with-meta {:invitation-token token}
              {:transform-response ((:create session) (:id profile))
               ::audit/props (:props profile)
               ::audit/profile-id (:id profile)}))

          (with-meta profile
            {:transform-response ((:create session) (:id profile))
             ::audit/props (:props profile)
             ::audit/profile-id (:id profile)}))))))

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
        params  {:filter query
                 :sizelimit 1
                 :attributes attrs}]
    (first (ldap/search cpool base-dn params))))

(defn- authenticate
  [{:keys [password email] :as params}]
  (with-open [conn (connect)]
    (when-let [{:keys [dn] :as luser} (get-ldap-user conn params)]
      (when (ldap/bind? conn dn password)
        {:photo    (get luser (keyword (cfg/get :ldap-attrs-photo)))
         :fullname (get luser (keyword (cfg/get :ldap-attrs-fullname)))
         :email    email
         :backend  "ldap"}))))

(defn- login-or-register
  [{:keys [conn] :as cfg} info]
  (or (some->> (:email info)
               (profile-q/retrieve-profile-data-by-email conn)
               (profile-q/populate-additional-data conn)
               (profile-q/decode-profile-row))
      (let [params  (-> info
                        (assoc :is-active true)
                        (assoc :is-demo false))]
        (->> params
             (profile-m/create-profile conn)
             (profile-m/create-profile-relations conn)
             (profile-q/strip-private-attrs)))))

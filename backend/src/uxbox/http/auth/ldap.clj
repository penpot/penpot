(ns uxbox.http.auth.ldap
  (:require
    [clj-ldap.client :as client]
    [clojure.set :as set]
    [mount.core :refer [defstate]]
    [uxbox.common.exceptions :as ex]
    [uxbox.config :as cfg]
    [uxbox.services.mutations :as sm]
    [uxbox.http.session :as session]))


(defn replace-several [s & {:as replacements}]
  (reduce-kv clojure.string/replace s replacements))

(defstate ldap-pool
  :start (client/connect (merge
                           {:host {:address (:ldap-auth-host cfg/config)
                                   :port (:ldap-auth-port cfg/config)}}
                           (-> cfg/config
                               (select-keys [:ldap-auth-ssl
                                             :ldap-auth-starttls
                                             :ldap-bind-dn
                                             :ldap-bind-password])
                               (set/rename-keys {:ldap-auth-ssl :ssl?
                                                 :ldap-auth-starttls :startTLS?
                                                 :ldap-bind-dn  :bind-dn
                                                 :ldap-bind-password :password}))))
  :stop (client/close ldap-pool))

(defn- auth-with-ldap [username password]
  (let [conn (client/get-connection ldap-pool)
        user-search-query (replace-several (:ldap-auth-user-query cfg/config)
                                           "$username" username)
        user-attributes (-> cfg/config
                            (select-keys [:ldap-auth-username-attribute
                                          :ldap-auth-email-attribute
                                          :ldap-auth-fullname-attribute
                                          :ldap-auth-avatar-attribute])
                            vals)]
    (try
      (when-some [user-entry (-> conn
                                 (client/search
                                   (:ldap-auth-base-dn cfg/config)
                                   {:filter user-search-query
                                    :sizelimit 1
                                    :attributes user-attributes})
                                 first)]
        (when-not (client/bind? conn (:dn user-entry) password)
          (ex/raise :type :authentication
                    :code ::wrong-credentials))
        (set/rename-keys user-entry {(keyword (:ldap-auth-avatar-attribute cfg/config)) :photo
                                     (keyword (:ldap-auth-fullname-attribute cfg/config)) :fullname
                                     (keyword (:ldap-auth-email-attribute cfg/config)) :email}))
      (finally (client/release-connection ldap-pool conn)))))

(defn auth [req]
  (let [data (:body-params req)
        uagent (get-in req [:headers "user-agent"])]
    (when-some [info (auth-with-ldap (:email data) (:password data))]
      (let [profile (sm/handle {::sm/type :login-or-register
                                :email (:email info)
                                :fullname (:fullname info)})
            sid (session/create (:id profile) uagent)]
        {:status 200
         :cookies (session/cookies sid)
         :body profile}))))

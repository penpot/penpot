;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.http.auth.ldap
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cfg]
   [app.http.session :as session]
   [app.services.mutations :as sm]
   [clj-ldap.client :as client]
   [clojure.set :as set]
   [clojure.string]
   [clojure.tools.logging :as log]
   [mount.core :refer [defstate]]))

(defn replace-several [s & {:as replacements}]
  (reduce-kv clojure.string/replace s replacements))

(declare *ldap-pool)

(defstate *ldap-pool
  :start (delay
           (try
             (client/connect (merge {:host {:address (:ldap-auth-host cfg/config)
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
             (catch Exception e
               (log/errorf e "Cannot connect to LDAP %s:%s"
                             (:ldap-auth-host cfg/config) (:ldap-auth-port cfg/config)))))
  :stop (when (realized? *ldap-pool)
          (some-> *ldap-pool deref (.close))))

(defn- auth-with-ldap [username password]
  (when-some [conn (some-> *ldap-pool deref)]
    (let [user-search-query (replace-several (:ldap-auth-user-query cfg/config)
                                             "$username" username)
          user-attributes (-> cfg/config
                              (select-keys [:ldap-auth-username-attribute
                                            :ldap-auth-email-attribute
                                            :ldap-auth-fullname-attribute
                                            :ldap-auth-avatar-attribute])
                              vals)]
      (when-some [user-entry (-> conn
                                 (client/search (:ldap-auth-base-dn cfg/config)
                                                {:filter user-search-query
                                                 :sizelimit 1
                                                 :attributes user-attributes})
                                 (first))]
        (when-not (client/bind? conn (:dn user-entry) password)
          (ex/raise :type :authentication
                    :code :wrong-credentials))
        (set/rename-keys user-entry {(keyword (:ldap-auth-avatar-attribute cfg/config)) :photo
                                     (keyword (:ldap-auth-fullname-attribute cfg/config)) :fullname
                                     (keyword (:ldap-auth-email-attribute cfg/config)) :email})))))

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

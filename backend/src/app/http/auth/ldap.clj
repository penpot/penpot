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
   [clj-ldap.client :as client]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string ]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(declare authenticate)
(declare create-connection)
(declare replace-several)


(s/def ::host ::cfg/ldap-auth-host)
(s/def ::port ::cfg/ldap-auth-port)
(s/def ::ssl ::cfg/ldap-auth-ssl)
(s/def ::starttls ::cfg/ldap-auth-starttls)
(s/def ::user-query ::cfg/ldap-auth-user-query)
(s/def ::base-dn ::cfg/ldap-auth-base-dn)
(s/def ::username-attribute ::cfg/ldap-auth-username-attribute)
(s/def ::email-attribute ::cfg/ldap-auth-email-attribute)
(s/def ::fullname-attribute ::cfg/ldap-auth-fullname-attribute)
(s/def ::avatar-attribute ::cfg/ldap-auth-avatar-attribute)

(s/def ::rpc map?)
(s/def ::session map?)

(defmethod ig/pre-init-spec :app.http.auth/ldap
  [_]
  (s/keys
   :req-un [::rpc ::session]
   :opt-un [::host
            ::port
            ::ssl
            ::starttls
            ::username-attribute
            ::base-dn
            ::username-attribute
            ::email-attribute
            ::fullname-attribute
            ::avatar-attribute]))

(defmethod ig/init-key :app.http.auth/ldap
  [_ {:keys [session rpc] :as cfg}]
  (let [conn (create-connection cfg)]
    (with-meta
      (fn [request]
        (let [data   (:body-params request)]
          (when-some [info (authenticate (assoc cfg
                                                :conn conn
                                                :username (:email data)
                                                :password (:password data)))]
            (let [method-fn (get-in rpc [:methods :mutation :login-or-register])
                  profile   (method-fn {:email (:email info)
                                        :fullname (:fullname info)})
                  uagent    (get-in request [:headers "user-agent"])
                  sid       (session/create! session {:profile-id (:id profile)
                                                      :user-agent uagent})]
              {:status 200
               :cookies (session/cookies session {:value sid})
               :body profile}))))
      {::conn conn})))

(defmethod ig/halt-key! ::client
  [_ handler]
  (let [{:keys [::conn]} (meta handler)]
    (when (realized? conn)
      (.close @conn))))

(defn- replace-several [s & {:as replacements}]
  (reduce-kv clojure.string/replace s replacements))

(defn- create-connection
  [cfg]
  (let [params (merge {:host {:address (:host cfg)
                              :port (:port cfg)}}
                      (-> cfg
                          (select-keys [:ssl
                                        :starttls
                                        :ldap-bind-dn
                                        :ldap-bind-password])
                          (set/rename-keys {:ssl :ssl?
                                            :starttls :startTLS?
                                            :ldap-bind-dn  :bind-dn
                                            :ldap-bind-password :password})))]
    (delay
      (try
        (client/connect params)
        (catch Exception e
          (log/errorf e "Cannot connect to LDAP %s:%s"
                      (:host cfg) (:port cfg)))))))


(defn- authenticate
  [{:keys [conn username password] :as cfg}]
  (when-some [conn (some-> conn deref)]
    (let [user-search-query (replace-several (:user-query cfg) "$username" username)
          user-attributes   (-> cfg
                                (select-keys [:username-attribute
                                              :email-attribute
                                              :fullname-attribute
                                              :avatar-attribute])
                                vals)]
      (when-some [user-entry (-> conn
                                 (client/search (:base-dn cfg)
                                                {:filter user-search-query
                                                 :sizelimit 1
                                                 :attributes user-attributes})
                                 (first))]
        (when-not (client/bind? conn (:dn user-entry) password)
          (ex/raise :type :authentication
                    :code :wrong-credentials))
        (set/rename-keys user-entry {(keyword (:avatar-attribute cfg)) :photo
                                     (keyword (:fullname-attribute cfg)) :fullname
                                     (keyword (:email-attribute cfg)) :email})))))


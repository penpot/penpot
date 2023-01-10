;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth.ldap
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [clj-ldap.client :as ldap]
   [clojure.spec.alpha :as s]
   [clojure.string]
   [integrant.core :as ig]))

(defn- prepare-params
  [cfg]
  {:ssl?      (:ssl cfg)
   :startTLS? (:tls cfg)
   :bind-dn   (:bind-dn cfg)
   :password  (:bind-password cfg)
   :host      {:address (:host cfg)
               :port    (:port cfg)}})

(defn- connect
  "Connects to the LDAP provider and returns a connection. An
  exception is raised if no connection is possible."
  ^java.lang.AutoCloseable
  [cfg]
  (try
    (-> cfg prepare-params ldap/connect)
    (catch Throwable cause
      (ex/raise :type :restriction
                :code :unable-to-connect-to-ldap
                :hint "unable to connect to ldap server"
                :cause cause))))

(defn- replace-several [s & {:as replacements}]
  (reduce-kv clojure.string/replace s replacements))

(defn- search-user
  [{:keys [::conn base-dn] :as cfg} email]
  (let [query  (replace-several (:query cfg) ":username" email)
        attrs  [(:attrs-username cfg)
                (:attrs-email cfg)
                (:attrs-fullname cfg)]
        params  {:filter query
                 :sizelimit 1
                 :attributes attrs}]
    (first (ldap/search conn base-dn params))))

(defn- retrieve-user
  [{:keys [::conn] :as cfg} {:keys [email password]}]
  (when-let [{:keys [dn] :as user} (search-user cfg email)]
    (when (ldap/bind? conn dn password)
      {:fullname (get user (-> cfg :attrs-fullname keyword))
       :email    email
       :backend  "ldap"})))

(s/def ::fullname ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::backend ::us/not-empty-string)

(s/def ::info-data
  (s/keys :req-un [::fullname ::email ::backend]))

(defn authenticate
  [cfg params]
  (with-open [conn (connect cfg)]
    (when-let [user (-> (assoc cfg ::conn conn)
                        (retrieve-user params))]
      (when-not (s/valid? ::info-data user)
        (let [explain (s/explain-str ::info-data user)]
          (l/warn ::l/raw (str "invalid response from ldap, looks like ldap is not configured correctly\n" explain))
          (ex/raise :type :restriction
                    :code :wrong-ldap-response
                    :explain explain)))
      user)))

(defn- try-connectivity
  [cfg]
  ;; If we have ldap parameters, try to establish connection
  (when (and (:bind-dn cfg)
             (:bind-password cfg)
             (:host cfg)
             (:port cfg))
    (try
      (with-open [_ (connect cfg)]
        (l/info :hint "provider initialized"
                :provider "ldap"
                :host (:host cfg)
                :port (:port cfg)
                :tls? (:tls cfg)
                :ssl? (:ssl cfg)
                :bind-dn (:bind-dn cfg)
                :base-dn (:base-dn cfg)
                :query (:query cfg))
        cfg)
      (catch Throwable cause
        (l/error :hint "unable to connect to LDAP server (LDAP auth provider disabled)"
                 :host (:host cfg) :port (:port cfg) :cause cause)
        nil))))

(s/def ::enabled? ::us/boolean)
(s/def ::host ::cf/ldap-host)
(s/def ::port ::cf/ldap-port)
(s/def ::ssl ::cf/ldap-ssl)
(s/def ::tls ::cf/ldap-starttls)
(s/def ::query ::cf/ldap-user-query)
(s/def ::base-dn ::cf/ldap-base-dn)
(s/def ::bind-dn ::cf/ldap-bind-dn)
(s/def ::bind-password ::cf/ldap-bind-password)
(s/def ::attrs-email ::cf/ldap-attrs-email)
(s/def ::attrs-fullname ::cf/ldap-attrs-fullname)
(s/def ::attrs-username ::cf/ldap-attrs-username)

(s/def ::provider-params
  (s/keys :opt-un [::host ::port
                   ::ssl ::tls
                   ::enabled?
                   ::bind-dn
                   ::bind-password
                   ::query
                   ::attrs-email
                   ::attrs-username
                   ::attrs-fullname]))
(s/def ::provider
  (s/nilable ::provider-params))

(defmethod ig/pre-init-spec ::provider
  [_]
  (s/spec ::provider))

(defmethod ig/init-key ::provider
  [_ cfg]
  (when (:enabled? cfg)
    (try-connectivity cfg)))

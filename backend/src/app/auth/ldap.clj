;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth.ldap
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [clj-ldap.client :as ldap]
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

(def ^:private schema:info-data
  [:map
   [:fullname ::sm/text]
   [:email ::sm/email]
   [:backend ::sm/text]])

(def ^:private valid-info-data?
  (sm/lazy-validator schema:info-data))

(def ^:private explain-info-data
  (sm/lazy-explainer schema:info-data))

(defn authenticate
  [cfg params]
  (with-open [conn (connect cfg)]
    (when-let [user (-> (assoc cfg ::conn conn)
                        (retrieve-user params))]
      (when-not (valid-info-data? user)
        (let [explain (explain-info-data user)]
          (l/warn :hint "invalid response from ldap, looks like ldap is not configured correctly" :data user)
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

(def ^:private schema:params
  [:map
   [:host {:optional true} :string]
   [:port {:optional true} ::sm/int]
   [:bind-dn {:optional true} :string]
   [:bind-passwor {:optional true} :string]
   [:query {:optional true} :string]
   [:base-dn {:optional true} :string]
   [:attrs-email {:optional true} :string]
   [:attrs-username {:optional true} :string]
   [:attrs-fullname {:optional true} :string]
   [:ssl {:optional true} ::sm/boolean]
   [:tls {:optional true} ::sm/boolean]])

(def ^:private check-params
  (sm/check-fn schema:params :hint "Invalid LDAP provider parameters"))

(defmethod ig/assert-key ::provider
  [_ params]
  (when (:enabled params)
    (some->> params check-params)))

(defmethod ig/init-key ::provider
  [_ cfg]
  (when (:enabled cfg)
    (try-connectivity cfg)))

(sm/register! ::provider schema:params)

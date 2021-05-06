;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.oauth
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.http :as http]
   [app.util.logging :as l]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(defn redirect-response
  [uri]
  {:status 302
   :headers {"location" (str uri)}
   :body ""})

(defn generate-error-redirect-uri
  [cfg]
  (-> (u/uri (:public-uri cfg))
      (assoc :path "/#/auth/login")
      (assoc :query (u/map->query-string {:error "unable-to-auth"}))))

(defn register-profile
  [{:keys [rpc] :as cfg} info]
  (let [method-fn (get-in rpc [:methods :mutation :login-or-register])
        profile   (method-fn info)]
    (cond-> profile
      (some? (:invitation-token info))
      (assoc :invitation-token (:invitation-token info)))))

(defn generate-redirect-uri
  [{:keys [tokens] :as cfg} profile]
  (let [token (or (:invitation-token profile)
                  (tokens :generate {:iss :auth
                                     :exp (dt/in-future "15m")
                                     :profile-id (:id profile)}))]
    (-> (u/uri (:public-uri cfg))
        (assoc :path "/#/auth/verify-token")
        (assoc :query (u/map->query-string {:token token})))))

(defn- build-redirect-uri
  [{:keys [provider] :as cfg}]
  (let [public (u/uri (:public-uri cfg))]
    (str (assoc public :path (str "/api/auth/oauth/" (:name provider) "/callback")))))

(defn- build-auth-uri
  [{:keys [provider] :as cfg} state]
  (let [params {:client_id (:client-id provider)
                :redirect_uri (build-redirect-uri cfg)
                :response_type "code"
                :state state
                :scope (str/join " " (:scopes provider []))}
        query  (u/map->query-string params)]
    (-> (u/uri (:auth-uri provider))
        (assoc :query query)
        (str))))

(defn retrieve-access-token
  [{:keys [provider] :as cfg} code]
  (try
    (let [params {:client_id (:client-id provider)
                  :client_secret (:client-secret provider)
                  :code code
                  :grant_type "authorization_code"
                  :redirect_uri (build-redirect-uri cfg)}
          req    {:method :post
                  :headers {"content-type" "application/x-www-form-urlencoded"}
                  :uri (:token-uri provider)
                  :body (u/map->query-string params)}
          res    (http/send! req)]
      (when (= 200 (:status res))
        (let [data (json/read-str (:body res))]
          {:token (get data "access_token")
           :type  (get data "token_type")})))
    (catch Exception e
      (l/error :hint "unexpected error on retrieve-access-token"
               :cause e)
      nil)))

(defn- retrieve-user-info
  [{:keys [provider] :as cfg} tdata]
  (try
    (let [req {:uri (:user-uri provider)
               :headers {"Authorization" (str (:type tdata) " " (:token tdata))}
               :timeout 6000
               :method :get}
          res (http/send! req)]

      (when (= 200 (:status res))
        (let [{:keys [name] :as data} (json/read-str (:body res) :key-fn keyword)]
          (-> data
              (assoc :backend (:name provider))
              (assoc :fullname name)))))

    (catch Exception e
      (l/error :hint "unexpected exception on retrieve-user-info"
               :cause e)
      nil)))

(defn retrieve-info
  [{:keys [tokens provider] :as cfg} request]
  (let [state  (get-in request [:params :state])
        state  (tokens :verify {:token state :iss :oauth})
        info   (some->> (get-in request [:params :code])
                        (retrieve-access-token cfg)
                        (retrieve-user-info cfg))]
    (when-not info
      (ex/raise :type :internal
                :code :unable-to-auth))

    ;; If the provider is OIDC, we can proceed to check
    ;; roles if they are defined.
    (when (and (= "oidc" (:name provider))
               (seq (:roles provider)))
      (let [provider-roles (into #{} (:roles provider))
            profile-roles  (let [attr  (cf/get :oidc-roles-attr :roles)
                                 roles (get info attr)]
                             (cond
                               (string? roles) (into #{} (str/words roles))
                               (vector? roles) (into #{} roles)
                               :else #{}))]
        ;; check if profile has a configured set of roles
        (when-not (set/subset? provider-roles profile-roles)
          (ex/raise :type :internal
                    :code :unable-to-auth
                    :hint "not enought permissions"))))

    (cond-> info
      (some? (:invitation-token state))
      (assoc :invitation-token (:invitation-token state)))))

;; --- HTTP HANDLERS

(defn- auth-handler
  [{:keys [tokens] :as cfg} request]
  (let [invitation (get-in request [:params :invitation-token])
        state      (tokens :generate
                           {:iss :oauth
                            :invitation-token invitation
                            :exp (dt/in-future "15m")})
        uri        (build-auth-uri cfg state)]
    {:status 200
     :body {:redirect-uri uri}}))

(defn- callback-handler
  [{:keys [session] :as cfg} request]
  (try
    (let [info    (retrieve-info cfg request)
          profile (register-profile cfg info)
          uri     (generate-redirect-uri cfg profile)
          sxf     ((:create session) (:id profile))]
      (->> (redirect-response uri)
           (sxf request)))
    (catch Exception _e
      (-> (generate-error-redirect-uri cfg)
          (redirect-response)))))

;; --- INIT

(declare initialize)

(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)
(s/def ::rpc map?)

(defmethod ig/pre-init-spec :app.http.oauth/handlers [_]
  (s/keys :req-un [::public-uri ::session ::tokens ::rpc]))

(defn wrap-handler
  [cfg handler]
  (fn [request]
    (let [provider (get-in request [:path-params :provider])
          provider (get-in @cfg [:providers provider])]
      (when-not provider
        (ex/raise :type :not-found
                  :context {:provider provider}
                  :hint "provider not configured"))
      (-> (assoc @cfg :provider provider)
          (handler request)))))

(defmethod ig/init-key :app.http.oauth/handlers
  [_ cfg]
  (let [cfg (initialize cfg)]
    {:handler (wrap-handler cfg auth-handler)
     :callback-handler (wrap-handler cfg callback-handler)}))

(defn- discover-oidc-config
  [{:keys [base-uri] :as opts}]
  (let [discovery-uri (u/join base-uri ".well-known/openid-configuration")
        response      (http/send! {:method :get :uri (str discovery-uri)})]
    (when (= 200 (:status response))
      (let [data (json/read-str (:body response))]
        (assoc opts
               :token-uri (get data "token_endpoint")
               :auth-uri (get data "authorization_endpoint")
               :user-uri (get data "userinfo_endpoint"))))))

(defn- initialize-oidc-provider
  [cfg]
  (let [opts {:base-uri      (cf/get :oidc-base-uri)
              :client-id     (cf/get :oidc-client-id)
              :client-secret (cf/get :oidc-client-secret)
              :token-uri     (cf/get :oidc-token-uri)
              :auth-uri      (cf/get :oidc-auth-uri)
              :user-uri      (cf/get :oidc-user-uri)
              :scopes        (into #{"openid" "profile" "email" "name"}
                                   (cf/get :oidc-scopes #{}))
              :roles-attr    (cf/get :oidc-roles-attr)
              :roles         (cf/get :oidc-roles)
              :name          "oidc"}]
    (if (and (string? (:base-uri opts))
             (string? (:client-id opts))
             (string? (:client-secret opts)))
      (if (and (string? (:token-uri opts))
               (string? (:user-uri opts))
               (string? (:auth-uri opts)))
        (do
          (l/info :action "initialize" :provider "oid" :method "static")
          (assoc-in cfg [:providers "oidc"] opts))
        (let [opts (discover-oidc-config opts)]
          (l/info :action "initialize" :provider "oid" :method "discover")
          (assoc-in cfg [:providers "oidc"] opts)))
      cfg)))

(defn- initialize-google-provider
  [cfg]
  (let [opts {:client-id     (cf/get :google-client-id)
              :client-secret (cf/get :google-client-secret)
              :scopes        #{"email" "profile" "openid"
                               "https://www.googleapis.com/auth/userinfo.email"
                               "https://www.googleapis.com/auth/userinfo.profile"}
              :auth-uri      "https://accounts.google.com/o/oauth2/v2/auth"
              :token-uri     "https://oauth2.googleapis.com/token"
              :user-uri      "https://openidconnect.googleapis.com/v1/userinfo"
              :name          "google"}]
    (if (and (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/info :action "initialize" :provider "google")
        (assoc-in cfg [:providers "google"] opts))
      cfg)))

(defn- initialize-github-provider
  [cfg]
  (let [opts {:client-id     (cf/get :github-client-id)
              :client-secret (cf/get :github-client-secret)
              :scopes        #{"read:user"
                               "user:email"}
              :auth-uri      "https://github.com/login/oauth/authorize"
              :token-uri     "https://github.com/login/oauth/access_token"
              :user-uri      "https://api.github.com/user"
              :name          "github"}]
    (if (and (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/info :action "initialize" :provider "github")
        (assoc-in cfg [:providers "github"] opts))
      cfg)))


(defn- initialize-gitlab-provider
  [cfg]
  (let [base (cf/get :gitlab-base-uri "https://gitlab.com")
        opts {:base-uri      base
              :client-id     (cf/get :gitlab-client-id)
              :client-secret (cf/get :gitlab-client-secret)
              :scopes        #{"read_user"}
              :auth-uri      (str base "/oauth/authorize")
              :token-uri     (str base "/oauth/token")
              :user-uri      (str base "/api/v4/user")
              :name          "gitlab"}]
    (if (and (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/info :action "initialize" :provider "gitlab")
        (assoc-in cfg [:providers "gitlab"] opts))
      cfg)))

(defn- initialize
  [cfg]
  (let [cfg (agent cfg :error-mode :continue)]
    (send-off cfg initialize-google-provider)
    (send-off cfg initialize-gitlab-provider)
    (send-off cfg initialize-github-provider)
    (send-off cfg initialize-oidc-provider)
    cfg))

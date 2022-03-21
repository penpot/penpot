;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.oauth
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.rpc.queries.profile :as profile]
   [app.util.json :as json]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.response :as yrs]))

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

(defn- qualify-props
  [provider props]
  (reduce-kv (fn [result k v]
               (assoc result (keyword (:name provider) (name k)) v))
             {}
             props))

(defn retrieve-access-token
  [{:keys [provider http-client] :as cfg} code]
  (let [params {:client_id (:client-id provider)
                :client_secret (:client-secret provider)
                :code code
                :grant_type "authorization_code"
                :redirect_uri (build-redirect-uri cfg)}
        req    {:method :post
                :headers {"content-type" "application/x-www-form-urlencoded"
                          "accept" "application/json"}
                :uri (:token-uri provider)
                :body (u/map->query-string params)}]
    (p/then
     (http-client req)
     (fn [{:keys [status body] :as res}]
       (if (= status 200)
         (let [data (json/read body)]
           {:token (get data :access_token)
            :type (get data :token_type)})
         (ex/raise :type :internal
                   :code :unable-to-retrieve-token
                   :http-status status
                   :http-body body))))))

(defn- retrieve-user-info
  [{:keys [provider http-client] :as cfg} tdata]
  (letfn [(retrieve []
            (http-client {:uri (:user-uri provider)
                          :headers {"Authorization" (str (:type tdata) " " (:token tdata))}
                          :timeout 6000
                          :method :get}))

          (validate-response [{:keys [status body] :as res}]
            (when-not (= 200 status)
              (ex/raise :type :internal
                        :code :unable-to-retrieve-user-info
                        :hint "unable to retrieve user info"
                        :http-status status
                        :http-body body))
            res)

          (get-email [info]
            (let [attr-kw (cf/get :oidc-email-attr :email)]
              (get info attr-kw)))

          (get-name [info]
            (let [attr-kw (cf/get :oidc-name-attr :name)]
              (get info attr-kw)))

          (process-response [{:keys [body]}]
            (let [info (json/read body)]
              {:backend  (:name provider)
               :email    (get-email info)
               :fullname (get-name info)
               :props (->> (dissoc info :name :email)
                           (qualify-props provider))}))

          (validate-info [info]
            (when-not (s/valid? ::info info)
              (l/warn :hint "received incomplete profile info object (please set correct scopes)"
                      :info (pr-str info))
              (ex/raise :type :internal
                        :code :incomplete-user-info
                        :hint "inconmplete user info"
                        :info info))
            info)]

    (-> (retrieve)
        (p/then' validate-response)
        (p/then' process-response)
        (p/then' validate-info))))

(s/def ::backend ::us/not-empty-string)
(s/def ::email ::us/not-empty-string)
(s/def ::fullname ::us/not-empty-string)
(s/def ::props (s/map-of ::us/keyword any?))
(s/def ::info
  (s/keys :req-un [::backend
                   ::email
                   ::fullname
                   ::props]))

(defn retrieve-info
  [{:keys [tokens provider] :as cfg} {:keys [params] :as request}]
  (letfn [(validate-oidc [info]
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
                            :hint "not enough permissions"))))
            info)

          (post-process [state info]
            (cond-> info
              (some? (:invitation-token state))
              (assoc :invitation-token (:invitation-token state))

              ;; If state token comes with props, merge them. The state token
              ;; props can contain pm_ and utm_ prefixed query params.
              (map? (:props state))
              (update :props merge (:props state))))]

    (when-let [error (get params :error)]
      (ex/raise :type :internal
                :code :error-on-retrieving-code
                :error-id error
                :error-desc (get params :error_description)))

    (let [state  (get params :state)
          code   (get params :code)
          state  (tokens :verify {:token state :iss :oauth})]
      (-> (p/resolved code)
          (p/then #(retrieve-access-token cfg %))
          (p/then #(retrieve-user-info cfg %))
          (p/then' validate-oidc)
          (p/then' (partial post-process state))))))

;; --- HTTP HANDLERS

(defn- retrieve-profile
  [{:keys [pool executor] :as cfg} info]
  (px/with-dispatch executor
    (with-open [conn (db/open pool)]
      (some->> (:email info)
               (profile/retrieve-profile-data-by-email conn)
               (profile/populate-additional-data conn)
               (profile/decode-profile-row)))))

(defn- redirect-response
  [uri]
  (yrs/response :status 302 :headers {"location" (str uri)}))

(defn- generate-error-redirect
  [cfg error]
  (let [uri (-> (u/uri (:public-uri cfg))
                (assoc :path "/#/auth/login")
                (assoc :query (u/map->query-string {:error "unable-to-auth" :hint (ex-message error)})))]
    (redirect-response uri)))

(defn- generate-redirect
  [{:keys [tokens session audit] :as cfg} request info profile]
  (if profile
    (let [sxf    ((:create session) (:id profile))
          token  (or (:invitation-token info)
                     (tokens :generate {:iss :auth
                                        :exp (dt/in-future "15m")
                                        :profile-id (:id profile)}))
          params {:token token}

          uri    (-> (u/uri (:public-uri cfg))
                     (assoc :path "/#/auth/verify-token")
                     (assoc :query (u/map->query-string params)))]

      (when (fn? audit)
        (audit :cmd :submit
               :type "mutation"
               :name "login"
               :profile-id (:id profile)
               :ip-addr (audit/parse-client-ip request)
               :props (audit/profile->props profile)))

      (->> (redirect-response uri)
           (sxf request)))

    (let [info   (assoc info
                        :iss :prepared-register
                        :is-active true
                        :exp (dt/in-future {:hours 48}))
          token  (tokens :generate info)
          params (d/without-nils
                  {:token token
                   :fullname (:fullname info)})
          uri    (-> (u/uri (:public-uri cfg))
                     (assoc :path "/#/auth/register/validate")
                     (assoc :query (u/map->query-string params)))]
      (redirect-response uri))))

(defn- auth-handler
  [{:keys [tokens] :as cfg} {:keys [params] :as request} respond raise]
  (try
    (let [props (audit/extract-utm-params params)
          state (tokens :generate
                        {:iss :oauth
                         :invitation-token (:invitation-token params)
                         :props props
                         :exp (dt/in-future "15m")})
          uri   (build-auth-uri cfg state)]
      (respond (yrs/response 200 {:redirect-uri uri})))
    (catch Throwable cause
      (raise cause))))

(defn- callback-handler
  [cfg request respond _]
  (letfn [(process-request []
            (p/let [info    (retrieve-info cfg request)
                    profile (retrieve-profile cfg info)]
              (generate-redirect cfg request info profile)))

          (handle-error [cause]
            (l/error :hint "error on oauth process" :cause cause)
            (respond (generate-error-redirect cfg cause)))]

    (-> (process-request)
        (p/then respond)
        (p/catch handle-error))))

;; --- INIT

(declare initialize)

(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)
(s/def ::rpc map?)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::public-uri ::session ::tokens ::rpc ::db/pool]))

(defn wrap-handler
  [cfg handler]
  (fn [request respond raise]
    (let [provider (get-in request [:path-params :provider])
          provider (get-in @cfg [:providers provider])]
      (if provider
        (handler (assoc @cfg :provider provider)
                 request
                 respond
                 raise)
        (raise
         (ex/error
          :type :not-found
          :provider provider
          :hint "provider not configured"))))))

(defmethod ig/init-key ::handler
  [_ cfg]
  (let [cfg (initialize cfg)]
    {:handler (wrap-handler cfg auth-handler)
     :callback-handler (wrap-handler cfg callback-handler)}))

(defn- discover-oidc-config
  [{:keys [http-client]} {:keys [base-uri] :as opts}]

  (let [discovery-uri (u/join base-uri ".well-known/openid-configuration")
        response      (ex/try (http-client {:method :get :uri (str discovery-uri)} {:sync? true}))]
    (cond
      (ex/exception? response)
      (do
        (l/warn :hint "unable to discover oidc configuration"
                :discover-uri (str discovery-uri)
                :cause response)
        nil)

      (= 200 (:status response))
      (let [data (json/read (:body response))]
        {:token-uri (get data :token_endpoint)
         :auth-uri  (get data :authorization_endpoint)
         :user-uri  (get data :userinfo_endpoint)})

      :else
      (do
        (l/warn :hint "unable to discover OIDC configuration"
                :uri (str discovery-uri)
                :response-status-code (:status response))
        nil))))

(defn- obfuscate-string
  [s]
  (if (< (count s) 10)
    (apply str (take (count s) (repeat "*")))
    (str (subs s 0 5)
         (apply str (take (- (count s) 5) (repeat "*"))))))

(defn- initialize-oidc-provider
  [cfg]
  (let [opts {:base-uri      (cf/get :oidc-base-uri)
              :client-id     (cf/get :oidc-client-id)
              :client-secret (cf/get :oidc-client-secret)
              :token-uri     (cf/get :oidc-token-uri)
              :auth-uri      (cf/get :oidc-auth-uri)
              :user-uri      (cf/get :oidc-user-uri)
              :scopes        (cf/get :oidc-scopes #{"openid" "profile" "email"})
              :roles-attr    (cf/get :oidc-roles-attr)
              :roles         (cf/get :oidc-roles)
              :name          "oidc"}]

    (if (and (string? (:base-uri opts))
             (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/debug :hint "initialize oidc provider" :name "generic-oidc"
                 :opts (update opts :client-secret obfuscate-string))
        (if (and (string? (:token-uri opts))
                 (string? (:user-uri opts))
                 (string? (:auth-uri opts)))
          (do
            (l/debug :hint "initialized with user provided configuration")
            (assoc-in cfg [:providers "oidc"] opts))
          (do
            (l/debug :hint "trying to discover oidc provider configuration using BASE_URI")
            (if-let [opts' (discover-oidc-config cfg opts)]
              (do
                (l/debug :hint "discovered opts" :additional-opts opts')
                (assoc-in cfg [:providers "oidc"] (merge opts opts')))

              cfg))))
      cfg)))

(defn- initialize-google-provider
  [cfg]
  (let [opts {:client-id     (cf/get :google-client-id)
              :client-secret (cf/get :google-client-secret)
              :scopes        #{"openid" "email" "profile"}
              :auth-uri      "https://accounts.google.com/o/oauth2/v2/auth"
              :token-uri     "https://oauth2.googleapis.com/token"
              :user-uri      "https://openidconnect.googleapis.com/v1/userinfo"
              :name          "google"}]
    (if (and (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/info :action "initialize" :provider "google"
                :opts (pr-str (update opts :client-secret obfuscate-string)))
        (assoc-in cfg [:providers "google"] opts))
      cfg)))

(defn- initialize-github-provider
  [cfg]
  (let [opts {:client-id     (cf/get :github-client-id)
              :client-secret (cf/get :github-client-secret)
              :scopes        #{"read:user" "user:email"}
              :auth-uri      "https://github.com/login/oauth/authorize"
              :token-uri     "https://github.com/login/oauth/access_token"
              :user-uri      "https://api.github.com/user"
              :name          "github"}]
    (if (and (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/info :action "initialize" :provider "github"
                :opts (pr-str (update opts :client-secret obfuscate-string)))
        (assoc-in cfg [:providers "github"] opts))
      cfg)))

(defn- initialize-gitlab-provider
  [cfg]
  (let [base (cf/get :gitlab-base-uri "https://gitlab.com")
        opts {:base-uri      base
              :client-id     (cf/get :gitlab-client-id)
              :client-secret (cf/get :gitlab-client-secret)
              :scopes        #{"openid" "profile" "email"}
              :auth-uri      (str base "/oauth/authorize")
              :token-uri     (str base "/oauth/token")
              :user-uri      (str base "/oauth/userinfo")
              :name          "gitlab"}]
    (if (and (string? (:client-id opts))
             (string? (:client-secret opts)))
      (do
        (l/info :action "initialize" :provider "gitlab"
                :opts (pr-str (update opts :client-secret obfuscate-string)))
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

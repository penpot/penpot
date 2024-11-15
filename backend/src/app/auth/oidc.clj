;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth.oidc
  "OIDC client implementation."
  (:require
   [app.auth.oidc.providers :as-alias providers]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.email.blacklist :as email.blacklist]
   [app.email.whitelist :as email.whitelist]
   [app.http.client :as http]
   [app.http.errors :as errors]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.rpc :as rpc]
   [app.rpc.commands.profile :as profile]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.util.inet :as inet]
   [app.util.json :as json]
   [app.util.time :as dt]
   [buddy.sign.jwk :as jwk]
   [buddy.sign.jwt :as jwt]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [yetti.request :as yreq]
   [yetti.response :as-alias yres]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn obfuscate-string
  [s]
  (if (< (count s) 10)
    (apply str (take (count s) (repeat "*")))
    (str (subs s 0 5)
         (apply str (take (- (count s) 5) (repeat "*"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OIDC PROVIDER (GENERIC)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- discover-oidc-config
  [cfg {:keys [base-uri] :as opts}]
  (let [uri (dm/str (u/join base-uri ".well-known/openid-configuration"))
        rsp (http/req! cfg {:method :get :uri uri} {:sync? true})]
    (if (= 200 (:status rsp))
      (let [data      (-> rsp :body json/decode)
            token-uri (get data :token_endpoint)
            auth-uri  (get data :authorization_endpoint)
            user-uri  (get data :userinfo_endpoint)
            jwks-uri  (get data :jwks_uri)]

        (l/debug :hint "oidc uris discovered"
                 :token-uri token-uri
                 :auth-uri auth-uri
                 :user-uri user-uri
                 :jwks-uri jwks-uri)

        {:token-uri token-uri
         :auth-uri  auth-uri
         :user-uri  user-uri
         :jwks-uri jwks-uri})
      (do
        (l/warn :hint "unable to discover OIDC configuration"
                :discover-uri uri
                :http-status (:status rsp))
        nil))))

(defn- prepare-oidc-opts
  [cfg]
  (let [opts {:base-uri      (cf/get :oidc-base-uri)
              :client-id     (cf/get :oidc-client-id)
              :client-secret (cf/get :oidc-client-secret)
              :token-uri     (cf/get :oidc-token-uri)
              :auth-uri      (cf/get :oidc-auth-uri)
              :user-uri      (cf/get :oidc-user-uri)
              :jwks-uri      (cf/get :oidc-jwks-uri)
              :scopes        (cf/get :oidc-scopes #{"openid" "profile" "email"})
              :roles-attr    (cf/get :oidc-roles-attr)
              :roles         (cf/get :oidc-roles)
              :name          "oidc"}

        opts (d/without-nils opts)]

    (when (and (string? (:base-uri opts))
               (string? (:client-id opts))
               (string? (:client-secret opts)))
      (if (and (string? (:token-uri opts))
               (string? (:user-uri opts))
               (string? (:auth-uri opts)))
        opts
        (try
          (-> (discover-oidc-config cfg opts)
              (merge opts {:discover? true}))
          (catch Throwable cause
            (l/warn :hint "unable to discover OIDC configuration"
                    :cause cause)))))))

(defn- process-oidc-jwks
  [keys]
  (reduce (fn [result {:keys [kid] :as kdata}]
            (let [pkey (ex/try! (jwk/public-key kdata))]
              (if (ex/exception? pkey)
                (do
                  (l/warn :hint "unable to create public key"
                          :kid (:kid kdata)
                          :cause pkey)
                  result)
                (assoc result kid pkey))))
          {}
          keys))

(defn- fetch-oidc-jwks
  [cfg {:keys [jwks-uri]}]
  (when jwks-uri
    (try
      (let [{:keys [status body]} (http/req! cfg {:method :get :uri jwks-uri} {:sync? true})]
        (if (= 200 status)
          (-> body json/decode :keys process-oidc-jwks)
          (do
            (l/warn :hint "unable to retrieve JWKs (unexpected response status code)"
                    :response-status status
                    :response-body  body)
            nil)))
      (catch Throwable cause
        (l/warn :hint "unable to retrieve JWKs (unexpected exception)"
                :cause cause)))))

(defmethod ig/assert-key ::providers/generic
  [_ params]
  (assert (http/client? (::http/client params)) "expected a valid http client"))

(defmethod ig/init-key ::providers/generic
  [_ cfg]
  (when (contains? cf/flags :login-with-oidc)
    (if-let [opts (prepare-oidc-opts cfg)]
      (let [jwks (fetch-oidc-jwks cfg opts)]
        (l/inf :hint "provider initialized"
               :provider "oidc"
               :method (if (:discover? opts) "discover" "manual")
               :client-id (:client-id opts)
               :client-secret (obfuscate-string (:client-secret opts))
               :scopes     (str/join "," (:scopes opts))
               :auth-uri   (:auth-uri opts)
               :user-uri   (:user-uri opts)
               :token-uri  (:token-uri opts)
               :roles-attr (:roles-attr opts)
               :roles      (:roles opts)
               :keys       (str/join "," (map str (keys jwks))))
        (assoc opts :jwks jwks))
      (do
        (l/warn :hint "unable to initialize auth provider, missing configuration" :provider "oidc")
        nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GOOGLE AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::providers/google
  [_ _]
  (let [opts {:client-id     (cf/get :google-client-id)
              :client-secret (cf/get :google-client-secret)
              :scopes        #{"openid" "email" "profile"}
              :auth-uri      "https://accounts.google.com/o/oauth2/v2/auth"
              :token-uri     "https://oauth2.googleapis.com/token"
              :user-uri      "https://openidconnect.googleapis.com/v1/userinfo"
              :name          "google"}]

    (when (contains? cf/flags :login-with-google)
      (if (and (string? (:client-id opts))
               (string? (:client-secret opts)))
        (do
          (l/inf :hint "provider initialized"
                 :provider "google"
                 :client-id (:client-id opts)
                 :client-secret (obfuscate-string (:client-secret opts)))
          opts)

        (do
          (l/warn :hint "unable to initialize auth provider, missing configuration" :provider "google")
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITHUB AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- int-in-range?
  [val start end]
  (and (<= start val) (< val end)))

(defn- retrieve-github-email
  [cfg tdata props]
  (or (some-> props :github/email)
      (let [params {:uri "https://api.github.com/user/emails"
                    :headers {"Authorization" (dm/str (:token/type tdata) " " (:token/access tdata))}
                    :timeout 6000
                    :method :get}

            {:keys [status body]} (http/req! cfg params {:sync? true})]

        (when-not (int-in-range? status 200 300)
          (ex/raise :type :internal
                    :code :unable-to-retrieve-github-emails
                    :hint "unable to retrieve github emails"
                    :request-uri (:uri params)
                    :response-status status
                    :response-body body))

        (->> body json/decode (filter :primary) first :email))))

(defmethod ig/assert-key ::providers/github
  [_ params]
  (assert (http/client? (::http/client params)) "expected a valid http client"))

(defmethod ig/init-key ::providers/github
  [_ cfg]
  (let [opts {:client-id     (cf/get :github-client-id)
              :client-secret (cf/get :github-client-secret)
              :scopes        #{"read:user" "user:email"}
              :auth-uri      "https://github.com/login/oauth/authorize"
              :token-uri     "https://github.com/login/oauth/access_token"
              :user-uri      "https://api.github.com/user"
              :name          "github"

              ;; Additional hooks for provider specific way of
              ;; retrieve emails.
              :get-email-fn  (partial retrieve-github-email cfg)}]

    (when (contains? cf/flags :login-with-github)
      (if (and (string? (:client-id opts))
               (string? (:client-secret opts)))
        (do
          (l/inf :hint "provider initialized"
                 :provider "github"
                 :client-id (:client-id opts)
                 :client-secret (obfuscate-string (:client-secret opts)))
          opts)

        (do
          (l/warn :hint "unable to initialize auth provider, missing configuration" :provider "github")
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITLAB AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/init-key ::providers/gitlab
  [_ cfg]
  (let [base (cf/get :gitlab-base-uri "https://gitlab.com")
        opts {:base-uri      base
              :client-id     (cf/get :gitlab-client-id)
              :client-secret (cf/get :gitlab-client-secret)
              :scopes        #{"openid" "profile" "email"}
              :auth-uri      (str base "/oauth/authorize")
              :token-uri     (str base "/oauth/token")
              :user-uri      (str base "/oauth/userinfo")
              :jwks-uri      (str base "/oauth/discovery/keys")
              :name          "gitlab"}]
    (when (contains? cf/flags :login-with-gitlab)
      (if (and (string? (:client-id opts))
               (string? (:client-secret opts)))
        (let [jwks (fetch-oidc-jwks cfg opts)]
          (l/inf :hint "provider initialized"
                 :provider "gitlab"
                 :base-uri base
                 :client-id (:client-id opts)
                 :client-secret (obfuscate-string (:client-secret opts)))
          (assoc opts :jwks jwks))

        (do
          (l/warn :hint "unable to initialize auth provider, missing configuration" :provider "gitlab")
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-attr-path
  [provider path]
  (let [[fitem & items] (str/split path "__")]
    (into [(keyword (:name provider) fitem)] (map keyword) items)))

(defn- build-redirect-uri
  [{:keys [::provider] :as cfg}]
  (let [public (u/uri (cf/get :public-uri))]
    (str (assoc public :path (str "/api/auth/oauth/" (:name provider) "/callback")))))

(defn- build-auth-uri
  [{:keys [::provider] :as cfg} state]
  (let [params {:client_id (:client-id provider)
                :redirect_uri (build-redirect-uri cfg)
                :response_type "code"
                :state state
                :scope (str/join " " (:scopes provider []))}
        query  (u/map->query-string params)]
    (-> (u/uri (:auth-uri provider))
        (assoc :query query)
        (str))))

(defn- qualify-prop-key
  [provider k]
  (keyword (:name provider) (name k)))

(defn- qualify-props
  [provider props]
  (reduce-kv (fn [result k v]
               (assoc result (qualify-prop-key provider k) v))
             {}
             props))

(defn- fetch-access-token
  [{:keys [::provider] :as cfg} code]
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

    (l/trc :hint "fetch access token"
           :provider (:name provider)
           :client-id (:client-id provider)
           :client-secret (obfuscate-string (:client-secret provider))
           :grant-type (:grant_type params)
           :redirect-uri (:redirect_uri params))

    (let [{:keys [status body]} (http/req! cfg req {:sync? true})]
      (l/trc :hint "access token fetched" :status status :body body)
      (if (= status 200)
        (let [data (json/decode body)
              data {:token/access (get data :access_token)
                    :token/id     (get data :id_token)
                    :token/type   (get data :token_type)}]
          (l/trc :hint "access token fetched"
                 :token-id (:token/id data)
                 :token-type (:token/type data)
                 :token (:token/access data))
          data)
        (ex/raise :type :internal
                  :code :unable-to-fetch-access-token
                  :hint "unable to fetch access token"
                  :request-uri (:uri req)
                  :response-status status
                  :response-body body)))))

(defn- process-user-info
  [provider tdata info]
  (letfn [(get-email [props]
            ;; Allow providers hook into this for custom email
            ;; retrieval method.
            (if-let [get-email-fn (:get-email-fn provider)]
              (get-email-fn tdata props)
              (let [attr-kw (cf/get :oidc-email-attr "email")
                    attr-ph (parse-attr-path provider attr-kw)]
                (get-in props attr-ph))))

          (get-name [props]
            (let [attr-kw (cf/get :oidc-name-attr "name")
                  attr-ph (parse-attr-path provider attr-kw)]
              (get-in props attr-ph)))]

    (let [props (qualify-props provider info)
          email (get-email props)]
      {:backend  (:name provider)
       :fullname (or (get-name props) email)
       :email    email
       :props    props})))

(defn- fetch-user-info
  [{:keys [::provider] :as cfg} tdata]
  (l/trc :hint "fetch user info"
         :uri (:user-uri provider)
         :token (obfuscate-string (:token/access tdata)))

  (let [params   {:uri (:user-uri provider)
                  :headers {"Authorization" (str (:token/type tdata) " " (:token/access tdata))}
                  :timeout 6000
                  :method :get}
        response (http/req! cfg params {:sync? true})]

    (l/trc :hint "user info response"
           :status (:status response)
           :body   (:body response))

    (when-not (int-in-range? (:status response) 200 300)
      (ex/raise :type :internal
                :code :unable-to-retrieve-user-info
                :hint "unable to retrieve user info"
                :http-status (:status response)
                :http-body (:body response)))

    (-> response :body json/decode)))

(defn- get-user-info
  [{:keys [::provider]} tdata]
  (try
    (when (:token/id tdata)
      (let [{:keys [kid alg] :as theader} (jwt/decode-header (:token/id tdata))]
        (when-let [key (if (str/starts-with? (name alg) "hs")
                         (:client-secret provider)
                         (get-in provider [:jwks kid]))]

          (let [claims (jwt/unsign (:token/id tdata) key {:alg alg})]
            (dissoc claims :exp :iss :iat :sid :aud :sub)))))
    (catch Throwable cause
      (l/warn :hint "unable to get user info from JWT token (unexpected exception)"
              :cause cause))))

(def ^:private schema:info
  [:map
   [:backend ::sm/text]
   [:email ::sm/email]
   [:fullname ::sm/text]
   [:props [:map-of :keyword :any]]])

(def ^:private valid-info?
  (sm/validator schema:info))

(defn- get-info
  [{:keys [::provider ::setup/props] :as cfg} {:keys [params] :as request}]
  (let [state  (get params :state)
        code   (get params :code)
        state  (tokens/verify props {:token state :iss :oauth})
        tdata  (fetch-access-token cfg code)
        info   (case (cf/get :oidc-user-info-source)
                 :token (get-user-info cfg tdata)
                 :userinfo (fetch-user-info cfg tdata)
                 (or (get-user-info cfg tdata)
                     (fetch-user-info cfg tdata)))

        info   (process-user-info provider tdata info)]

    (l/trc :hint "user info" :info info)

    (when-not (valid-info? info)
      (l/warn :hint "received incomplete profile info object (please set correct scopes)" :info info)
      (ex/raise :type :internal
                :code :incomplete-user-info
                :hint "inconmplete user info"
                :info info))

    ;; If the provider is OIDC, we can proceed to check
    ;; roles if they are defined.
    (when (and (= "oidc" (:name provider))
               (seq (:roles provider)))

      (let [expected-roles (into #{} (:roles provider))
            current-roles  (let [roles-kw (cf/get :oidc-roles-attr "roles")
                                 roles-ph (parse-attr-path provider roles-kw)
                                 roles    (get-in (:props info) roles-ph)]
                             (cond
                               (string? roles) (into #{} (str/words roles))
                               (vector? roles) (into #{} roles)
                               :else #{}))]

        ;; check if profile has a configured set of roles
        (when-not (set/subset? expected-roles current-roles)
          (ex/raise :type :internal
                    :code :unable-to-auth
                    :hint "not enough permissions"))))

    (cond-> info
      (some? (:invitation-token state))
      (assoc :invitation-token (:invitation-token state))

      (some? (:external-session-id state))
      (assoc :external-session-id (:external-session-id state))

      ;; If state token comes with props, merge them. The state token
      ;; props can contain pm_ and utm_ prefixed query params.
      (map? (:props state))
      (update :props merge (:props state)))))

(defn- get-profile
  [cfg info]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (some->> (:email info)
                          (profile/clean-email)
                          (profile/get-profile-by-email conn)))))

(defn- redirect-response
  [uri]
  {::yres/status 302
   ::yres/headers {"location" (str uri)}})

(defn- redirect-with-error
  ([error] (redirect-with-error error nil))
  ([error hint]
   (let [params {:error error :hint hint}
         params (d/without-nils params)
         uri    (-> (u/uri (cf/get :public-uri))
                    (assoc :path "/#/auth/login")
                    (assoc :query (u/map->query-string params)))]
     (redirect-response uri))))

(defn- redirect-to-register
  [cfg info request]
  (let [info   (assoc info
                      :iss :prepared-register
                      :exp (dt/in-future {:hours 48}))

        params {:token (tokens/generate (::setup/props cfg) info)
                :provider (:provider (:path-params request))
                :fullname (:fullname info)}
        params (d/without-nils params)]

    (redirect-response
     (-> (u/uri (cf/get :public-uri))
         (assoc :path "/#/auth/register/validate")
         (assoc :query (u/map->query-string params))))))

(defn- redirect-to-verify-token
  [token]
  (let [params {:token token}
        uri    (-> (u/uri (cf/get :public-uri))
                   (assoc :path "/#/auth/verify-token")
                   (assoc :query (u/map->query-string params)))]

    (redirect-response uri)))

(defn- provider-has-email-verified?
  [{:keys [::provider] :as cfg} {:keys [props] :as info}]
  (let [prop (qualify-prop-key provider :email_verified)]
    (true? (get props prop))))

(defn- profile-has-provider-props?
  [{:keys [::provider] :as cfg} profile]
  (let [prop (qualify-prop-key provider :email)]
    (contains? (:props profile) prop)))

(defn- provider-matches-profile?
  [{:keys [::provider] :as cfg} profile info]
  (or (= (:auth-backend profile) (:name provider))
      (profile-has-provider-props? cfg profile)
      (provider-has-email-verified? cfg info)))

(defn- process-callback
  [cfg request info profile]
  (cond
    (some? profile)
    (cond
      (:is-blocked profile)
      (redirect-with-error "profile-blocked")

      (not (provider-matches-profile? cfg profile info))
      (redirect-with-error "auth-provider-not-allowed")

      (not (:is-active profile))
      (let [info (assoc info :profile-id (:id profile))]
        (redirect-to-register cfg info request))

      :else
      (let [sxf     (session/create-fn cfg (:id profile))
            token   (or (:invitation-token info)
                        (tokens/generate (::setup/props cfg)
                                         {:iss :auth
                                          :exp (dt/in-future "15m")
                                          :profile-id (:id profile)}))
            props   (audit/profile->props profile)
            context (d/without-nils {:external-session-id (:external-session-id info)})]

        (audit/submit! cfg {::audit/type "action"
                            ::audit/name "login-with-oidc"
                            ::audit/profile-id (:id profile)
                            ::audit/ip-addr (inet/parse-request request)
                            ::audit/props props
                            ::audit/context context})

        (->> (redirect-to-verify-token token)
             (sxf request))))

    (and (email.blacklist/enabled? cfg)
         (email.blacklist/contains? cfg (:email info)))
    (redirect-with-error "email-domain-not-allowed")

    (and (email.whitelist/enabled? cfg)
         (not (email.whitelist/contains? cfg (:email info))))
    (redirect-with-error "email-domain-not-allowed")

    :else
    (let [info (assoc info :is-active (provider-has-email-verified? cfg info))]
      (if (or (contains? cf/flags :registration)
              (contains? cf/flags :oidc-registration))
        (redirect-to-register cfg info request)
        (redirect-with-error "registration-disabled")))))

(defn- get-external-session-id
  [request]
  (let [session-id (yreq/get-header request "x-external-session-id")]
    (when (string? session-id)
      (if (or (> (count session-id) 256)
              (= session-id "null")
              (str/blank? session-id))
        nil
        session-id))))

(defn- auth-handler
  [cfg {:keys [params] :as request}]
  (let [props  (audit/extract-utm-params params)
        esid   (rpc/get-external-session-id request)
        params {:iss :oauth
                :invitation-token (:invitation-token params)
                :external-session-id esid
                :props props
                :exp (dt/in-future "4h")}
        state  (tokens/generate (::setup/props cfg)
                                (d/without-nils params))
        uri    (build-auth-uri cfg state)]
    {::yres/status 200
     ::yres/body {:redirect-uri uri}}))

(defn- callback-handler
  [{:keys [::provider] :as cfg} request]
  (try
    (if-let [error (dm/get-in request [:params :error])]
      (redirect-with-error "unable-to-auth" error)
      (let [info    (get-info cfg request)
            profile (get-profile cfg info)]
        (process-callback cfg request info profile)))
    (catch Throwable cause
      (binding [l/*context* (-> (errors/request->context request)
                                (assoc :auth/provider (:name provider)))]
        (let [edata (ex-data cause)]
          (cond
            (= :validation (:type edata))
            (l/wrn :hint "invalid token received" :cause cause)

            :else
            (l/err :hint "error on oauth process" :cause cause))))

      (redirect-with-error "unable-to-auth" (ex-message cause)))))

(def provider-lookup
  {:compile
   (fn [& _]
     (fn [handler {:keys [::providers] :as cfg}]
       (fn [request]
         (let [provider (some-> request :path-params :provider keyword)]
           (if-let [provider (get providers provider)]
             (handler (assoc cfg ::provider provider) request)
             (ex/raise :type :restriction
                       :code :provider-not-configured
                       :provider provider
                       :hint "provider not configured"))))))})

(def ^:private schema:provider
  [:map {:title "provider"}
   [:client-id ::sm/text]
   [:client-secret ::sm/text]
   [:base-uri {:optional true} ::sm/text]
   [:token-uri {:optional true} ::sm/text]
   [:auth-uri {:optional true} ::sm/text]
   [:user-uri {:optional true} ::sm/text]
   [:scopes {:optional true}
    [::sm/set ::sm/text]]
   [:roles {:optional true}
    [::sm/set ::sm/text]]
   [:roles-attr {:optional true} ::sm/text]
   [:email-attr {:optional true} ::sm/text]
   [:name-attr {:optional true} ::sm/text]])

(def ^:private schema:routes-params
  [:map
   ::session/manager
   ::http/client
   ::setup/props
   ::db/pool
   [::providers [:map-of :keyword [:maybe schema:provider]]]])

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (sm/check schema:routes-params params)))

(defmethod ig/init-key ::routes
  [_ cfg]
  (let [cfg (update cfg :providers d/without-nils)]
    ["" {:middleware [[session/authz cfg]
                      [provider-lookup cfg]]}
     ["/auth/oauth"
      ["/:provider"
       {:handler auth-handler
        :allowed-methods #{:post}}]
      ["/:provider/callback"
       {:handler callback-handler
        :allowed-methods #{:get}}]]]))

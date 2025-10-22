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
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
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
  [cfg {:keys [base-uri] :as provider}]
  (let [uri (u/join base-uri ".well-known/openid-configuration")
        rsp (http/req! cfg {:method :get :uri (dm/str uri)})]

    (if (= 200 (:status rsp))
      (let [data      (-> rsp :body json/decode)
            token-uri (get data :token_endpoint)
            auth-uri  (get data :authorization_endpoint)
            user-uri  (get data :userinfo_endpoint)
            jwks-uri  (get data :jwks_uri)]

        (-> provider
            (assoc :token-uri token-uri)
            (assoc :auth-uri  auth-uri)
            (assoc :user-uri  user-uri)
            (assoc :jwks-uri jwks-uri)))

      (ex/raise :type ::internal
                :code :invalid-sso-config
                :hint "unable to discover OIDC configuration"
                :discover-uri uri
                :response-status-code (:status rsp)))))

(defn- get-oidc-config
  "Get the OIDC config params from global config"
  []
  (d/without-nils
   {:base-uri         (cf/get :oidc-base-uri)
    :client-id        (cf/get :oidc-client-id)
    :client-secret    (cf/get :oidc-client-secret)
    :token-uri        (cf/get :oidc-token-uri)
    :auth-uri         (cf/get :oidc-auth-uri)
    :user-uri         (cf/get :oidc-user-uri)
    :jwks-uri         (cf/get :oidc-jwks-uri)
    :scopes           (cf/get :oidc-scopes #{"openid" "profile" "email"})
    :roles            (cf/get :oidc-roles)
    :user-info-source (cf/get :oidc-user-info-source)
    :roles-attr       (cf/get :oidc-roles-attr)
    :email-attr       (cf/get :oidc-email-attr "email")
    :name-attr        (cf/get :oidc-name-attr "name")
    :name             "oidc"
    :id               "oidc"}))

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
  [cfg jwks-uri]
  (let [{:keys [status body]} (http/req! cfg {:method :get :uri jwks-uri})]
    (if (= 200 status)
      (-> body json/decode :keys process-oidc-jwks)
      (ex/raise :type ::internal
                :code :unable-to-fetch-sso-jwks
                :hint "unable to retrieve JWKs (unexpected response status code)"
                :response-status-code status))))

(defn- populate-jwks
  "Fetch and Add (if possible) JWK's to the OIDC provider"
  [cfg provider]
  (try
    (if-let [jwks (some->> (:jwks-uri provider) (fetch-oidc-jwks cfg))]
      (assoc provider :jwks jwks)
      provider)
    (catch Throwable cause
      (l/warn :hint "unable to fetch JWKs for the OIDC provider"
              :provider (str (:id provider))
              :cause cause)
      provider)))

(defn- prepare-oidc-provider
  [cfg params]
  (when-not (and (string? (:base-uri params))
                 (string? (:client-id params))
                 (string? (:client-secret params)))
    (ex/raise :type ::internal
              :code :invalid-sso-config
              :hint "missing params for provider initialization"
              :provider (:id params)))

  (try
    (if (and (string? (:token-uri params))
             (string? (:user-uri params))
             (string? (:auth-uri params)))
      (populate-jwks cfg params)
      (let [provider (->> params
                          (discover-oidc-config cfg)
                          (populate-jwks cfg))]
        (with-meta provider {::discovered true})))

    (catch Throwable cause
      (ex/raise :type ::internal
                :type :invalid-sso-config
                :hint "unexpected exception on configuring provider"
                :provider (:id params)
                :cause cause))))

(defmethod ig/assert-key ::providers/generic
  [_ params]
  (assert (http/client? (::http/client params)) "expected a valid http client"))

(defmethod ig/init-key ::providers/generic
  [_ cfg]
  (when (contains? cf/flags :login-with-oidc)
    (try
      (let [provider (->> (get-oidc-config)
                          (prepare-oidc-provider cfg))]
        (l/inf :hint "provider initialized"
               :provider (:id provider)
               :client-id (:client-id provider)
               :client-secret (obfuscate-string (:client-secret provider)))
        provider)

      (catch Throwable cause
        (l/warn :hint "unable to initialize auth provider"
                :provider "oidc"
                :cause cause)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GOOGLE AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-google-config
  []
  (d/without-nils
   {:client-id        (cf/get :google-client-id)
    :client-secret    (cf/get :google-client-secret)
    :scopes           #{"openid" "email" "profile"}
    :auth-uri         "https://accounts.google.com/o/oauth2/v2/auth"
    :token-uri        "https://oauth2.googleapis.com/token"
    :user-uri         "https://openidconnect.googleapis.com/v1/userinfo"
    :user-info-source "userinfo"
    :name             "google"
    :id               "google"}))

(defn- prepare-google-provider
  [params]
  (when-not (and (string? (:client-id params))
                 (string? (:client-secret params)))
    (ex/raise :type ::internal
              :code :invalid-sso-config
              :hint "missing params for provider initialization"
              :provider (:id params)))

  params)

(defmethod ig/init-key ::providers/google
  [_ _]
  (when (contains? cf/flags :login-with-google)
    (try
      (let [provider (->> (get-google-config)
                          (prepare-google-provider))]
        (l/inf :hint "provider initialized"
               :provider (:id provider)
               :client-id (:client-id provider)
               :client-secret (obfuscate-string (:client-secret provider)))
        provider)

      (catch Throwable cause
        (l/warn :hint "unable to initialize auth provider"
                :provider "google"
                :cause cause)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITHUB AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- int-in-range?
  [val start end]
  (and (<= start val) (< val end)))

(defn- lookup-github-email
  [cfg tdata props]
  (or (some-> props :github/email)
      (let [params {:uri "https://api.github.com/user/emails"
                    :headers {"Authorization" (dm/str (:token/type tdata) " " (:token/access tdata))}
                    :timeout 6000
                    :method :get}

            {:keys [status body]} (http/req! cfg params)]

        (when-not (int-in-range? status 200 300)
          (ex/raise :type :internal
                    :code :unable-to-retrieve-github-emails
                    :hint "unable to retrieve github emails"
                    :request-uri (:uri params)
                    :response-status status
                    :response-body body))

        (->> body json/decode (filter :primary) first :email))))

(defn- get-github-config
  [cfg]
  (d/without-nils
   {:client-id        (cf/get :github-client-id)
    :client-secret    (cf/get :github-client-secret)
    :scopes           #{"read:user" "user:email"}
    :auth-uri         "https://github.com/login/oauth/authorize"
    :token-uri        "https://github.com/login/oauth/access_token"
    :user-uri         "https://api.github.com/user"
    :name             "github"
    :id               "github"
    :user-info-source "userinfo"

    ;; Additional hooks for provider specific way of
    ;; retrieve emails.
    ::get-email-fn  (partial lookup-github-email cfg)}))

(defn- prepare-github-provider
  [params]
  (when-not (and (string? (:client-id params))
                 (string? (:client-secret params)))
    (ex/raise :type ::internal
              :code :invalid-sso-config
              :hint "several required params for configuring GITHUB SSO are missing"
              :provider (:id params)))

  params)

(defmethod ig/assert-key ::providers/github
  [_ params]
  (assert (http/client? (::http/client params)) "expected a valid http client"))

(defmethod ig/init-key ::providers/github
  [_ cfg]
  (when (contains? cf/flags :login-with-github)
    (try
      (let [provider (->> (get-github-config cfg)
                          (prepare-github-provider))]
        (l/inf :hint "provider initialized"
               :provider (:id provider)
               :client-id (:client-id provider)
               :client-secret (obfuscate-string (:client-secret provider)))
        provider)

      (catch Throwable cause
        (l/warn :hint "unable to initialize auth provider"
                :provider "github"
                :cause cause)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITLAB AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-gitlab-config
  []
  (let [base (cf/get :gitlab-base-uri "https://gitlab.com")
        opts {:base-uri      base
              :client-id     (cf/get :gitlab-client-id)
              :client-secret (cf/get :gitlab-client-secret)
              :scopes        #{"openid" "profile" "email"}
              :auth-uri      (str base "/oauth/authorize")
              :token-uri     (str base "/oauth/token")
              :user-uri      (str base "/oauth/userinfo")
              :jwks-uri      (str base "/oauth/discovery/keys")
              :name          "gitlab"
              :id            "gitlab"}]
    (d/without-nils opts)))

(defn- prepare-gitlab-provider
  [cfg params]
  (when-not (and (string? (:client-id params))
                 (string? (:client-secret params)))
    (ex/raise :type ::internal
              :code :invalid-sso-config
              :hint "missing params for provider initialization"
              :provider (:id params)))

  (try
    (let [provider (populate-jwks cfg params)]
      (l/inf :hint "provider initialized"
             :provider "gitlab"
             :base-uri (:base-uri provider)
             :client-id (:client-id provider)
             :client-secret (obfuscate-string (:client-secret provider)))
      provider)
    (catch Throwable cause
      (ex/raise :type ::internal
                :type :invalid-sso-config
                :hint "unexpected exception on configuring provider"
                :provider (:id params)
                :cause cause))))

(defmethod ig/init-key ::providers/gitlab
  [_ cfg]

  (when (contains? cf/flags :login-with-gitlab)
    (try
      (let [provider (->> (get-gitlab-config)
                          (prepare-gitlab-provider cfg))]

        (l/inf :hint "provider initialized"
               :provider (:id provider)
               :client-id (:client-id provider)
               :client-secret (obfuscate-string (:client-secret provider)))
        provider)

      (catch Throwable cause
        (l/warn :hint "unable to initialize auth provider"
                :provider "gitlab"
                :cause cause)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROVIDERS COLLECTOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:provider
  [:map {:title "Provider"}
   [:name ::sm/text]
   [:client-id ::sm/text]
   [:client-secret ::sm/text]

   [:id [:or :string ::sm/uuid]]
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

(def ^:private schema:providers
  [:map-of :string schema:provider])

(defmethod ig/assert-key ::providers
  [_ providers]
  (let [check-provider (sm/check-fn schema:provider)]
    (assert (every? check-provider (filter identity providers)))))

(defmethod ig/init-key ::providers
  [_ providers]
  (->> providers
       (filter identity)
       (d/index-by :id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-attr-path
  [provider path]
  (let [[fitem & items] (str/split path "__")]
    (into [(keyword (:name provider) fitem)] (map keyword) items)))

(defn- build-redirect-uri
  []
  (let [public (u/uri (cf/get :public-uri))]
    (str (assoc public :path (str "/api/auth/oidc/callback")))))

(defn- build-auth-redirect-uri
  [provider token]
  (let [params {:client_id (:client-id provider)
                :redirect_uri (build-redirect-uri)
                :response_type "code"
                :state token
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
  [cfg provider code]
  (let [params {:client_id (:client-id provider)
                :client_secret (:client-secret provider)
                :code code
                :grant_type "authorization_code"
                :redirect_uri (build-redirect-uri)}
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

    (let [{:keys [status body]} (http/req! cfg req)]
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
            (if-let [get-email-fn (::get-email-fn provider)]
              (get-email-fn tdata props)
              (let [attr-kw (get provider :email-attr "email")
                    attr-ph (parse-attr-path provider attr-kw)]
                (get-in props attr-ph))))

          (get-name [props]
            (or (let [attr-kw (get provider :name-attr "name")
                      attr-ph (parse-attr-path provider attr-kw)]
                  (get-in props attr-ph))
                (let [attr-ph (parse-attr-path provider "nickname")]
                  (get-in props attr-ph))))]

    (let [info  (assoc info :provider (str (:id provider)))
          props (qualify-props provider info)
          email (get-email props)]
      {:backend  (:name provider)
       :fullname (or (get-name props) email)
       :email email
       :email-verified (get info :email_verified false)
       :props props})))

(defn- fetch-user-info
  [cfg provider tdata]
  (l/trc :hint "fetch user info"
         :uri (:user-uri provider)
         :token (obfuscate-string (:token/access tdata)))

  (let [params   {:uri (:user-uri provider)
                  :headers {"Authorization" (str (:token/type tdata) " " (:token/access tdata))}
                  :timeout 6000
                  :method :get}
        response (http/req! cfg params)]

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

(defn- get-user-info-from-token
  [provider tdata]
  (try
    (when (:token/id tdata)
      (let [{:keys [kid alg]} (jwt/decode-header (:token/id tdata))]
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
   [:email-verified :boolean]
   [:props [:map-of :keyword ::sm/any]]])

(def ^:private valid-info?
  (sm/validator schema:info))

(defn- get-info
  [cfg provider claims params]
  (let [code  (get params :code)
        tdata (fetch-access-token cfg provider code)
        info  (case (get provider :user-info-source)
                :token (get-user-info-from-token provider tdata)
                :userinfo (fetch-user-info cfg provider tdata)
                (or (get-user-info-from-token provider tdata)
                    (fetch-user-info cfg provider tdata)))

        info  (process-user-info provider tdata info)]

    (if (valid-info? info)
      (l/trc :hint "received valid user info object" :info info)
      (do
        (l/warn :hint "received incomplete user info object (please set correct scopes)" :info info)
        (ex/raise :type :internal
                  :code :incomplete-user-info
                  :hint "inconmplete user info"
                  :info info)))

    ;; If the provider is OIDC, we can proceed to check
    ;; roles if they are defined.
    (when (and (= "oidc" (:name provider))
               (seq (:roles provider)))

      (let [expected-roles (into #{} (:roles provider))
            current-roles  (let [roles-kw (get provider :roles-attr "roles")
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
      (some? (:invitation-token claims))
      (assoc :invitation-token (:invitation-token claims))

      (some? (:external-session-id claims))
      (assoc :external-session-id (:external-session-id claims))

      ;; If state token comes with props, merge them. The state token
      ;; props can contain pm_ and utm_ prefixed query params.
      (map? (:props claims))
      (update :props merge (:props claims)))))

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
  [cfg info provider]
  (let [info   (assoc info
                      :iss :prepared-register
                      :exp (ct/in-future {:hours 48}))

        params {:token (tokens/generate cfg info)
                :provider (:provider (:id provider))
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
  [provider {:keys [props] :as info}]
  (let [prop (qualify-prop-key provider :email_verified)]
    (true? (get props prop))))

(defn- profile-has-provider-props?
  [provider profile]
  (let [prop (qualify-prop-key provider :email)]
    (contains? (:props profile) prop)))

(defn- get-external-session-id
  [request]
  (let [session-id (yreq/get-header request "x-external-session-id")]
    (when (string? session-id)
      (if (or (> (count session-id) 256)
              (= session-id "null")
              (str/blank? session-id))
        nil
        session-id))))

(defn- decode-row
  [{:keys [roles scopes user-info-source] :as row}]
  (cond-> row
    (db/pgarray? scopes)
    (assoc :scopes (db/decode-pgarray scopes #{}))

    (db/pgarray? roles)
    (assoc :roles (db/decode-pgarray roles #{}))

    (string? user-info-source)
    (assoc :user-info-source (keyword user-info-source))))

(defn- get-custom-sso-provider
  [cfg id]
  (try
    (when-let [params (some->> (db/get* cfg :sso-config {:id id :is-enabled true})
                               (decode-row))]
      (case (:name params)
        "oidc" (prepare-oidc-provider cfg params)))
    (catch Throwable cause
      (l/wrn :hint "unable to get custom SSO provider"
             :provider (str id)
             :cause cause))))

(defn- resolve-provider
  [{:keys [::providers] :as cfg} params]
  (let [provider (get params :provider)
        provider (if (uuid? provider)
                   provider
                   (or (uuid/parse* provider) provider))]

    (cond
      (uuid? provider)
      (or (get-custom-sso-provider cfg provider)
          (ex/raise :type :restriction
                    :code :sso-provider-not-configured
                    :hint "provider not configured"
                    :provider provider))

      (string? provider)
      (or (get providers provider)
          (ex/raise :type :restriction
                    :code :sso-provider-not-configured
                    :hint "provider not configured"
                    :provider provider))

      :else
      (throw (IllegalArgumentException. "invalid data for provider")))))

(defn- auth-handler
  [cfg {:keys [params] :as request}]
  (let [provider (resolve-provider cfg params)
        props    (audit/extract-utm-params params)
        esid     (rpc/get-external-session-id request)
        params   {:iss :oidc
                  :provider (:id provider)
                  :invitation-token (:invitation-token params)
                  :external-session-id esid
                  :props props
                  :exp (ct/in-future "4h")}
        token  (tokens/generate cfg (d/without-nils params))
        uri    (build-auth-redirect-uri provider token)]

    {::yres/status 200
     ::yres/body {:redirect-uri uri}}))

(defn- get-and-decode-claims
  [cfg {:keys [params] :as request}]
  (let [token (get params :state)]
    (tokens/verify cfg {:token token :iss :oidc})))

(defn- callback-handler
  [cfg {:keys [params] :as request}]
  (if-let [error (get params :error)]
    (redirect-with-error "unable-to-auth" error)
    (try
      (let [claims   (get-and-decode-claims cfg request)
            provider (resolve-provider cfg claims)
            info     (get-info cfg provider claims params)
            profile  (get-profile cfg info)]

        (cond
          (not profile)
          (cond
            (and (email.blacklist/enabled? cfg)
                 (email.blacklist/contains? cfg (:email info)))
            (redirect-with-error "email-domain-not-allowed")

            (and (email.whitelist/enabled? cfg)
                 (not (email.whitelist/contains? cfg (:email info))))
            (redirect-with-error "email-domain-not-allowed")

            :else
            (if (or (contains? cf/flags :registration)
                    (contains? cf/flags :oidc-registration))
              (redirect-to-register cfg info provider)
              (redirect-with-error "registration-disabled")))

          (:is-blocked profile)
          (redirect-with-error "profile-blocked")

          (not (or (= (:auth-backend profile) (:name provider))
                   (profile-has-provider-props? provider profile)
                   (provider-has-email-verified? provider info)))
          (redirect-with-error "auth-provider-not-allowed")

          (not (:is-active profile))
          (let [info (assoc info :profile-id (:id profile))]
            (redirect-to-register cfg info provider))

          :else
          (let [sxf     (session/create-fn cfg (:id profile))
                token   (or (:invitation-token info)
                            (tokens/generate cfg
                                             {:iss :auth
                                              :exp (ct/in-future "15m")
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
                 (sxf request)))))

      (catch Throwable cause
        (binding [l/*context* (errors/request->context request)]
          (l/err :hint "error on process oidc callback" :cause cause)
          (redirect-with-error "unable-to-auth" (ex-message cause)))))))

(def ^:private schema:routes-params
  [:map
   ::session/manager
   ::http/client
   ::setup/props
   ::db/pool
   [::providers schema:providers]])

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (sm/check schema:routes-params params)))

(defmethod ig/init-key ::routes
  [_ cfg]
  (let [cfg (update cfg ::providers d/without-nils)]
    ["/auth/oidc" {:middleware [[session/authz cfg]]}
     [""
      {:handler (partial auth-handler cfg)
       :allowed-methods #{:post}}]
     ["/callback"
      {:handler (partial callback-handler cfg)
       :allowed-methods #{:get}}]]))

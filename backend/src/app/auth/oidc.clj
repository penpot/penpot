;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth.oidc
  "OIDC client implementation."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.http.middleware :as hmw]
   [app.loggers.audit :as audit]
   [app.rpc.queries.profile :as profile]
   [app.tokens :as tokens]
   [app.util.json :as json]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.response :as yrs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- obfuscate-string
  [s]
  (if (< (count s) 10)
    (apply str (take (count s) (repeat "*")))
    (str (subs s 0 5)
         (apply str (take (- (count s) 5) (repeat "*"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OIDC PROVIDER (GENERIC)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- discover-oidc-config
  [{:keys [http-client]} {:keys [base-uri] :as opts}]
  (let [discovery-uri (u/join base-uri ".well-known/openid-configuration")
        response      (ex/try (http/req! http-client {:method :get :uri (str discovery-uri)} {:sync? true}))]
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

(defn- prepare-oidc-opts
  [cfg]
  (let [opts {:base-uri      (:base-uri cfg)
              :client-id     (:client-id cfg)
              :client-secret (:client-secret cfg)
              :token-uri     (:token-uri cfg)
              :auth-uri      (:auth-uri cfg)
              :user-uri      (:user-uri cfg)
              :scopes        (:scopes cfg #{"openid" "profile" "email"})
              :roles-attr    (:roles-attr cfg)
              :roles         (:roles cfg)
              :name          "oidc"}

        opts (d/without-nils opts)]

    (when (and (string? (:base-uri opts))
               (string? (:client-id opts))
               (string? (:client-secret opts)))
      (if (and (string? (:token-uri opts))
               (string? (:user-uri opts))
               (string? (:auth-uri opts)))
        opts
        (some-> (discover-oidc-config cfg opts)
                (merge opts {:discover? true}))))))

(defmethod ig/prep-key ::generic-provider
  [_ cfg]
  (d/without-nils cfg))

(defmethod ig/init-key ::generic-provider
  [_ cfg]
  (when (:enabled? cfg)
    (if-let [opts (prepare-oidc-opts cfg)]
      (do
        (l/info :hint "provider initialized"
                :provider :oidc
                :method (if (:discover? opts) "discover" "manual")
                :client-id (:client-id opts)
                :client-secret (obfuscate-string (:client-secret opts))
                :scopes (str/join "," (:scopes opts))
                :auth-uri (:auth-uri opts)
                :user-uri (:user-uri opts)
                :token-uri (:token-uri opts)
                :roles-attr (:roles-attr opts)
                :roles      (:roles opts))
        opts)
      (do
        (l/warn :hint "unable to initialize auth provider, missing configuration" :provider :oidc)
        nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GOOGLE AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/prep-key ::google-provider
  [_ cfg]
  (d/without-nils cfg))

(defmethod ig/init-key ::google-provider
  [_ cfg]
  (let [opts {:client-id     (:client-id cfg)
              :client-secret (:client-secret cfg)
              :scopes        #{"openid" "email" "profile"}
              :auth-uri      "https://accounts.google.com/o/oauth2/v2/auth"
              :token-uri     "https://oauth2.googleapis.com/token"
              :user-uri      "https://openidconnect.googleapis.com/v1/userinfo"
              :name          "google"}]

    (when (:enabled? cfg)
      (if (and (string? (:client-id opts))
               (string? (:client-secret opts)))
        (do
          (l/info :hint "provider initialized"
                  :provider :google
                  :client-id (:client-id opts)
                  :client-secret (obfuscate-string (:client-secret opts)))
          opts)

        (do
          (l/warn :hint "unable to initialize auth provider, missing configuration" :provider :google)
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITHUB AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- retrieve-github-email
  [{:keys [http-client]} tdata info]
  (or (some-> info :email p/resolved)
      (-> (http/req! http-client {:uri "https://api.github.com/user/emails"
                                  :headers {"Authorization" (dm/str (:type tdata) " " (:token tdata))}
                                  :timeout 6000
                                  :method :get})
          (p/then (fn [{:keys [status body] :as response}]
                    (when-not (s/int-in-range? 200 300 status)
                      (ex/raise :type :internal
                                :code :unable-to-retrieve-github-emails
                                :hint "unable to retrieve github emails"
                                :http-status status
                                :http-body body))
                    (->> response :body json/read (filter :primary) first :email))))))

(defmethod ig/prep-key ::github-provider
  [_ cfg]
  (d/without-nils cfg))

(defmethod ig/init-key ::github-provider
  [_ cfg]
  (let [opts {:client-id     (:client-id cfg)
              :client-secret (:client-secret cfg)
              :scopes        #{"read:user" "user:email"}
              :auth-uri      "https://github.com/login/oauth/authorize"
              :token-uri     "https://github.com/login/oauth/access_token"
              :user-uri      "https://api.github.com/user"
              :name          "github"

              ;; Additional hooks for provider specific way of
              ;; retrieve emails.
              :get-email-fn           (partial retrieve-github-email cfg)}]

    (when (:enabled? cfg)
      (if (and (string? (:client-id opts))
               (string? (:client-secret opts)))
        (do
          (l/info :hint "provider initialized"
                  :provider :github
                  :client-id (:client-id opts)
                  :client-secret (obfuscate-string (:client-secret opts)))
          opts)

        (do
          (l/warn :hint "unable to initialize auth provider, missing configuration" :provider :github)
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITLAB AUTH PROVIDER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/prep-key ::gitlab-provider
  [_ cfg]
  (d/without-nils cfg))

(defmethod ig/init-key ::gitlab-provider
  [_ cfg]
  (let [base (:base-uri cfg "https://gitlab.com")
        opts {:base-uri      base
              :client-id     (:client-id cfg)
              :client-secret (:client-secret cfg)
              :scopes        #{"openid" "profile" "email"}
              :auth-uri      (str base "/oauth/authorize")
              :token-uri     (str base "/oauth/token")
              :user-uri      (str base "/oauth/userinfo")
              :name          "gitlab"}]
    (when (:enabled? cfg)
      (if (and (string? (:client-id opts))
               (string? (:client-secret opts)))
        (do
          (l/info :hint "provider initialized"
                  :provider :gitlab
                  :base-uri base
                  :client-id (:client-id opts)
                  :client-secret (obfuscate-string (:client-secret opts)))
          opts)

        (do
          (l/warn :hint "unable to initialize auth provider, missing configuration" :provider :gitlab)
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
     (http/req! http-client req)
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
            (http/req! http-client {:uri (:user-uri provider)
                                    :headers {"Authorization" (str (:type tdata) " " (:token tdata))}
                                    :timeout 6000
                                    :method :get}))
          (validate-response [response]
            (when-not (s/int-in-range? 200 300 (:status response))
              (ex/raise :type :internal
                        :code :unable-to-retrieve-user-info
                        :hint "unable to retrieve user info"
                        :http-status (:status response)
                        :http-body (:body response)))
            response)

          (get-email [info]
            ;; Allow providers hook into this for custom email
            ;; retrieval method.
            (if-let [get-email-fn (:get-email-fn provider)]
              (get-email-fn tdata info)
              (let [attr-kw (cf/get :oidc-email-attr :email)]
                (get info attr-kw))))

          (get-name [info]
            (let [attr-kw (cf/get :oidc-name-attr :name)]
              (get info attr-kw)))

          (process-response [response]
            (p/let [info  (-> response :body json/read)
                    email (get-email info)]
              {:backend  (:name provider)
               :email    email
               :fullname (or (get-name info) email)
               :props    (->> (dissoc info :name :email)
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
        (p/then validate-response)
        (p/then process-response)
        (p/then validate-info))))

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
  [{:keys [sprops provider] :as cfg} {:keys [params] :as request}]
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
          state  (tokens/verify sprops {:token state :iss :oauth})]
      (-> (p/resolved code)
          (p/then #(retrieve-access-token cfg %))
          (p/then #(retrieve-user-info cfg %))
          (p/then' validate-oidc)
          (p/then' (partial post-process state))))))

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
  [{:keys [sprops session audit] :as cfg} request info profile]
  (if profile
    (let [sxf    ((:create session) (:id profile))
          token  (or (:invitation-token info)
                     (tokens/generate sprops {:iss :auth
                                              :exp (dt/in-future "15m")
                                              :profile-id (:id profile)}))
          params {:token token}

          uri    (-> (u/uri (:public-uri cfg))
                     (assoc :path "/#/auth/verify-token")
                     (assoc :query (u/map->query-string params)))]

      (when (:is-blocked profile)
        (ex/raise :type :restriction
                  :code :profile-blocked))

      (when (fn? audit)
        (audit :cmd :submit
               :type "command"
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
          token  (tokens/generate sprops info)
          params (d/without-nils
                  {:token token
                   :fullname (:fullname info)})
          uri    (-> (u/uri (:public-uri cfg))
                     (assoc :path "/#/auth/register/validate")
                     (assoc :query (u/map->query-string params)))]
      (redirect-response uri))))

(defn- auth-handler
  [{:keys [sprops] :as cfg} {:keys [params] :as request}]
  (let [props (audit/extract-utm-params params)
        state (tokens/generate sprops
                               {:iss :oauth
                                :invitation-token (:invitation-token params)
                                :props props
                                :exp (dt/in-future "15m")})
        uri   (build-auth-uri cfg state)]
    (yrs/response 200 {:redirect-uri uri})))

(defn- callback-handler
  [cfg request]
  (letfn [(process-request []
            (p/let [info    (retrieve-info cfg request)
                    profile (retrieve-profile cfg info)]
              (generate-redirect cfg request info profile)))

          (handle-error [cause]
            (l/error :hint "error on oauth process" :cause cause)
            (generate-error-redirect cfg cause))]

    (-> (process-request)
        (p/catch handle-error))))

(def provider-lookup
  {:compile
   (fn [& _]
     (fn [handler]
       (fn [{:keys [providers] :as cfg} request]
         (let [provider (some-> request :path-params :provider keyword)]
           (if-let [provider (get providers provider)]
             (handler (assoc cfg :provider provider) request)
             (ex/raise :type :restriction
                       :code :provider-not-configured
                       :provider provider
                       :hint "provider not configured"))))))})

(s/def ::public-uri ::us/not-empty-string)
(s/def ::http-client ::http/client)
(s/def ::session map?)
(s/def ::sprops map?)
(s/def ::providers map?)

(defmethod ig/pre-init-spec ::routes
  [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::sprops
                   ::http-client
                   ::providers
                   ::db/pool
                   ::wrk/executor]))

(defmethod ig/init-key ::routes
  [_ {:keys [executor session] :as cfg}]
  (let [cfg (update cfg :provider d/without-nils)]
    ["" {:middleware [[(:middleware session)]
                      [hmw/with-dispatch executor]
                      [hmw/with-config cfg]
                      [provider-lookup]
                      ]}
     ;; We maintain the both URI prefixes for backward compatibility.

     ["/auth/oauth"
      ["/:provider"
       {:handler auth-handler
        :allowed-methods #{:post}}]
      ["/:provider/callback"
       {:handler callback-handler
        :allowed-methods #{:get}}]]

     ["/auth/oidc"
      ["/:provider"
       {:handler auth-handler
        :allowed-methods #{:post}}]
      ["/:provider/callback"
       {:handler callback-handler
        :allowed-methods #{:get}}]]]))

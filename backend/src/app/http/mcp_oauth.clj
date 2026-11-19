;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.mcp-oauth
  "MCP-specific OAuth authorization server.

  The authorize endpoint stages a request; the existing Penpot session identifies
  the user on the approval page. Approval creates a one-use code, and token
  exchange returns the existing fixed MCP user token after verifying PKCE.
  Client registration, login sessions, and authorization codes are distinct."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.errors :as errors]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.mcp.oauth-clients :as clients]
   [app.mcp.oauth-state :as state]
   [app.rpc.commands.access-token :as access-token]
   [app.util.json :as json]
   [clojure.string :as str]
   [integrant.core :as ig]
   [yetti.request :as yreq]
   [yetti.response :as yres])
  (:import
   java.io.InputStream
   java.net.URI
   java.net.URLDecoder
   java.net.URLEncoder
   java.nio.charset.StandardCharsets
   java.util.Base64))

(defn issuer [] (str (cf/get-public-uri "api/mcp/oauth")))
(defn resource [] (str (cf/get-public-uri "mcp/stream")))

(defn authorization-metadata
  "OAuth capabilities and endpoints advertised to MCP clients."
  []
  {:issuer (issuer)
   :authorization_endpoint (str (issuer) "/authorize")
   :token_endpoint (str (issuer) "/token")
   :registration_endpoint (str (issuer) "/register")
   :response_types_supported ["code"]
   :grant_types_supported ["authorization_code"]
   :code_challenge_methods_supported ["S256"]
   :token_endpoint_auth_methods_supported ["none" "client_secret_basic" "client_secret_post"]
   :scopes_supported ["mcp"]
   :authorization_response_iss_parameter_supported true
   :client_id_metadata_document_supported true})

(defn resource-metadata
  "Protected-resource metadata referenced by the MCP server's 401 challenge."
  []
  {:resource (resource)
   :authorization_servers [(issuer)]
   :scopes_supported ["mcp"]
   :bearer_methods_supported ["header"]
   :resource_name "Penpot MCP"})

(defn- response
  ([body] (response 200 body))
  ([status body]
   {::yres/status status
    ::yres/headers {"content-type" "application/json"
                    "cache-control" "no-store"
                    "pragma" "no-cache"
                    "referrer-policy" "no-referrer"}
    ::yres/body (json/encode-str body)}))

(defn- redirect
  [url]
  {::yres/status 303
   ::yres/headers {"location" url "cache-control" "no-store"
                   "referrer-policy" "no-referrer"}
   ::yres/body ""})

(defn- encode [^String value] (URLEncoder/encode value StandardCharsets/UTF_8))
(defn- decode
  [^String value]
  (try (URLDecoder/decode value StandardCharsets/UTF_8)
       (catch IllegalArgumentException _ (clients/invalid! "invalid_request"))))

(defn- parse-form
  "Bounded OAuth form/query parser preserving protocol parameter names.
  Rejects duplicate keys so validation never depends on which value wins."
  [query]
  (when (> (count query) 16384) (clients/invalid! "invalid_request"))
  (reduce (fn [result part]
            (let [[key value] (str/split part #"=" 2)
                  key         (keyword (decode key))]
              (when (contains? result key) (clients/invalid! "invalid_request"))
              (assoc result key (decode (or value "")))))
          {}
          (remove str/blank? (str/split (or query "") #"&"))))

(defn- read-body
  [request content-type]
  (when-not (str/starts-with? (or (yreq/get-header request "content-type") "") content-type)
    (clients/invalid! "invalid_request"))
  (with-open [^InputStream body (yreq/body request)]
    (when-not body (clients/invalid! "invalid_request"))
    (let [bytes (.readNBytes body 16385)]
      (when (> (alength bytes) 16384) (clients/invalid! "invalid_request"))
      (String. bytes StandardCharsets/UTF_8))))

(defn- append-query
  [url params]
  (str url (if (str/includes? url "?") "&" "?")
       (str/join "&" (for [[key value] params :when (some? value)]
                       (str (encode (name key)) "=" (encode value))))))

(defn- limit!
  [cfg request kind limit]
  (when-not (state/allow-request! cfg kind (str (yreq/remote-addr request)) limit)
    (clients/invalid! "temporarily_unavailable" 429)))

(defn- authorize
  "Validate and stage an authorization request before user login or consent.
  The browser receives only an opaque handle to the ten-minute Redis record."
  [cfg request]
  (limit! cfg request :authorize-rate 60)
  (let [params   (parse-form (yreq/query request))
        client   (clients/resolve-client cfg (:client_id params))
        callback (:redirect_uri params)]
    ;; Never redirect until the client and its callback have been checked.
    (when-not (clients/redirect-matches? client callback)
      (clients/invalid! "invalid_request"))
    (when-not (and (= "code" (:response_type params))
                   (= "S256" (:code_challenge_method params))
                   (string? (:code_challenge params))
                   (re-matches #"[A-Za-z0-9_-]{43}" (:code_challenge params))
                   (= "mcp" (get params :scope "mcp"))
                   (= (resource) (get params :resource (resource)))
                   (<= (count (:state params)) 2048))
      (clients/invalid! "invalid_request"))
    (let [handle (state/create! cfg :request
                                {:client-id (:client_id client)
                                 :client-name (:client_name client)
                                 :logo-uri (:logo_uri client)
                                 :redirect-uri callback
                                 :challenge (:code_challenge params)
                                 :state (:state params)
                                 :resource (resource)
                                 :scope "mcp"}
                                600)]
      (redirect (append-query (str (cf/get-public-uri))
                              {:screen "mcp-authorize" :request handle})))))

(defn- session-binding
  "Profile and login-session identity used to bind one approval request."
  [request]
  (let [session (session/get-session request)]
    (when-not (:profile-id session) (clients/invalid! "login_required" 401))
    {:profile-id (str (:profile-id session))
     :session-id (state/digest (str (:id session)))}))

(defn- approval-request
  "Bind a pending request to its first authenticated session and return UI data.
  Compare-and-replace prevents another session from taking over that request."
  [cfg request]
  (let [binding (session-binding request)
        handle  (:request (parse-form (yreq/query request)))
        record  (state/read! cfg :request handle)]
    (when-not record (clients/invalid! "invalid_request"))
    (let [value (:value record)]
      (if (:session-id value)
        (when-not (= binding (select-keys value [:profile-id :session-id]))
          (clients/invalid! "access_denied" 403))
        (state/replace! cfg :request handle record
                        (merge value binding {:csrf (state/random-secret)}))))
    ;; Another GET may have won the binding race. Read the winning record so
    ;; same-session retries share its CSRF value; other sessions cannot proceed.
    (let [value (:value (state/read! cfg :request handle))]
      (when-not (= binding (select-keys value [:profile-id :session-id]))
        (clients/invalid! "invalid_request"))
      (response (select-keys value [:client-id :client-name :logo-uri :csrf :scope])))))

(defn- same-origin?
  [request]
  (let [public (URI. (str (cf/get-public-uri)))
        origin (str (.getScheme public) "://" (.getRawAuthority public))]
    (= origin (yreq/get-header request "origin"))))

(defn- decision
  "Accept one session-bound approval/denial and return the client callback.
  Origin and CSRF checks protect the cookie-authenticated decision endpoint."
  [cfg request]
  (when-not (same-origin? request) (clients/invalid! "access_denied" 403))
  (let [binding (session-binding request)
        params  (parse-form (read-body request "application/x-www-form-urlencoded"))
        handle  (:request params)
        record  (state/read! cfg :request handle)
        value   (:value record)]
    (when-not (and record
                   (= binding (select-keys value [:profile-id :session-id]))
                   (state/secret= (:csrf value) (:csrf params))
                   (contains? #{"approve" "deny"} (:decision params)))
      (clients/invalid! "invalid_request"))
    ;; Consume before provisioning: concurrent clicks must not issue two codes.
    ;; A later failure requires a fresh authorization attempt.
    (when-not (state/consume! cfg :request handle record)
      (clients/invalid! "invalid_request"))
    (let [result (if (= "deny" (:decision params))
                   {:error "access_denied"}
                   (let [token (db/tx-run! cfg access-token/ensure-mcp-access!
                                           (uuid/parse (:profile-id value)))
                         ;; Retain the token row ID, never the fixed token itself.
                         code  (state/create! cfg :code
                                              (-> value
                                                  (dissoc :csrf :session-id :client-name)
                                                  (assoc :token-id (str (:id token))))
                                              120)]
                     {:code code}))]
      (response {:redirect-uri (append-query (:redirect-uri value)
                                             (merge result {:state (:state value)
                                                            :iss (issuer)}))}))))

(defn- client-authentication
  "Extract one OAuth client-authentication method, rejecting mixed credentials.
  Basic credentials are form-decoded after splitting the decoded header."
  [request params]
  (let [header (yreq/get-header request "authorization")]
    (if header
      (do
        (when (or (:client_secret params) (not (str/starts-with? header "Basic ")))
          (clients/invalid! "invalid_client" 401))
        (let [decoded     (try (String. (.decode (Base64/getDecoder) (subs header 6))
                                        StandardCharsets/UTF_8)
                               (catch IllegalArgumentException _
                                 (clients/invalid! "invalid_client" 401)))
              [id secret] (str/split decoded #":" 2)
              id          (decode id)]
          (when (or (nil? secret)
                    (and (:client_id params) (not= id (:client_id params))))
            (clients/invalid! "invalid_client" 401))
          {:id id :secret (decode secret) :method "client_secret_basic"}))
      {:id (:client_id params) :secret (:client_secret params)
       :method (if (:client_secret params) "client_secret_post" "none")})))

(defn- token
  "Exchange a code for the fixed MCP token after client and PKCE verification."
  [cfg request]
  (limit! cfg request :token-rate 120)
  (let [params (parse-form (read-body request "application/x-www-form-urlencoded"))
        auth   (client-authentication request params)
        record (state/read! cfg :code (:code params))
        value  (:value record)]
    (when-not (= "authorization_code" (:grant_type params))
      (clients/invalid! "unsupported_grant_type"))
    ;; Compare the exact client ID, not decoded metadata: a self-contained ID
    ;; edited after approval identifies a different client for this exchange.
    (when-not (and record
                   (= (:id auth) (:client-id value))
                   (= (:redirect_uri params) (:redirect-uri value))
                   (= (:resource value) (get params :resource (:resource value)))
                   (string? (:code_verifier params))
                   (re-matches #"[A-Za-z0-9._~-]{43,128}" (:code_verifier params))
                   (state/secret= (:challenge value) (state/digest (:code_verifier params))))
      (clients/invalid! "invalid_grant"))
    ;; Resolve again because an HTTPS metadata document may have changed
    ;; since approval; its current callback and authentication method apply.
    (let [client (clients/resolve-client cfg (:id auth))]
      (when-not (and (= (:method auth) (:token_endpoint_auth_method client))
                     (clients/redirect-matches? client (:redirect-uri value))
                     (or (= "none" (:method auth))
                         (and (string? (:secret auth))
                              (state/secret= (:secret-hash client)
                                             (state/digest (:secret auth))))))
        (clients/invalid! "invalid_client" 401)))
    ;; Validate before consuming so an invalid verifier/secret cannot burn the
    ;; code. Atomic consumption allows only one valid exchange to succeed.
    (when-not (state/consume! cfg :code (:code params) record)
      (clients/invalid! "invalid_grant"))
    ;; Regeneration deletes the old token row. Never substitute the profile's
    ;; latest token for the one this approval authorized, nor return it expired.
    (let [row (db/exec-one! (::db/pool cfg)
                            [(str "SELECT token FROM access_token"
                                  " WHERE id = ? AND profile_id = ? AND type = 'mcp'"
                                  " AND (expires_at IS NULL OR expires_at > now())")
                             (uuid/parse (:token-id value))
                             (uuid/parse (:profile-id value))])]
      (when-not row (clients/invalid! "invalid_grant"))
      (response {:access_token (:token row) :token_type "Bearer" :scope "mcp"}))))

(defn- register
  "Issue a self-contained client registration; does not grant user access."
  [cfg request]
  (limit! cfg request :registration-rate 10)
  (let [body     (read-body request "application/json")
        metadata (try (json/decode body)
                      (catch Exception _ (clients/invalid! "invalid_request")))]
    (response 201 (clients/register! cfg metadata))))

(defn- wrap-errors
  "Answer every failure with an OAuth error body. Unexpected failures also go
  through the shared HTTP error handler, only for its logging; these routes
  never set request params, so codes and credentials stay out of the log."
  [handler]
  (fn [request]
    (try (handler request)
         (catch Exception cause
           (let [{:keys [oauth-error status]} (ex-data cause)
                 status                       (or status (if (and oauth-error (not= oauth-error "temporarily_unavailable")) 400 503))]
             (when-not oauth-error
               (errors/handle cause request))
             (cond-> (response status {:error (or oauth-error "temporarily_unavailable")})
               (and (= status 401) (= oauth-error "invalid_client"))
               (assoc-in [::yres/headers "www-authenticate"] "Basic realm=\"Penpot MCP\"")))))))

(defn- wrap-public
  "CORS for discovery, registration and exchange without browser credentials.
  Cookie-authenticated approval endpoints must not use this wrapper."
  [handler]
  (fn [request]
    (let [response (if (= :options (yreq/method request))
                     {::yres/status 204}
                     (handler request))]
      (update response ::yres/headers merge
              {"access-control-allow-origin" "*"
               "access-control-allow-methods" "GET, POST, OPTIONS"
               "access-control-allow-headers" "Content-Type, Authorization"
               "access-control-expose-headers" "WWW-Authenticate"}))))

(defn- endpoint
  [cfg handler method public?]
  {:handler (partial handler cfg)
   :middleware (into (if public? [{:compile (constantly wrap-public)}] [])
                     [{:compile (constantly wrap-errors)} [mw/restrict-methods]])
   :allowed-methods (cond-> #{method} public? (conj :options))})

;; OAuth endpoints parse forms and emit snake_case JSON independently of RPC
;; middleware. Only the approval UI endpoints require a Penpot login session.
(defmethod ig/init-key ::routes
  [_ cfg]
  [""
   ["/.well-known/oauth-authorization-server/api/mcp/oauth"
    (endpoint cfg (fn [_ _] (response (authorization-metadata))) :get true)]
   ["/api/mcp/oauth"
    ["/resource" (endpoint cfg (fn [_ _] (response (resource-metadata))) :get true)]
    ["/authorize" (endpoint cfg authorize :get false)]
    ["/register" (endpoint cfg register :post true)]
    ["/token" (endpoint cfg token :post true)]
    ;; Error handling wraps the session middleware too, so its failures also
    ;; get OAuth error bodies and logging.
    ["" {:middleware [{:compile (constantly wrap-errors)}
                      [mw/auth {:cookie (partial session/decode-token cfg)}]
                      [session/authz cfg]]}
     ["/request" (endpoint cfg approval-request :get false)]
     ["/decision" (endpoint cfg decision :post false)]]]])

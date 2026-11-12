;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.mcp-oauth-http-test
  (:require
   [app.common.uri :as uri]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as http]
   [app.http.errors :as errors]
   [app.http.mcp-oauth :as oauth]
   [app.http.session :as session]
   [app.mcp.oauth-clients :as clients]
   [app.mcp.oauth-state :as state]
   [app.rpc.commands.access-token :as access-token]
   [app.setup.clock :as clock]
   [app.util.json :as json]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [yetti.response :as yres])
  (:import java.net.URLEncoder java.nio.charset.StandardCharsets))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset
  (fn [run]
    (binding [cf/config (assoc cf/config :tenant (str (java.util.UUID/randomUUID)))]
      (run))))

(defn- form [params]
  (str/join "&" (for [[key value] params]
                  (str (name key) "=" (URLEncoder/encode (str value) StandardCharsets/UTF_8)))))

(defn- request [path & {:as options}]
  (let [handler (#'http/router-handler (:app.http/router th/*system*))]
    (handler (th/make-dummy-request (assoc options :path path)))))

(defn- body [response] (json/decode (::yres/body response)))

(defn- login-cookie [profile]
  (let [session (session/create-session (:app.http.session/manager th/*system*)
                                        {:profile-id (:id profile)})
        token   (:token (#'session/assign-token th/*system* session))]
    {(cf/get :auth-token-cookie-name) token}))

(defn- begin [client]
  (let [reply (request "/api/mcp/oauth/authorize"
                       :query (form {:client_id (:client_id client)
                                     :redirect_uri (first (:redirect_uris client))
                                     :response_type "code" :scope "mcp"
                                     :resource (oauth/resource)
                                     :state "client-state"
                                     :code_challenge (state/digest (apply str (repeat 43 "a")))
                                     :code_challenge_method "S256"}))]
    (t/is (= 303 (::yres/status reply)))
    (get (uri/query-string->map (.getQuery (java.net.URI. (get-in reply [::yres/headers "location"]))))
         :request)))

(t/deftest discovery-and-registration
  (let [metadata   (body (request "/.well-known/oauth-authorization-server/api/mcp/oauth"))
        resource   (body (request "/api/mcp/oauth/resource"))
        registered (request "/api/mcp/oauth/register" :method :post
                            :headers {"content-type" "application/json"}
                            :body-bytes (json/encode-str {:redirect_uris ["https://example.com/callback"]
                                                          :token_endpoint_auth_method "none"}))]
    (t/is (= (oauth/issuer) (:issuer metadata)))
    (t/is (= ["S256"] (:code_challenge_methods_supported metadata)))
    (t/is (= (oauth/resource) (:resource resource)))
    (t/is (= 201 (::yres/status registered)))
    (t/is (= "none" (:token_endpoint_auth_method (body registered))))
    (t/is (nil? (:client_secret (body registered))))))

(t/deftest complete-authorization-flow
  (let [profile  (th/create-profile* 1 {:is-active true})
        cookies  (login-cookie profile)
        client   (clients/register! th/*system* {:redirect_uris ["https://example.com/callback"]
                                                 :logo_uri "https://example.com/logo.png"
                                                 :token_endpoint_auth_method "none"})
        handle   (begin client)
        details  (request "/api/mcp/oauth/request" :query (form {:request handle}) :cookies cookies)
        origin   (str (cf/get-public-uri))
        origin   (str/replace origin #"/$" "")
        decision {:request handle :decision "approve" :csrf (:csrf (body details))}
        submit   #(request "/api/mcp/oauth/decision" :method :post :cookies cookies
                           :headers {"content-type" "application/x-www-form-urlencoded" "origin" origin}
                           :body-bytes (form %))]
    (t/is (= 401 (::yres/status (request "/api/mcp/oauth/request" :query (form {:request handle})))))
    (t/is (= 200 (::yres/status details)))
    (t/is (= "https://example.com/logo.png" (:logo-uri (body details))))
    (t/is (= 400 (::yres/status (submit (assoc decision :csrf "wrong")))))
    (let [approved (submit decision)
          callback (java.net.URI. (:redirect-uri (body approved)))
          params   (uri/query-string->map (.getQuery callback))
          exchange {:grant_type "authorization_code" :code (:code params)
                    :client_id (:client_id client) :redirect_uri "https://example.com/callback"
                    :resource (oauth/resource) :code_verifier (apply str (repeat 43 "a"))}
          redeem   #(request "/api/mcp/oauth/token" :method :post
                             :headers {"content-type" "application/x-www-form-urlencoded"}
                             :body-bytes (form %))]
      (t/is (= 200 (::yres/status approved)))
      (t/is (= "client-state" (:state params)))
      (t/is (= (oauth/issuer) (:iss params)))
      (t/is (= 400 (::yres/status (submit decision))))
      ;; Metadata is public and editable, but changing it changes the client ID.
      ;; Even with the correct verifier, an edited ID cannot use this approval.
      (let [[prefix encoded] (str/split (:client_id client) #"\." 2)
            payload          (json/decode (.decode (java.util.Base64/getUrlDecoder) encoded))]
        (doseq [changed [(assoc payload :client_name "Different application")
                         (assoc payload :redirect_uris ["https://attacker.example/callback"])]]
          (let [id (str prefix "." (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                                                    (json/encode changed)))]
            (t/is (string? (:client_id (clients/resolve-client {} id))))
            (t/is (= "invalid_grant" (:error (body (redeem (assoc exchange :client_id id)))))))))
      (t/is (= 400 (::yres/status (redeem (assoc exchange :code_verifier (apply str (repeat 43 "b")))))))
      (let [token-response (redeem exchange)
            token          (body token-response)
            stored         (first (db/query th/*pool* :access-token {:profile-id (:id profile) :type "mcp"}))]
        (t/is (= 200 (::yres/status token-response)))
        (t/is (= "no-store" (get-in token-response [::yres/headers "cache-control"])))
        (t/is (= (:token stored) (:access_token token)))
        (t/is (= "Bearer" (:token_type token)))
        (t/is (nil? (:expires_in token)))
        (t/is (nil? (:refresh_token token)))
        (t/is (= 400 (::yres/status (redeem exchange))))))))

(t/deftest denial-and-session-binding
  (let [profile (th/create-profile* 1 {:is-active true})
        other   (th/create-profile* 2 {:is-active true})
        cookies (login-cookie profile)
        client  (clients/register! th/*system* {:redirect_uris ["https://example.com/callback"]
                                                :token_endpoint_auth_method "none"})
        handle  (begin client)
        details (body (request "/api/mcp/oauth/request" :query (form {:request handle}) :cookies cookies))
        params  {:request handle :csrf (:csrf details) :decision "deny"}
        submit  #(request "/api/mcp/oauth/decision" :method :post :cookies cookies
                          :headers {"content-type" "application/x-www-form-urlencoded" "origin" %}
                          :body-bytes (form params))]
    (t/is (= 403 (::yres/status
                  (request "/api/mcp/oauth/request" :query (form {:request handle})
                           :cookies (login-cookie other)))))
    (t/is (= 403 (::yres/status (submit "https://attacker.example"))))
    (let [denied   (submit (str/replace (str (cf/get-public-uri)) #"/$" ""))
          callback (java.net.URI. (:redirect-uri (body denied)))
          result   (uri/query-string->map (.getQuery callback))]
      (t/is (= "access_denied" (:error result)))
      (t/is (= "client-state" (:state result)))
      (t/is (empty? (db/query th/*pool* :access-token {:profile-id (:id profile)})))
      (t/is (not (:mcp-enabled (db/decode-transit-pgobject
                                (:props (th/db-get :profile {:id (:id profile)})))))))))

(t/deftest oauth-input-and-cors-errors
  (doseq [data ["{" "[]" (apply str (repeat 16385 "a"))]]
    (let [reply (request "/api/mcp/oauth/register" :method :post
                         :headers {"content-type" "application/json"}
                         :body-bytes data)]
      (t/is (= 400 (::yres/status reply)))
      (t/is (= "*" (get-in reply [::yres/headers "access-control-allow-origin"])))))
  (let [reply (request "/api/mcp/oauth/token" :method :post
                       :headers {"content-type" "application/x-www-form-urlencoded"}
                       :body-bytes "code=a&code=b")]
    (t/is (= 400 (::yres/status reply))))
  (t/is (= 204 (::yres/status (request "/api/mcp/oauth/token" :method :options)))))

(t/deftest confidential-client-exchange
  (let [profile  (th/create-profile* 1 {:is-active true})
        stored   (db/tx-run! th/*system* access-token/ensure-mcp-access! (:id profile))
        verifier (apply str (repeat 43 "a"))]
    (doseq [method ["client_secret_basic" "client_secret_post"]]
      (let [client (clients/register! th/*system*
                                      {:redirect_uris ["https://example.com/callback"]
                                       :token_endpoint_auth_method method})
            code   (state/create! th/*system* :code
                                  {:client-id (:client_id client)
                                   :redirect-uri "https://example.com/callback"
                                   :resource (oauth/resource)
                                   :challenge (state/digest verifier)
                                   :profile-id (str (:id profile))
                                   :token-id (str (:id stored))}
                                  120)
            params {:grant_type "authorization_code" :client_id (:client_id client)
                    :code code :code_verifier verifier :redirect_uri "https://example.com/callback"}
            redeem (fn [secret]
                     (request "/api/mcp/oauth/token" :method :post
                              :headers (cond-> {"content-type" "application/x-www-form-urlencoded"}
                                         (= method "client_secret_basic")
                                         (assoc "authorization"
                                                (str "Basic " (.encodeToString
                                                               (java.util.Base64/getEncoder)
                                                               (.getBytes (str (:client_id client) ":" secret) "UTF-8")))))
                              :body-bytes (form (cond-> params
                                                  (= method "client_secret_post")
                                                  (assoc :client_secret secret)))))]
        (t/is (= 401 (::yres/status (redeem "wrong"))))
        (t/is (= (:token stored) (:access_token (body (redeem (:client_secret client))))))))))

(t/deftest exchange-rejects-expired-token
  (let [profile  (th/create-profile* 1 {:is-active true})
        stored   (db/tx-run! th/*system* access-token/ensure-mcp-access! (:id profile))
        verifier (apply str (repeat 43 "a"))
        client   (clients/register! th/*system* {:redirect_uris ["https://example.com/callback"]
                                                 :token_endpoint_auth_method "none"})
        code     (state/create! th/*system* :code
                                {:client-id (:client_id client)
                                 :redirect-uri "https://example.com/callback"
                                 :resource (oauth/resource)
                                 :challenge (state/digest verifier)
                                 :profile-id (str (:id profile))
                                 :token-id (str (:id stored))}
                                120)]
    (db/exec! th/*pool* ["UPDATE access_token SET expires_at = now() - interval '1 day' WHERE id = ?"
                         (:id stored)])
    (let [reply (request "/api/mcp/oauth/token" :method :post
                         :headers {"content-type" "application/x-www-form-urlencoded"}
                         :body-bytes (form {:grant_type "authorization_code" :code code
                                            :client_id (:client_id client) :code_verifier verifier
                                            :redirect_uri "https://example.com/callback"}))]
      (t/is (= 400 (::yres/status reply)))
      (t/is (= "invalid_grant" (:error (body reply)))))))

(t/deftest unexpected-errors-are-logged
  (let [logged  (atom [])
        cookies (login-cookie (th/create-profile* 1 {:is-active true}))
        fail    (fn [& _] (throw (RuntimeException. "unexpected")))]
    (with-redefs [errors/handle         (fn [cause _] (swap! logged conj (ex-message cause)))
                  clients/register!     fail
                  clock/get-clock       fail]
      (t/testing "endpoint failures"
        (let [reply (request "/api/mcp/oauth/register" :method :post
                             :headers {"content-type" "application/json"}
                             :body-bytes (json/encode-str {:redirect_uris ["https://example.com/callback"]}))]
          (t/is (= 503 (::yres/status reply)))
          (t/is (= {:error "temporarily_unavailable"} (body reply)))
          (t/is (= "*" (get-in reply [::yres/headers "access-control-allow-origin"])))))
      (t/testing "session middleware failures"
        (let [reply (request "/api/mcp/oauth/request" :query (form {:request "x"}) :cookies cookies)]
          (t/is (= 503 (::yres/status reply)))
          (t/is (= {:error "temporarily_unavailable"} (body reply)))))
      (t/testing "OAuth rejections are not logged"
        (t/is (= 400 (::yres/status (request "/api/mcp/oauth/token" :method :post
                                             :headers {"content-type" "application/x-www-form-urlencoded"}
                                             :body-bytes "code=a&code=b"))))))
    (t/is (= ["unexpected" "unexpected"] @logged))))

(t/deftest authorization-rejects-unregistered-callback
  (let [client (clients/register! th/*system* {:redirect_uris ["https://example.com/callback"]})
        reply  (request "/api/mcp/oauth/authorize"
                        :query (form {:client_id (:client_id client)
                                      :redirect_uri "https://attacker.example/callback"}))]
    (t/is (= 400 (::yres/status reply)))
    (t/is (nil? (get-in reply [::yres/headers "location"])))))

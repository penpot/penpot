;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.mcp-oauth-clients-test
  (:require
   [app.mcp.oauth-clients :as clients]
   [app.mcp.oauth-state :as state]
   [app.util.json :as json]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)

(t/deftest registered-clients
  (let [registration (clients/register! {}
                                        {:client_name "Example"
                                         :redirect_uris ["https://client.example/callback"]})
        client       (clients/resolve-client {} (:client_id registration))]
    (t/is (string? (:client_secret registration)))
    (t/is (nil? (:client_secret client)))
    (t/is (state/secret= (:secret-hash client) (state/digest (:client_secret registration))))
    (t/is (clients/redirect-matches? client "https://client.example/callback"))
    (doseq [redirect ["https://client.example/callback?extra=1"
                      "https://client.example/callback/../evil"
                      "https://evil.example/callback"]]
      (t/is (not (clients/redirect-matches? client redirect))))))

(t/deftest registrations-are-self-contained-and-distinct
  (doseq [method ["none" "client_secret_basic" "client_secret_post"]]
    (let [metadata {:client_name "Example α"
                    :logo_uri "https://client.example/logo.png"
                    :redirect_uris ["https://client.example/callback?source=mcp&mode=oauth"]
                    :token_endpoint_auth_method method}
          first    (clients/register! {} metadata)
          second   (clients/register! {} metadata)
          client   (clients/resolve-client {} (:client_id first))]
      (t/is (not= (:client_id first) (:client_id second)))
      (t/is (<= (count (:client_id first)) 2048))
      (t/is (= metadata (select-keys client (keys metadata))))
      (t/is (= (:client_id first) (:client_id client)))
      (t/is (nil? (:client_secret client)))
      (if (= method "none")
        (do (t/is (nil? (:client_secret first)))
            (t/is (nil? (:secret-hash client))))
        (t/is (state/secret= (:secret-hash client)
                             (state/digest (:client_secret first))))))))

(t/deftest oversized-registration-is-rejected
  (let [metadata {:redirect_uris [(str "https://client.example/" (apply str (repeat 1800 "a")))]}]
    (t/is (= "invalid_client_metadata"
             (try (clients/register! {} metadata)
                  (catch clojure.lang.ExceptionInfo cause (:oauth-error (ex-data cause))))))))

(t/deftest unusable-client-logos-are-ignored
  ;; An optional image must not prevent a client from authorizing.
  (doseq [logo [nil "" 42 "/logo.png" "http://client.example/logo.png"
                "javascript:alert(1)" "data:image/svg+xml,<svg/>"
                "https://user:secret@client.example/logo.png"
                (str "https://client.example/" (apply str (repeat 2048 "a")))]]
    (let [client (clients/validate-metadata
                  {:redirect_uris ["https://client.example/callback"]
                   :logo_uri logo}
                  false)]
      (t/is (not (contains? client :logo_uri))))))

(t/deftest client-id-size-boundary
  (let [callback     "https://client.example/callback/"
        metadata     {:redirect_uris [callback] :token_endpoint_auth_method "none"}
        registration (clients/register! {} metadata)
        [_ encoded]  (str/split (:client_id registration) #"\." 2)
        payload-size (alength (.decode (java.util.Base64/getUrlDecoder) encoded))
        ;; 1,525 payload bytes encode to 2,034 characters plus the 14-char prefix.
        padding      (apply str (repeat (- 1525 payload-size) "a"))
        metadata     (assoc metadata :redirect_uris [(str callback padding)])
        largest      (clients/register! {} metadata)]
    (t/is (= 2048 (count (:client_id largest))))
    (t/is (= (:redirect_uris metadata)
             (:redirect_uris (clients/resolve-client {} (:client_id largest)))))
    (t/is (= "invalid_client_metadata"
             (try (clients/register! {} (update-in metadata [:redirect_uris 0] str "a"))
                  (catch clojure.lang.ExceptionInfo cause (:oauth-error (ex-data cause))))))))

(t/deftest malformed-client-identifiers-are-rejected
  (doseq [id ["unknown" "penpot-mcp-v2.e30" "penpot-mcp-v1.e30"
              "penpot-mcp-v1.W10" "penpot-mcp-v1.%%" "penpot-mcp-v1.bm90LWpzb24"
              (str "penpot-mcp-v1." (apply str (repeat 2048 "a")))]]
    (t/is (= "invalid_client"
             (try (clients/resolve-client {} id)
                  (catch clojure.lang.ExceptionInfo cause (:oauth-error (ex-data cause))))))))

(t/deftest client-metadata-validation
  (doseq [redirect ["https://client.example/callback#fragment" "javascript:alert(1)"
                    "http://client.example/callback" "https://user:secret@client.example/cb"]]
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (clients/validate-metadata {:redirect_uris [redirect]} false))))
  (t/is (clients/redirect-matches? {:redirect_uris ["http://127.0.0.1:3000/callback"]}
                                   "http://127.0.0.1:12345/callback"))
  (t/is (= "none" (:token_endpoint_auth_method
                   (clients/validate-metadata
                    {:redirect_uris ["https://chatgpt.com/connector_platform_oauth_redirect"]
                     :token_endpoint_auth_methods_supported ["none" "private_key_jwt"]}
                    true))))
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (clients/validate-metadata
                  {:redirect_uris ["https://example.com/cb"]
                   :grant_types ["authorization_code" "refresh_token"]} false))))

(t/deftest client-metadata-documents-can-advertise-additional-grants
  (let [metadata {:redirect_uris ["https://client.example/callback"]
                  :grant_types ["authorization_code" "refresh_token"]}]
    (t/is (= ["authorization_code"]
             (:grant_types (clients/validate-metadata metadata true))))))

(t/deftest client-metadata-documents-require-authorization-code-support
  (doseq [grants [[] ["refresh_token"] "authorization_code"
                  ["authorization_code" nil] ["authorization_code" 1]]]
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (clients/validate-metadata
                    {:redirect_uris ["https://client.example/callback"]
                     :grant_types grants}
                    true)))))

(t/deftest localhost-callbacks-allow-only-port-changes
  (let [client {:redirect_uris ["http://localhost/callback"]}]
    (t/is (clients/redirect-uri? "http://localhost/callback"))
    (t/is (clients/redirect-matches? client "http://localhost:52351/callback"))
    (doseq [redirect ["http://localhost.evil.example:52351/callback"
                      "http://127.0.0.1:52351/callback"
                      "http://localhost:52351/other"
                      "http://localhost:52351/callback?extra=1"
                      "http://localhost:52351/callback#fragment"
                      "http://user@localhost:52351/callback"]]
      (t/is (not (clients/redirect-matches? client redirect))))
    (doseq [redirect ["http://localhost.evil.example/callback"
                      "http://user@localhost/callback"]]
      (t/is (not (clients/redirect-uri? redirect))))))

(t/deftest codex-published-client-metadata
  ;; Public metadata from https://chatgpt.com/oauth/codex/client.json.
  ;; Keep this fixture offline so upstream changes cannot make CI flaky.
  (let [client-id "https://chatgpt.com/oauth/codex/client.json"
        metadata  {:client_id client-id
                   :client_uri "https://chatgpt.com/codex"
                   :application_type "native"
                   :redirect_uris ["http://127.0.0.1/callback" "http://localhost/callback"]
                   :token_endpoint_auth_method "none"
                   :token_endpoint_auth_methods_supported ["none"]
                   :grant_types ["authorization_code" "refresh_token"]
                   :response_types ["code"]
                   :client_name "Codex"
                   :logo_uri "https://persistent.oaistatic.com/sonic/misc/openai-logo.png"}]
    (with-mocks [dns {:target 'app.util.ssrf/resolve-host
                      :return (into-array java.net.InetAddress
                                          [(java.net.InetAddress/getByName "93.184.216.34")])}
                 sender {:target 'app.http.client/send!
                         :return (fn [& _]
                                   {:status 200
                                    :headers {"content-type" "application/json" "cache-control" "no-store"}
                                    :body (java.io.ByteArrayInputStream. (json/encode metadata))})}]
      (let [client (clients/resolve-client th/*system* client-id)]
        (t/is (= client-id (:client_id client)))
        (t/is (= (:logo_uri metadata) (:logo_uri client)))
        (t/is (= ["authorization_code"] (:grant_types client)))
        (t/is (= "none" (:token_endpoint_auth_method client)))
        (t/is (clients/redirect-matches? client "http://127.0.0.1:52351/callback"))))))

(t/deftest client-metadata-documents
  (let [client-id "https://client.example/client.json"
        calls     (atom 0)
        metadata  (atom {:client_id client-id
                         :client_name "Example"
                         :redirect_uris ["https://client.example/callback"]
                         :token_endpoint_auth_methods_supported ["none" "private_key_jwt"]})]
    (with-mocks [dns {:target 'app.util.ssrf/resolve-host
                      :return (into-array java.net.InetAddress
                                          [(java.net.InetAddress/getByName "93.184.216.34")])}
                 sender {:target 'app.http.client/send!
                         :return (fn [_ _ _]
                                   (swap! calls inc)
                                   {:status 200
                                    :headers {"content-type" "application/json" "cache-control" "no-store"}
                                    :body (java.io.ByteArrayInputStream.
                                           (json/encode @metadata))})}]
      (t/is (= "none" (:token_endpoint_auth_method (clients/resolve-client th/*system* client-id))))
      (swap! metadata assoc :redirect_uris ["https://client.example/changed"])
      (t/is (not (clients/redirect-matches? (clients/resolve-client th/*system* client-id)
                                            "https://client.example/callback")))
      (t/is (= 2 @calls))
      (swap! metadata assoc :client_id "https://different.example/client.json")
      (t/is (thrown? clojure.lang.ExceptionInfo (clients/resolve-client th/*system* client-id)))
      (swap! metadata assoc :client_id client-id :client_name (apply str (repeat 65537 "a")))
      (t/is (thrown? clojure.lang.ExceptionInfo (clients/resolve-client th/*system* client-id))))))

(t/deftest client-metadata-rejects-unsafe-fetches
  (with-mocks [sender {:target 'app.http.client/send!
                       :return (fn [& _] (throw (AssertionError. "Unsafe request reached HTTP client")))}]
    (doseq [client-id ["https://127.0.0.1/client.json"
                       "https://169.254.169.254/client.json"
                       "https://[::1]/client.json"
                       "https://user:secret@example.com/client.json"
                       "https://example.com/client.json#fragment"]]
      (t/is (thrown? clojure.lang.ExceptionInfo (clients/resolve-client th/*system* client-id))))
    (t/is (not (:called? @sender)))))

(t/deftest client-metadata-rejects-redirects
  (with-mocks [dns {:target 'app.util.ssrf/resolve-host
                    :return (into-array java.net.InetAddress
                                        [(java.net.InetAddress/getByName "93.184.216.34")])}
               sender {:target 'app.http.client/send!
                       :return {:status 302
                                :headers {"location" "https://127.0.0.1/internal"}
                                :body (java.io.ByteArrayInputStream. (byte-array 0))}}]
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (clients/resolve-client th/*system* "https://redirect.example/client.json")))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-middleware-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token]
   [app.http.middleware :as mw]
   [app.http.session :as session]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.access-token]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]
   [yetti.request :as yreq]
   [yetti.response :as yres])
  (:import
   io.undertow.server.RequestTooBigException))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defrecord DummyRequest [headers cookies method body-stream
                         remote-addr server-name server-port
                         scheme protocol path query ssl-client-cert]
  yreq/IRequestCookies
  (get-cookie [_ name]
    {:value (get cookies name)})

  yreq/IRequest
  (get-header [_ name]
    (get headers name))
  (method [_] method)
  (body [_] body-stream)
  (path [_] path)
  (query [_] query)
  (server-port [_] server-port)
  (server-name [_] server-name)
  (remote-addr [_] remote-addr)
  (ssl-client-cert [_] ssl-client-cert)
  (scheme [_] scheme)
  (protocol [_] protocol))

(defn- make-dummy-request
  "Constructs a DummyRequest from an options map. Every key is
  optional; missing values fall back to sensible defaults. New
  fields added to DummyRequest won't break existing call sites
  as long as this constructor keeps its `:or` defaults in sync.

  Recognized keys:
    :headers         — map of header name → value
    :cookies         — map of cookie name → value
    :method          — HTTP method keyword (default :get)
    :body-stream     — InputStream for the body (used directly)
    :body-bytes      — bytes or string for the body; wrapped in a
                       ByteArrayInputStream if :body-stream is not
                       given
    :remote-addr     — string (default \"127.0.0.1\")
    :server-name     — string (default \"test\")
    :server-port     — long   (default 0)
    :scheme          — keyword (default :http)
    :protocol        — string (default \"HTTP/1.1\")
    :path            — string (default \"/test\")
    :query           — string or nil (default nil)
    :ssl-client-cert — X509Certificate or nil (default nil)"
  [{:keys [headers cookies method body-stream body-bytes
           remote-addr server-name server-port scheme protocol
           path query ssl-client-cert]
    :or   {headers {} cookies {} method :get
           body-stream nil
           remote-addr "127.0.0.1" server-name "test" server-port 0
           scheme :http protocol "HTTP/1.1" path "/test" query nil
           ssl-client-cert nil}}]
  (let [body-stream (or body-stream
                        (when body-bytes
                          (java.io.ByteArrayInputStream.
                           (if (string? body-bytes)
                             (.getBytes ^String body-bytes "UTF-8")
                             body-bytes))))]
    (->DummyRequest headers cookies method body-stream
                    remote-addr server-name server-port
                    scheme protocol path query ssl-client-cert)))

(t/deftest auth-middleware-1
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (make-dummy-request {}))

    (t/is (nil? (::http/auth-data @request)))

    (handler (make-dummy-request {:headers {"authorization" "Token aaaa"}}))

    (let [{:keys [token claims] token-type :type} (get @request ::http/auth-data)]
      (t/is (= :token token-type))
      (t/is (= "aaaa" token))
      (t/is (nil? claims)))))

(t/deftest auth-middleware-2
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (make-dummy-request {}))
    (t/is (nil? (::http/auth-data @request)))

    (handler (make-dummy-request {:headers {"authorization" "Bearer aaaa"}}))

    (let [{:keys [token claims] token-type :type} (get @request ::http/auth-data)]
      (t/is (= :bearer token-type))
      (t/is (= "aaaa" token))
      (t/is (nil? claims)))))

(t/deftest auth-middleware-3
  (let [request (volatile! nil)
        handler (#'app.http.middleware/wrap-auth
                 (fn [req] (vreset! request req))
                 {})]

    (handler (make-dummy-request {}))
    (t/is (nil? (::http/auth-data @request)))

    (handler (make-dummy-request {:cookies {"auth-token" "foobar"}}))

    (let [{:keys [token claims] token-type :type} (get @request ::http/auth-data)]
      (t/is (= :cookie token-type))
      (t/is (= "foobar" token))
      (t/is (nil? claims)))))

(t/deftest shared-key-auth
  (let [handler (#'app.http.middleware/wrap-shared-key-auth
                 (fn [req] {::yres/status 200})
                 {:test1 "secret-key"})]

    (let [response (handler (make-dummy-request {}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (make-dummy-request {:headers {"x-shared-key" "secret-key2"}}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (make-dummy-request {:headers {"x-shared-key" "secret-key"}}))]
      (t/is (= 403 (::yres/status response))))

    (let [response (handler (make-dummy-request {:headers {"x-shared-key" "test1 secret-key"}}))]
      (t/is (= 200 (::yres/status response))))))

(t/deftest access-token-authz
  (let [profile (th/create-profile* 1)
        token   (db/tx-run! th/*system* app.rpc.commands.access-token/create-access-token (:id profile) "test" nil nil)
        handler (#'app.http.access-token/wrap-authz identity th/*system*)]

    (let [response (handler nil)]
      (t/is (nil? response)))

    (let [response (handler {::http/auth-data {:type :token :token "foobar" :claims {:tid (:id token)}}})]
      (t/is (= #{} (:app.http.access-token/perms response)))
      (t/is (= (:id profile) (:app.http.access-token/profile-id response))))))

(defrecord MethodAwareDummyRequest [req-method headers]
  yreq/IRequest
  (method [_] req-method)
  (get-header [_ name] (get headers name)))

(t/deftest cors-middleware-allowlisted-origin
  (let [handler (#'app.http.middleware/wrap-cors
                 (fn [_] {::yres/status 200 ::yres/headers {}})
                 #{"https://trusted.example"})
        resp    (handler (->MethodAwareDummyRequest :get {"origin" "https://trusted.example"}))
        headers (::yres/headers resp)]

    (t/is (= 200 (::yres/status resp)))
    (t/is (= "https://trusted.example" (get headers "access-control-allow-origin")))
    (t/is (= "true" (get headers "access-control-allow-credentials")))
    (t/is (= "Origin" (get headers "vary")))
    (t/is (= "content-type" (get headers "access-control-expose-headers")))
    (t/is (not (str/includes?
                (get headers "access-control-allow-headers" "")
                "cookie")))))

(t/deftest cors-middleware-non-allowlisted-origin
  (let [handler (#'app.http.middleware/wrap-cors
                 (fn [_] {::yres/status 200 ::yres/headers {}})
                 #{"https://trusted.example"})
        resp    (handler (->MethodAwareDummyRequest :get {"origin" "https://attacker.example"}))
        headers (::yres/headers resp)]

    (t/is (= 200 (::yres/status resp)))
    (t/is (nil? (get headers "access-control-allow-origin")))
    (t/is (nil? (get headers "access-control-allow-credentials")))
    (t/is (nil? (get headers "access-control-allow-headers")))
    (t/is (nil? (get headers "access-control-expose-headers")))
    (t/is (= "Origin" (get headers "vary")))))

(t/deftest cors-middleware-preflight-allowlisted
  (let [handler (#'app.http.middleware/wrap-cors
                 (fn [_] {::yres/status 200 ::yres/headers {}})
                 #{"https://trusted.example"})
        resp    (handler (->MethodAwareDummyRequest :options {"origin" "https://trusted.example"}))
        headers (::yres/headers resp)]

    (t/is (= 204 (::yres/status resp)))
    (t/is (= "https://trusted.example" (get headers "access-control-allow-origin")))
    (t/is (= "true" (get headers "access-control-allow-credentials")))))

(t/deftest cors-middleware-preflight-non-allowlisted
  (let [handler (#'app.http.middleware/wrap-cors
                 (fn [_] {::yres/status 200 ::yres/headers {}})
                 #{"https://trusted.example"})
        resp    (handler (->MethodAwareDummyRequest :options {"origin" "https://attacker.example"}))
        headers (::yres/headers resp)]

    (t/is (= 204 (::yres/status resp)))
    (t/is (nil? (get headers "access-control-allow-origin")))
    (t/is (nil? (get headers "access-control-allow-credentials")))))

(t/deftest cors-middleware-missing-origin
  (let [handler (#'app.http.middleware/wrap-cors
                 (fn [_] {::yres/status 200 ::yres/headers {}})
                 #{"https://trusted.example"})
        resp    (handler (->MethodAwareDummyRequest :get {}))
        headers (::yres/headers resp)]

    (t/is (= 200 (::yres/status resp)))
    (t/is (nil? (get headers "access-control-allow-origin")))
    (t/is (nil? (get headers "access-control-allow-credentials")))))

(t/deftest session-authz
  (let [cfg      th/*system*
        manager  (session/inmemory-manager)
        profile  (th/create-profile* 1)
        handler  (-> (fn [req] req)
                     (#'session/wrap-authz  {::session/manager manager})
                     (#'mw/wrap-auth {:bearer (partial session/decode-token cfg)
                                      :cookie (partial session/decode-token cfg)}))

        session  (->> (session/create-session manager {:profile-id (:id profile)
                                                       :user-agent "user agent"})
                      (#'session/assign-token cfg))

        response (handler (make-dummy-request {:cookies {"auth-token" (:token session)}}))

        {:keys [token claims] token-type :type}
        (get response ::http/auth-data)]

    (t/is (= :cookie token-type))
    (t/is (= (:token session) token))
    (t/is (= "authentication" (:iss claims)))
    (t/is (= "penpot" (:aud claims)))
    (t/is (= (:id session) (:sid claims)))
    (t/is (= (:id profile) (:uid claims)))))

(t/deftest parse-request-illegal-argument-exception
  ;; clojure.data.json raises IllegalArgumentException (case
  ;; fall-through) on several kinds of malformed input. The
  ;; parse-request middleware should convert any such IAE into a
  ;; 400 :malformed-json validation error rather than letting it
  ;; surface as a 500 internal error. Because the conversion is
  ;; done by raising an ex-info (caught by the top-level error
  ;; handler in app.http/router-handler), this test asserts on
  ;; the ex-info thrown by wrap-parse-request directly.
  (let [handler (#'app.http.middleware/wrap-parse-request
                 (fn [_] {::yres/status 200 ::yres/body :ok}))
        ;; Body contains the bytes for: {"x": "\}"}  -- a string
        ;; value with a backslash followed by '}', which
        ;; clojure.data.json v0.5.x cannot handle.
        body    (.getBytes "{\"x\": \"\\}\"}" "UTF-8")
        request (make-dummy-request
                 {:method  :post
                  :headers {"content-type" "application/json"}
                  :body-bytes body})
        ex      (try
                  (handler request)
                  (catch clojure.lang.ExceptionInfo e e))]
    (t/is (instance? clojure.lang.ExceptionInfo ex))
    (t/is (= :validation (-> ex ex-data :type)))
    (t/is (= :malformed-json (-> ex ex-data :code)))
    (t/is (string? (-> ex ex-data :hint)))))

(t/deftest parse-request-request-too-big-exception
  ;; When RequestTooBigException is raised (e.g. the request body
  ;; exceeded the configured size limit), the middleware should
  ;; convert it to a 413 :request-body-too-large validation
  ;; error.
  (let [handler (#'app.http.middleware/wrap-parse-request
                 (fn [_] (throw (RequestTooBigException. "too large"))))
        request (make-dummy-request
                 {:method  :post
                  :headers {"content-type" "application/json"}
                  :body-bytes (.getBytes "{}" "UTF-8")})
        ex      (try
                  (handler request)
                  (catch clojure.lang.ExceptionInfo e e))]
    (t/is (instance? clojure.lang.ExceptionInfo ex))
    (t/is (= :validation (-> ex ex-data :type)))
    (t/is (= :request-body-too-large (-> ex ex-data :code)))
    (t/is (string? (-> ex ex-data :hint)))))

(t/deftest parse-request-eof-exception
  ;; When java.io.EOFException is raised (e.g. the body stream
  ;; was closed before the parser could read it), the middleware
  ;; should convert it to a 400 :malformed-json validation error.
  (let [handler (#'app.http.middleware/wrap-parse-request
                 (fn [_] (throw (java.io.EOFException. "stream closed"))))
        request (make-dummy-request
                 {:method  :post
                  :headers {"content-type" "application/json"}
                  :body-bytes (.getBytes "{}" "UTF-8")})
        ex      (try
                  (handler request)
                  (catch clojure.lang.ExceptionInfo e e))]
    (t/is (instance? clojure.lang.ExceptionInfo ex))
    (t/is (= :validation (-> ex ex-data :type)))
    (t/is (= :malformed-json (-> ex ex-data :code)))
    (t/is (string? (-> ex ex-data :hint)))))

(t/deftest parse-request-runtime-exception-with-cause
  ;; When a RuntimeException with a non-nil ex-cause is raised,
  ;; the middleware should recurse on the cause and dispatch
  ;; through the specific-exception branches. Here we wrap an
  ;; IllegalArgumentException in a RuntimeException and verify
  ;; it surfaces as :malformed-json.
  (let [iae     (IllegalArgumentException. "No matching clause: 99")
        wrapped (doto (RuntimeException. "wrapped")
                  (.initCause iae))
        handler (#'app.http.middleware/wrap-parse-request
                 (fn [_] (throw wrapped)))
        request (make-dummy-request
                 {:method  :post
                  :headers {"content-type" "application/json"}
                  :body-bytes (.getBytes "{}" "UTF-8")})
        ex      (try
                  (handler request)
                  (catch clojure.lang.ExceptionInfo e e))]
    (t/is (instance? clojure.lang.ExceptionInfo ex))
    (t/is (= :validation (-> ex ex-data :type)))
    (t/is (= :malformed-json (-> ex ex-data :code)))))

(t/deftest parse-request-runtime-exception-without-cause
  ;; When a bare RuntimeException (no ex-cause) is raised, the
  ;; middleware should fall through to errors/handle's :default
  ;; path and return a 500 with :type :server-error :code
  ;; :unexpected. This is the "true internal error" path.
  (let [handler  (#'app.http.middleware/wrap-parse-request
                  (fn [_] (throw (RuntimeException. "boom"))))
        request  (make-dummy-request
                  {:method  :post
                   :headers {"content-type" "application/json"}
                   :body-bytes (.getBytes "{}" "UTF-8")})
        response (handler request)
        body     (::yres/body response)]
    (t/is (= 500 (::yres/status response)))
    (t/is (= :server-error (:type body)))
    (t/is (= :unexpected (:code body)))
    (t/is (= "boom" (:hint body)))))

(t/deftest parse-request-non-runtime-throwable
  ;; When a non-RuntimeException Throwable is raised (e.g. an
  ;; Error subclass or a non-RuntimeException checked-style
  ;; exception), the middleware should fall through to the
  ;; :else branch and call errors/handle. java.io.IOException
  ;; has a dedicated handle-exception method that returns 500
  ;; with :code :io-exception.
  (let [handler  (#'app.http.middleware/wrap-parse-request
                  (fn [_] (throw (java.io.IOException. "network gone"))))
        request  (make-dummy-request
                  {:method  :post
                   :headers {"content-type" "application/json"}
                   :body-bytes (.getBytes "{}" "UTF-8")})
        response (handler request)
        body     (::yres/body response)]
    (t/is (= 500 (::yres/status response)))
    (t/is (= :server-error (:type body)))
    (t/is (= :io-exception (:code body)))
    (t/is (= "network gone" (:hint body)))))

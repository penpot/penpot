;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.auth-oidc-test
  (:require
   [app.auth.oidc :as oidc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.http.session :as session]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]
   [yetti.response :as-alias yres]))

(def ^:private oidc-provider
  {:id "oidc"
   :type "oidc"})

(t/deftest parse-attr-path-supports-dot-and-double-underscore
  (t/is
   (= [:oidc/resource-access :penpot_roles :roles]
      (#'oidc/parse-attr-path oidc-provider "resource_access__penpot_roles__roles")))
  (t/is
   (= [:oidc/ocs :data :email]
      (#'oidc/parse-attr-path oidc-provider "ocs.data.email"))))

(t/deftest process-user-info-supports-dot-notation-nested-attrs
  (let [provider (assoc oidc-provider
                        :email-attr "ocs.data.email"
                        :name-attr "ocs.data.display-name")
        info     (#'oidc/process-user-info provider
                                           {}
                                           {:email_verified true
                                            :ocs {:data {:email "nextcloud@example.com"
                                                         :display-name "Nextcloud User"}}})]
    (t/is (= "nextcloud@example.com" (:email info)))
    (t/is (= "Nextcloud User" (:fullname info)))
    (t/is (true? (:email-verified info)))))

;; The provider's `:user-info-source` value arrives as a string (enforced by
;; the malli schema in `app.config` and used as-is by the hard-coded Google /
;; GitHub provider maps), so the dispatch must interpret strings — not
;; keywords — to actually honour `PENPOT_OIDC_USER_INFO_SOURCE=userinfo`.
(t/deftest select-user-info-source-interprets-config-strings
  (t/testing "explicit string values map to keyword dispatch tokens"
    (t/is (= :token    (#'oidc/select-user-info-source "token")))
    (t/is (= :userinfo (#'oidc/select-user-info-source "userinfo"))))

  (t/testing "missing or explicit \"auto\" falls back to auto dispatch"
    (t/is (= :auto (#'oidc/select-user-info-source "auto")))
    (t/is (= :auto (#'oidc/select-user-info-source nil))))

  (t/testing "unknown values fall back to auto dispatch safely"
    (t/is (= :auto (#'oidc/select-user-info-source "unknown")))
    ;; Guards against the reverse regression — a stray keyword value must
    ;; not silently slip through as if it were the matching string.
    (t/is (= :auto (#'oidc/select-user-info-source :token)))
    (t/is (= :auto (#'oidc/select-user-info-source :userinfo)))))

(t/deftest int-in-range-checks-range-correctly
  (t/testing "values within range return true"
    (t/is (#'oidc/int-in-range? 200 200 300))
    (t/is (#'oidc/int-in-range? 250 200 300))
    (t/is (#'oidc/int-in-range? 299 200 300)))
  (t/testing "values outside range return false"
    (t/is (not (#'oidc/int-in-range? 199 200 300)))
    (t/is (not (#'oidc/int-in-range? 300 200 300)))))

(t/deftest redirect-response-builds-302-response
  (let [result (#'oidc/redirect-response "https://example.com/path")]
    (t/is (= 302 (::yres/status result)))
    (t/is (= "https://example.com/path" (get-in result [::yres/headers "location"])))))

(t/deftest valid-info-validates-info-map
  (t/testing "valid info maps pass validation"
    (t/is (#'oidc/valid-info?
           {:backend "oidc" :email "user@example.com" :fullname "User"
            :email-verified true :props {:foo 1}})))
  (t/testing "incomplete maps fail validation"
    (t/is (not (#'oidc/valid-info? nil)))
    (t/is (not (#'oidc/valid-info? {})))
    (t/is (not (#'oidc/valid-info? {:backend "oidc"})))
    (t/is (not (#'oidc/valid-info? {:backend "oidc" :email "user@example.com"})))
    (t/is (not (#'oidc/valid-info? {:backend "oidc" :email "user@example.com" :fullname "User"})))
    (t/is (not (#'oidc/valid-info?
                {:backend "oidc" :email "user@example.com" :fullname "User" :email-verified true})))))

(t/deftest qualify-prop-key-qualifies-key
  (let [provider {:type "github"}]
    (t/is (= :github/email (#'oidc/qualify-prop-key provider :email)))
    (t/is (= :github/full-name (#'oidc/qualify-prop-key provider :full_name)))
    (t/is (= :github/my-key (#'oidc/qualify-prop-key provider :my-key)))))

(t/deftest qualify-props-qualifies-all-keys
  (let [provider {:type "github"}
        result   (#'oidc/qualify-props provider {:email "u@e.com" :name "Test"})]
    (t/is (= "u@e.com" (:github/email result)))
    (t/is (= "Test" (:github/name result)))))

(t/deftest provider-has-email-verified-checks-email-verified
  (let [provider {:type "github"}]
    (t/testing "returns true when email_verified in props is true"
      (t/is (#'oidc/provider-has-email-verified? provider {:props {:github/email-verified true}})))
    (t/testing "returns false when email_verified is false or missing"
      (t/is (not (#'oidc/provider-has-email-verified? provider {:props {}})))
      (t/is (not (#'oidc/provider-has-email-verified? provider {:props {:github/email-verified false}}))))))

(t/deftest profile-has-provider-props-matches-provider
  (t/testing "non-OIDC provider with string id checks for qualified email key"
    (let [provider {:type "github" :id "github"}]
      (t/is (#'oidc/profile-has-provider-props? provider {:props {:github/email "u@e.com"}}))
      (t/is (not (#'oidc/profile-has-provider-props? provider {:props {}})))
      (t/is (not (#'oidc/profile-has-provider-props? provider {:props nil})))))
  (t/testing "OIDC provider with UUID id checks oidc/provider-id"
    (let [provider {:type "oidc" :id #uuid "00000000-0000-0000-0000-000000000001"}]
      (t/is (#'oidc/profile-has-provider-props?
             provider {:props {:oidc/provider-id "00000000-0000-0000-0000-000000000001"}}))
      (t/is (not (#'oidc/profile-has-provider-props? provider {:props {:oidc/provider-id "other"}}))))))

(t/deftest redirect-with-error-builds-error-url
  (binding [cf/config {:public-uri "http://localhost:3449"}]
    (t/testing "with error and hint"
      (let [result (#'oidc/redirect-with-error "auth-error" "hint message")
            loc    (get-in result [::yres/headers "location"])]
        (t/is (= 302 (::yres/status result)))
        (t/is (.contains loc "http://localhost:3449/#/auth/login?"))
        (t/is (.contains loc "error=auth-error"))
        (t/is (.contains loc "hint=hint"))))
    (t/testing "without hint omits hint param"
      (let [result (#'oidc/redirect-with-error "auth-error")
            loc    (get-in result [::yres/headers "location"])]
        (t/is (.contains loc "error=auth-error"))
        (t/is (not (.contains loc "hint=")))))))

(t/deftest redirect-to-verify-token-builds-verify-url
  (binding [cf/config {:public-uri "http://localhost:3449"}]
    (let [result (#'oidc/redirect-to-verify-token "test-token-value")
          loc    (get-in result [::yres/headers "location"])]
      (t/is (= 302 (::yres/status result)))
      (t/is (.contains loc "http://localhost:3449/#/auth/verify-token?"))
      (t/is (.contains loc "token=test-token-value")))))

(t/deftest build-redirect-uri-constructs-redirect
  (binding [cf/config {:public-uri "http://localhost:3449"}]
    (t/is (= "http://localhost:3449/api/auth/oidc/callback"
             (#'oidc/build-redirect-uri)))))

(t/deftest fetch-user-info-returns-decoded-body-on-success
  (let [cfg      {}
        provider {:user-uri "https://provider.example.com/userinfo"}
        tdata    {:token/access "test-access-token" :token/type "Bearer"}]
    (with-mocks [http-mock {:target 'app.http.client/req
                            :return {:status 200
                                     :body "{\"email\":\"user@example.com\",\"name\":\"Test User\"}"}}]
      (let [result (#'oidc/fetch-user-info cfg provider tdata)]
        (t/is (:called? @http-mock))
        (t/is (= 1 (:call-count @http-mock)))
        (t/is (= "user@example.com" (:email result)))
        (t/is (= "Test User" (:name result)))))))

(t/deftest fetch-user-info-throws-on-non-2xx
  (let [cfg      {}
        provider {:user-uri "https://provider.example.com/userinfo"}
        tdata    {:token/access "test-at" :token/type "Bearer"}]
    (t/testing "401 with Bad credentials"
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return {:status 401
                                       :body "Bad credentials"}}]
        (let [e (try (#'oidc/fetch-user-info cfg provider tdata) (catch Throwable t t))]
          (t/is (instance? clojure.lang.ExceptionInfo e))
          (t/is (= :unable-to-retrieve-user-info (:code (ex-data e))))
          (t/is (= 401 (:http-status (ex-data e))))
          (t/is (= "Bad credentials" (:http-body (ex-data e)))))))
    (t/testing "500 server error"
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return {:status 500
                                       :body "Internal Server Error"}}]
        (let [e (try (#'oidc/fetch-user-info cfg provider tdata) (catch Throwable t t))]
          (t/is (instance? clojure.lang.ExceptionInfo e))
          (t/is (= :unable-to-retrieve-user-info (:code (ex-data e))))
          (t/is (= 500 (:http-status (ex-data e)))))))))

(t/deftest fetch-user-info-passes-correct-request
  (let [cfg      {}
        provider {:user-uri "https://provider.example.com/userinfo" :skip-ssrf-check? true}
        tdata    {:token/access "secret-token" :token/type "Bearer"}]
    (with-mocks [http-mock {:target 'app.http.client/req
                            :return {:status 200 :body "{}"}}]
      (#'oidc/fetch-user-info cfg provider tdata)
      (let [[_ req-opts opts] (-> @http-mock :call-args)]
        (t/is (true? (:skip-ssrf-check? opts)))
        (t/is (= "https://provider.example.com/userinfo" (:uri req-opts)))
        (t/is (= "Bearer secret-token" (get-in req-opts [:headers "Authorization"])))
        (t/is (= :get (:method req-opts)))))))

(t/deftest fetch-access-token-returns-token-data-on-success
  (binding [cf/config {:public-uri "http://localhost:3449"}]
    (let [cfg      {}
          provider {:client-id "test-client"
                    :client-secret "test-secret"
                    :token-uri "https://provider.example.com/token"}
          code     "auth-code-123"]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return {:status 200
                                       :body "{\"access_token\":\"at\",\"id_token\":\"it\",\"token_type\":\"Bearer\"}"}}]
        (let [result (#'oidc/fetch-access-token cfg provider code)]
          (t/is (:called? @http-mock))
          (t/is (= 1 (:call-count @http-mock)))
          (t/is (= "at" (:token/access result)))
          (t/is (= "it" (:token/id result)))
          (t/is (= "Bearer" (:token/type result))))))))

(t/deftest fetch-access-token-throws-on-error
  (binding [cf/config {:public-uri "http://localhost:3449"}]
    (let [cfg      {}
          provider {:client-id "test-client"
                    :client-secret "test-secret"
                    :token-uri "https://provider.example.com/token"}
          code     "auth-code-123"]
      (with-mocks [http-mock {:target 'app.http.client/req
                              :return {:status 400 :body "{\"error\":\"invalid_grant\"}"}}]
        (let [e (try (#'oidc/fetch-access-token cfg provider code) (catch Throwable t t))]
          (t/is (instance? clojure.lang.ExceptionInfo e))
          (t/is (= :unable-to-fetch-access-token (:code (ex-data e)))))))))

;; Shared mock data for get-info tests
(def ^:private mock-tdata
  {:token/access "mock-at" :token/id nil :token/type "Bearer"})

(def ^:private mock-claims
  {:email "user@example.com" :name "User" :exp 1 :iss "test"})

(def ^:private mock-userinfo
  {:email "user@example.com" :name "User"})

(t/deftest get-info-uses-token-source
  (let [provider {:type "oidc" :user-info-source "token"}
        state    {}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly mock-claims)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "user@example.com" (:email result)))
        (t/is (= "User" (:fullname result)))
        (t/is (= "oidc" (:backend result)))
        (t/is (= false (:email-verified result)))))))

(t/deftest get-info-uses-userinfo-source
  (let [provider {:type "oidc" :user-info-source "userinfo"}
        state    {}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly nil)
                  app.auth.oidc/fetch-user-info    (constantly mock-userinfo)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "user@example.com" (:email result)))
        (t/is (= "User" (:fullname result)))
        (t/is (= "oidc" (:backend result)))))))

(t/deftest get-info-auto-prefers-claims
  (let [provider {:type "oidc" :user-info-source "auto"}
        state    {}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly mock-claims)
                  app.auth.oidc/fetch-user-info    (fn [& _] (throw (Exception. "should not call")))]

      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "user@example.com" (:email result)))
        (t/is (= "User" (:fullname result)))))))

(t/deftest get-info-auto-falls-back-to-userinfo
  (let [provider {:type "oidc" :user-info-source "auto"}
        state    {}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly nil)
                  app.auth.oidc/fetch-user-info    (constantly mock-userinfo)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "user@example.com" (:email result)))
        (t/is (= "User" (:fullname result)))))))

(t/deftest get-info-throws-on-incomplete-info
  (let [provider {:type "oidc" :user-info-source "userinfo"}
        state    {}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly nil)
                  app.auth.oidc/fetch-user-info    (constantly {:no-email nil})]
      (let [e (try (#'oidc/get-info {} provider state code) (catch Throwable t t))]
        (t/is (instance? clojure.lang.ExceptionInfo e))
        (t/is (= :incomplete-user-info (:code (ex-data e))))))))

(t/deftest get-info-checks-roles-satisfied
  (let [provider {:type "oidc" :user-info-source "token" :roles #{"member"}}
        state    {}
        code     "code"
        claims   (assoc mock-claims :roles ["member" "admin"])]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly claims)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "user@example.com" (:email result)))
        (t/is (= "oidc" (:backend result)))))))

(t/deftest get-info-throws-on-insufficient-roles
  (let [provider {:type "oidc" :user-info-source "token" :roles #{"admin"}}
        state    {}
        code     "code"
        claims   (assoc mock-claims :roles ["member"])]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly claims)]
      (let [e (try (#'oidc/get-info {} provider state code) (catch Throwable t t))]
        (t/is (instance? clojure.lang.ExceptionInfo e))
        (t/is (= :unable-to-auth (:code (ex-data e))))))))

(t/deftest get-info-merges-state-props
  (let [provider {:type "oidc" :user-info-source "token"}
        state    {:invitation-token "inv-123"
                  :external-session-id "ext-456"
                  :props {:utm_source "twitter"}}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly mock-claims)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "inv-123" (:invitation-token result)))
        (t/is (= "ext-456" (:external-session-id result)))
        (t/is (= "twitter" (get-in result [:props :utm_source])))))))

(t/deftest get-info-adds-sso-session-id-from-claims
  (let [provider {:type "oidc" :user-info-source "token"}
        state    {}
        code     "code"
        claims   (assoc mock-claims :sid "sso-sid")]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly claims)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= "sso-sid" (:sso-session-id result)))))))

(t/deftest get-info-adds-sso-provider-id-for-uuid-provider
  (let [provider {:type "oidc" :user-info-source "token"
                  :id #uuid "00000000-0000-0000-0000-000000000001"}
        state    {}
        code     "code"]
    (with-redefs [app.auth.oidc/fetch-access-token  (constantly mock-tdata)
                  app.auth.oidc/get-id-token-claims (constantly mock-claims)]
      (let [result (#'oidc/get-info {} provider state code)]
        (t/is (= #uuid "00000000-0000-0000-0000-000000000001" (:sso-provider-id result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; callback-handler tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private test-token-key
  (byte-array (map byte (range 32))))

(def ^:private base-cfg
  {::setup/props {:tokens-key test-token-key}
   ::session/manager (session/inmemory-manager)
   :app.email/blacklist  #{"banned.com"}
   :app.email/whitelist  #{"allowed.com"}})

(def ^:private test-profile-id
  #uuid "11111111-1111-1111-1111-111111111111")

(def ^:private test-profile
  {:id test-profile-id
   :is-active true
   :is-blocked false
   :auth-backend "oidc"
   :email "user@example.com"
   :props {}})

(defn- make-state-token
  [cfg overrides]
  (tokens/generate cfg (d/without-nils (merge {:iss "oidc" :provider "oidc"
                                               :exp (ct/in-future {:hours 1})}
                                              overrides))))

(defn- default-request
  [cfg & {:keys [state] :or {state "dummy"}}]
  {:params {:state state :code "test-code"}
   :method :get
   :path "/api/auth/oidc/callback"
   :headers {"user-agent" "TestAgent"
             "x-forwarded-for" "127.0.0.1"}
   :remote-addr "127.0.0.1"})

(defn- redirect-location
  "Extract the Location header from a handler response."
  [result]
  (get-in result [::yres/headers "location"]))

(t/deftest callback-param-error-redirects-to-login
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        request {:params {:error "access_denied"}}]
    (binding [cf/config {:public-uri "http://localhost:3449"}]
      (let [result (#'oidc/callback-handler cfg request)
            loc    (redirect-location result)]
        (t/is (= 302 (::yres/status result)))
        (t/is (.contains loc "error=unable-to-auth"))
        (t/is (.contains loc "hint=access_denied"))))))

(t/deftest callback-no-profile-registration-disabled
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        state   (make-state-token cfg {})
        request (default-request cfg :state state)]
    (binding [cf/config {:public-uri "http://localhost:3449"}
              cf/flags #{}]
      (with-redefs [app.auth.oidc/resolve-provider (constantly {:type "oidc" :id "oidc"})
                    app.auth.oidc/get-info         (constantly {:email "u@e.com" :fullname "U"
                                                                :backend "oidc" :email-verified false
                                                                :props {}})
                    app.auth.oidc/get-profile      (constantly nil)]
        (let [result (#'oidc/callback-handler cfg request)
              loc    (redirect-location result)]
          (t/is (.contains loc "error=registration-disabled")))))))

(t/deftest callback-profile-blocked
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        state   (make-state-token cfg {})
        request (default-request cfg :state state)]
    (binding [cf/config {:public-uri "http://localhost:3449"}
              cf/flags #{:registration}]
      (with-redefs [app.auth.oidc/resolve-provider (constantly {:type "oidc" :id "oidc"})
                    app.auth.oidc/get-info         (constantly {:email "u@e.com" :fullname "U"
                                                                :backend "oidc" :email-verified false
                                                                :props {}})
                    app.auth.oidc/get-profile      (constantly (assoc test-profile :is-blocked true))]
        (let [result (#'oidc/callback-handler cfg request)
              loc    (redirect-location result)]
          (t/is (.contains loc "error=profile-blocked")))))))

(t/deftest callback-provider-mismatch
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        state   (make-state-token cfg {})
        request (default-request cfg :state state)]
    (binding [cf/config {:public-uri "http://localhost:3449"}
              cf/flags #{:registration}]
      (with-redefs [app.auth.oidc/resolve-provider (constantly {:type "oidc" :id "oidc"})
                    app.auth.oidc/get-info         (constantly {:email "u@e.com" :fullname "U"
                                                                :backend "oidc" :email-verified false
                                                                :props {}})
                    app.auth.oidc/get-profile      (constantly (assoc test-profile :auth-backend "gitlab"))]
        (let [result (#'oidc/callback-handler cfg request)
              loc    (redirect-location result)]
          (t/is (.contains loc "error=auth-provider-not-allowed")))))))

(t/deftest callback-profile-inactive-redirects-to-register
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        state   (make-state-token cfg {})
        request (default-request cfg :state state)]
    (binding [cf/config {:public-uri "http://localhost:3449"}
              cf/flags #{:registration}]
      (with-redefs [app.auth.oidc/resolve-provider (constantly {:type "oidc" :id "oidc"})
                    app.auth.oidc/get-info         (constantly {:email "u@e.com" :fullname "U"
                                                                :backend "oidc" :email-verified false
                                                                :props {}})
                    app.auth.oidc/get-profile      (constantly (assoc test-profile :is-active false))]
        (let [result (#'oidc/callback-handler cfg request)
              loc    (redirect-location result)]
          (t/is (.contains loc "http://localhost:3449/#/auth/register/validate?"))
          (t/is (.contains loc "token=")))))))

(t/deftest callback-success-flow
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        state   (make-state-token cfg {})
        request (default-request cfg :state state)]
    (binding [cf/config {:public-uri "http://localhost:3449"}
              cf/flags #{:registration}]
      (with-redefs [app.auth.oidc/resolve-provider      (constantly {:type "oidc" :id "oidc"})
                    app.auth.oidc/get-info              (constantly {:email "u@e.com" :fullname "U"
                                                                     :backend "oidc" :email-verified false
                                                                     :props {}})
                    app.auth.oidc/get-profile           (constantly test-profile)
                    app.auth.oidc/update-profile-with-info (fn [cfg profile info] profile)
                    app.loggers.audit/submit            (constantly nil)]
        (let [result (#'oidc/callback-handler cfg request)
              loc    (redirect-location result)]
          (t/is (.contains loc "http://localhost:3449/#/auth/verify-token?"))
          (t/is (.contains loc "token=")))))))

(t/deftest callback-gracefully-handles-unable-to-retrieve-user-info
  (let [cfg     (dissoc base-cfg :app.email/blacklist :app.email/whitelist)
        state   (make-state-token cfg {})
        request (default-request cfg :state state)]
    (binding [cf/config {:public-uri "http://localhost:3449"}]
      (with-redefs [app.auth.oidc/resolve-provider (constantly {:type "oidc" :id "oidc"})
                    app.auth.oidc/get-info         (fn [& _]
                                                     (ex/raise :type :internal
                                                               :code :unable-to-retrieve-user-info
                                                               :hint "unable to retrieve user info"
                                                               :http-status 401
                                                               :http-body "Bad credentials"))]
        (let [result (#'oidc/callback-handler cfg request)
              loc    (redirect-location result)]
          (t/is (= 302 (::yres/status result)))
          (t/is (.contains loc "error=unable-to-auth")))))))

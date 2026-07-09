;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.auth-oidc-test
  (:require
   [app.auth.oidc :as oidc]
   [app.config :as cf]
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

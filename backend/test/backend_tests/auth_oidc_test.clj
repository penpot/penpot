;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.auth-oidc-test
  (:require
   [app.auth.oidc :as oidc]
   [buddy.sign.jwt :as jwt]
   [clojure.test :as t]))

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

;; OIDC core spec 3.1.3.7 — the relying party MUST validate `iss` and `aud`
;; on the ID token, not just the signature. Use HS256 with a shared secret
;; so the tests stay self-contained (no key material to manage).
(def ^:private hs-secret
  "shared-secret-do-not-use-in-prod-do-not-use-in-prod-do-not-use!!")

(def ^:private hs-provider
  {:id "oidc"
   :type "oidc"
   :client-id "client-id-123"
   :issuer "https://idp.example.com"
   :client-secret hs-secret})

(defn- sign-id-token
  [overrides]
  (let [now (quot (System/currentTimeMillis) 1000)
        claims (merge {:iss "https://idp.example.com"
                       :aud "client-id-123"
                       :sub "user-1"
                       :exp (+ now 3600)
                       :iat now}
                      overrides)]
    (jwt/sign claims hs-secret {:alg :hs256})))

(t/deftest get-id-token-claims-validates-iss-and-aud
  (t/testing "valid token with matching iss/aud is decoded"
    (let [token  (sign-id-token {})
          claims (#'oidc/get-id-token-claims hs-provider {:token/id token})]
      (t/is (= "user-1" (:sub claims)))
      (t/is (= "client-id-123" (:aud claims)))
      (t/is (= "https://idp.example.com" (:iss claims)))))

  (t/testing "token with mismatched issuer is rejected"
    (let [token (sign-id-token {:iss "https://attacker.example.com"})]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (#'oidc/get-id-token-claims hs-provider {:token/id token})))))

  (t/testing "token issued for a different client is rejected"
    (let [token (sign-id-token {:aud "other-client-id"})]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (#'oidc/get-id-token-claims hs-provider {:token/id token})))))

  (t/testing "expired token is rejected"
    (let [now    (quot (System/currentTimeMillis) 1000)
          token  (sign-id-token {:exp (- now 60)})]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (#'oidc/get-id-token-claims hs-provider {:token/id token})))))

  (t/testing "absent id token returns nil (e.g., GitHub OAuth)"
    (t/is (nil? (#'oidc/get-id-token-claims hs-provider {})))))

;; Init-time guard that closes the loophole exploited above: if iss cannot be
;; enforced at runtime (no configured or discovered issuer), prepare-oidc-provider
;; must refuse to start the provider rather than silently skip the check.
(def ^:private manual-oidc-params
  {:type "oidc"
   :id "oidc"
   :base-uri "https://idp.example.com"
   :client-id "client-id-123"
   :client-secret "secret"
   :token-uri "https://idp.example.com/token"
   :user-uri "https://idp.example.com/userinfo"
   :auth-uri "https://idp.example.com/auth"})

(t/deftest prepare-oidc-provider-requires-issuer
  (t/testing "manual URI configuration without issuer is rejected"
    (t/is (thrown-with-msg? Exception #"missing issuer"
                            (#'oidc/prepare-oidc-provider {} manual-oidc-params))))

  (t/testing "manual URI configuration with explicit issuer is accepted"
    (let [provider (#'oidc/prepare-oidc-provider {} (assoc manual-oidc-params
                                                           :issuer "https://idp.example.com"))]
      (t/is (= "https://idp.example.com" (:issuer provider))))))

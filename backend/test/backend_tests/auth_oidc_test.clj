;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.auth-oidc-test
  (:require
   [app.auth.oidc :as oidc]
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

(t/deftest token-endpoint-errors-detect-valid-client-credentials
  (let [response {:status 403
                  :body "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid authorization code\"}"}]
    (t/is (#'oidc/token-endpoint-valid-client-error? response))
    (t/is (not (#'oidc/token-endpoint-invalid-client-error? response)))))

(t/deftest token-endpoint-errors-detect-invalid-client-credentials
  (t/is (#'oidc/token-endpoint-invalid-client-error?
         {:status 401
          :body "{\"error\":\"access_denied\",\"error_description\":\"Unauthorized\"}"}))
  (t/is (#'oidc/token-endpoint-invalid-client-error?
         {:status 400
          :body "{\"error\":\"invalid_client\"}"}))
  (t/is (not (#'oidc/token-endpoint-valid-client-error?
              {:status 400
               :body "{\"error\":\"invalid_client\"}"}))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.auth-oidc-provider-test
  "The per-domain providers stored in `sso_provider` are loaded at login time,
  so the fail-closed checks live in `load-provider`. This namespace runs the real
  query, which is the part the unit tests of `app.auth.oidc` never touch."
  (:require
   [app.auth.oidc :as oidc]
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-sso-provider!
  "Insert a provider row that bypasses discovery through explicit endpoints,
  which is the configuration that needs a stored issuer."
  [{:keys [id domain issuer]}]
  (th/db-insert! :sso-provider
                 (cond-> {:id            id
                          :type          "oidc"
                          :domain        domain
                          :client-id     "client-id"
                          :client-secret "client-secret"
                          :base-uri      "https://idp.example.com"
                          :token-uri     "https://idp.example.com/token"
                          :auth-uri      "https://idp.example.com/auth"
                          :user-uri      "https://idp.example.com/userinfo"}
                   (some? issuer) (assoc :issuer issuer))))

(t/deftest load-provider-fails-closed-without-issuer
  (t/testing "a row with explicit endpoints and no issuer does not initialize"
    (let [id (uuid/next)]
      (create-sso-provider! {:id id :domain "no-issuer.example.com"})
      (let [cause (try
                    (#'oidc/load-provider th/*system* id)
                    (catch Throwable cause cause))]
        (t/is (ex/error? cause))
        (t/is (= :invalid-sso-config (:code (ex-data cause))))
        (t/is (str/includes? (ex-message cause) "missing issuer"))))))

(t/deftest load-provider-uses-the-stored-issuer
  (t/testing "the issuer of the row is what the ID token is validated against"
    (let [id (uuid/next)]
      (create-sso-provider! {:id     id
                             :domain "issuer.example.com"
                             :issuer "https://idp.example.com"})
      (let [provider (#'oidc/load-provider th/*system* id)]
        (t/is (= "https://idp.example.com" (:issuer provider)))
        (t/is (= "client-id" (:client-id provider)))))))

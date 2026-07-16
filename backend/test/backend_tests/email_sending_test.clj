;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.email-sending-test
  (:require
   [app.config :as cf]
   [app.db :as db]
   [app.email :as emails]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest register-email-rendering
  (let [result (emails/render emails/register {:to "example@app.io" :name "foo"})]
    (t/is (map? result))
    (t/is (contains? result :subject))
    (t/is (contains? result :body))
    (t/is (contains? result :to))
    #_(t/is (contains? result :reply-to))
    (t/is (map? (:body result)))))

(def ^:private sso-notice-snippet
  "has set up single sign-on (SSO) in Penpot")

(defn- email-text-body
  [result]
  (get-in result [:body "text/plain"]))

(defn- invite-email-params
  [organization]
  {:to "invitee@example.com"
   :public-uri (cf/get :public-uri)
   :invited-by "Owner User"
   :user-name "Invitee User"
   :token "test-token"
   :organization organization})

(t/deftest invite-to-org-includes-sso-notice-when-active
  (let [result (emails/render emails/invite-to-org
                              (invite-email-params {:name "Acme Inc"
                                                    :sso-active true}))]
    (t/is (str/includes? (email-text-body result) sso-notice-snippet))
    (t/is (str/includes? (get-in result [:body "text/html"]) sso-notice-snippet))))

(t/deftest invite-to-org-omits-sso-notice-when-inactive
  (let [result (emails/render emails/invite-to-org
                              (invite-email-params {:name "Acme Inc"
                                                    :sso-active false}))]
    (t/is (not (str/includes? (email-text-body result) sso-notice-snippet)))
    (t/is (not (str/includes? (get-in result [:body "text/html"]) sso-notice-snippet)))))

(t/deftest invite-to-team-includes-sso-notice-when-active
  (let [result (emails/render emails/invite-to-team
                              {:to "invitee@example.com"
                               :public-uri (cf/get :public-uri)
                               :invited-by "Owner User"
                               :team "Design Team"
                               :token "test-token"
                               :organization {:name "Acme Inc"
                                              :sso-active true}})]
    (t/is (str/includes? (email-text-body result) sso-notice-snippet))
    (t/is (str/includes? (get-in result [:body "text/html"]) sso-notice-snippet))))

(t/deftest invite-to-team-omits-sso-notice-when-inactive
  (let [result (emails/render emails/invite-to-team
                              {:to "invitee@example.com"
                               :public-uri (cf/get :public-uri)
                               :invited-by "Owner User"
                               :team "Design Team"
                               :token "test-token"
                               :organization {:name "Acme Inc"
                                              :sso-active false}})]
    (t/is (not (str/includes? (email-text-body result) sso-notice-snippet)))
    (t/is (not (str/includes? (get-in result [:body "text/html"]) sso-notice-snippet)))))

(t/deftest invite-to-team-omits-sso-notice-without-organization
  (let [result (emails/render emails/invite-to-team
                              {:to "invitee@example.com"
                               :public-uri (cf/get :public-uri)
                               :invited-by "Owner User"
                               :team "Design Team"
                               :token "test-token"})]
    (t/is (not (str/includes? (email-text-body result) sso-notice-snippet)))
    (t/is (not (str/includes? (get-in result [:body "text/html"]) sso-notice-snippet)))))

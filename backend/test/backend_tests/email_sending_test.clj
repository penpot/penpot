;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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

(t/deftest invite-to-organization-includes-sso-notice-when-active
  (let [result (emails/render emails/invite-to-organization
                              (invite-email-params {:name "Acme Inc"
                                                    :sso-active true}))]
    (t/is (str/includes? (email-text-body result) sso-notice-snippet))
    (t/is (str/includes? (get-in result [:body "text/html"]) sso-notice-snippet))))

(t/deftest invite-to-organization-omits-sso-notice-when-inactive
  (let [result (emails/render emails/invite-to-organization
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

(defn- renewal-notice-params
  [user-name]
  {:to "billing@example.com"
   :public-uri (cf/get :public-uri)
   :user-name user-name
   :renewal-date "2026-01-01"
   :estimated-amount "$42.00"
   :organizations [{:name "Acme"
                    :initials "AC"}]})

(t/deftest renewal-notice-greets-by-name-when-user-name-present
  (let [result (emails/render emails/renewal-notice (renewal-notice-params "Acme Org"))]
    (t/is (str/includes? (email-text-body result) "Hi Acme Org,"))
    (t/is (str/includes? (get-in result [:body "text/html"]) "Hi Acme Org,"))))

(t/deftest renewal-notice-omits-space-before-comma-when-user-name-is-empty
  (let [result (emails/render emails/renewal-notice (renewal-notice-params ""))]
    (t/is (str/includes? (email-text-body result) "Hi,"))
    (t/is (not (str/includes? (email-text-body result) "Hi ,")))
    (t/is (str/includes? (get-in result [:body "text/html"]) "Hi,"))
    (t/is (not (str/includes? (get-in result [:body "text/html"]) "Hi ,")))))

(t/deftest renewal-notice-omits-space-before-comma-when-user-name-is-nil
  (let [result (emails/render emails/renewal-notice (renewal-notice-params nil))]
    (t/is (str/includes? (email-text-body result) "Hi,"))
    (t/is (not (str/includes? (email-text-body result) "Hi ,")))
    (t/is (str/includes? (get-in result [:body "text/html"]) "Hi,"))
    (t/is (not (str/includes? (get-in result [:body "text/html"]) "Hi ,")))))

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
   [app.jobs :as jobs]
   [app.metrics :as-alias mtx]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [integrant.core :as ig]
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

(t/deftest send-with-reuse-conn-and-no-conn-raises
  (t/testing "reuse-conn without a caller connection fails fast"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"reuse-conn"
                            (emails/send {}
                                         {::emails/reuse-conn true
                                          ::emails/factory (fn [_] {:to "a@example.com"})})))))

(t/deftest send-with-reuse-conn-joins-caller-transaction
  (let [job-def (ig/init-key ::emails/job-def
                             {::emails/sendmail (fn [_] nil)})
        defs    {:sendmail job-def}
        email   {:to "a@example.com" :subject "hi" :body "hello"}
        job-id  (db/tx-run! {::db/pool th/*pool*}
                            (fn [cfg]
                              (emails/send (assoc cfg
                                                  ::jobs/defs defs
                                                  ::mtx/metrics (get th/*system* :app.metrics/metrics))
                                           (assoc email ::emails/reuse-conn true))))]
    (t/testing "the email job is submitted through the caller conn"
      (t/is (= "sendmail" (:name (th/db-get :job {:id job-id})))))))

(defn- templated-job-def
  [sendmail]
  (ig/init-key ::emails/templated-job-def
               {::emails/sendmail sendmail}))

(defn- submit-templated
  [job-def params]
  (db/tx-run! {::db/pool th/*pool*}
              (fn [cfg]
                (emails/send-template (assoc cfg
                                             ::jobs/defs {:send-templated-mail job-def}
                                             ::mtx/metrics (get th/*system* :app.metrics/metrics))
                                      (assoc params ::emails/reuse-conn true)))))

(def ^:private invite-to-team-recipe
  {:to "invitee@example.com"
   :public-uri (cf/get :public-uri)
   :invited-by "Owner User"
   :team "Design Team"
   :token "test-token"
   :organization {:name "Acme Inc"
                  :sso-active false}})

(t/deftest templated-registry-covers-all-factories
  (t/is (= #{"feedback" "register" "password-recovery" "password-changed"
             "change-email" "invite-to-team" "invite-to-organization"
             "organization-setup-sso" "renewal-notice" "join-team"
             "request-file-access" "request-file-access-yourpenpot"
             "request-file-access-yourpenpot-view" "request-team-access"
             "comment-mention" "comment-thread" "comment-notification"}
           (set (keys emails/templates)))))

(t/deftest send-template-validation
  (t/testing "missing template fails fast"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"template"
                            (emails/send-template {} {:to "a@example.com"}))))
  (t/testing "unknown template fails fast"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown email template"
                            (emails/send-template {} {:to "a@example.com"
                                                      ::emails/template "nope"}))))
  (t/testing "template ids are strings, never keywords"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown email template"
                            (emails/send-template {} {:to "a@example.com"
                                                      ::emails/template :app.email/register}))))
  (t/testing "reuse-conn without a caller connection fails fast"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"reuse-conn"
                            (emails/send-template {} {:to "a@example.com"
                                                      ::emails/reuse-conn true
                                                      ::emails/template "register"})))))

(t/deftest send-template-stores-recipe-not-rendered-mail
  (let [job-def  (templated-job-def (fn [_] nil))
        job-id   (submit-templated job-def (assoc invite-to-team-recipe
                                                  ::emails/template "invite-to-team"))
        row      (th/db-get :job {:id job-id})
        params   (jobs/decode-params job-def (:params row))
        rendered (emails/render emails/invite-to-team invite-to-team-recipe)]
    (t/testing "the job lands on the new queue"
      (t/is (= "send-templated-mail" (:name row))))
    (t/testing "stored params carry the template id and no rendered content"
      (t/is (= "invite-to-team" (:template params)))
      (t/is (not (contains? params :subject)))
      (t/is (not (contains? params :body))))
    (t/testing "stored params are smaller than the rendered html alone"
      (let [stored-size   (count (str (:params row)))
            rendered-html (count (get-in rendered [:body "text/html"]))]
        (t/is (< stored-size rendered-html))))
    (t/testing "the worker renders the same mail from the stored recipe"
      (t/is (= rendered ((get emails/templates (:template params))
                         (dissoc params :template)))))))

(t/deftest send-template-roundtrips-uuids-and-profile
  (let [sent     (atom nil)
        job-def  (templated-job-def (fn [_ email] (reset! sent email)))
        team-id  (random-uuid)
        recipe   {:to "member@example.com"
                  :public-uri (cf/get :public-uri)
                  :invited-by "Owner User"
                  :team "Design Team"
                  :team-id team-id}
        job-id   (submit-templated job-def (assoc recipe
                                                  ::emails/template "join-team"))
        params   (jobs/decode-params job-def (:params (th/db-get :job {:id job-id})))]
    (t/testing "uuids survive the plain-JSON queue roundtrip typed"
      (t/is (uuid? (:team-id params)))
      (t/is (= team-id (:team-id params))))
    (t/testing "the handler renders and delivers the mail"
      ((::jobs/handler job-def) nil params)
      (t/is (= (:subject (emails/render emails/join-team recipe))
               (:subject @sent)))
      (t/is (= "member@example.com" (:to @sent)))))
  (let [sent     (atom nil)
        job-def  (templated-job-def (fn [_ email] (reset! sent email)))
        recipe   {:to "dest@example.com"
                  :reply-to "user@example.com"
                  :email "user@example.com"
                  :attachments {"error-report.txt" "trace"}
                  :feedback-subject "subj"
                  :feedback-type "bug"
                  :feedback-content "content"
                  :profile {:id (random-uuid) :fullname "User" :email "user@example.com"}}
        job-id   (submit-templated job-def (assoc recipe
                                                  ::emails/template "feedback"))
        params   (jobs/decode-params job-def (:params (th/db-get :job {:id job-id})))]
    (t/testing "nested profile id survives the roundtrip typed"
      (t/is (uuid? (get-in params [:profile :id]))))
    (t/testing "the handler renders the feedback mail"
      ((::jobs/handler job-def) nil params)
      (t/is (= (:subject (emails/render emails/user-feedback recipe))
               (:subject @sent))))))

(t/deftest send-templated-mail-handler-rejects-unknown-template
  (let [job-def (templated-job-def (fn [_] nil))
        code    (try
                  ((::jobs/handler job-def) nil {:template "nope"
                                                 :to "a@example.com"})
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    (-> e ex-data :code)))]
    (t/is (= :unknown-email-template code))))

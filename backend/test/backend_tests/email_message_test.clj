;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.email-message-test
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [app.email :as emails]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ADDRESS HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest clean-normalizes-addresses
  (t/testing "case is lowercased"
    (t/is (= "foo@example.com" (emails/clean "FOO@Example.COM"))))
  (t/testing "mailto prefix is removed"
    (t/is (= "foo@example.com" (emails/clean "mailto:foo@example.com"))))
  (t/testing "angle brackets are trimmed"
    (t/is (= "foo@example.com" (emails/clean "<foo@example.com>"))))
  (t/testing "all normalizations apply together"
    (t/is (= "foo@example.com" (emails/clean "mailto:<FOO@Example.COM>")))))

(t/deftest get-domain-uses-normalized-address
  (t/is (= "example.com" (emails/get-domain "MAILTO:<Foo@Example.COM>"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SMTP CONFIG AND SESSION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private full-smtp-config
  {::emails/username "user@example.com"
   ::emails/password "secret"
   ::emails/tls true
   ::emails/host "smtp.example.com"
   ::emails/port 587
   ::emails/default-from "no-reply@example.com"})

(t/deftest smtp-config-validation
  (t/is (= full-smtp-config (emails/valid-smtp-config? full-smtp-config)))
  (t/testing "a string port is not an integer"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (emails/valid-smtp-config? {::emails/port "587"}))))
  (t/testing "a string tls flag is not a boolean"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (emails/valid-smtp-config? {::emails/tls "true"})))))

(t/deftest smtp-session-properties
  (let [session (@#'emails/create-smtp-session (assoc full-smtp-config
                                                      ::emails/timeout 5000))]
    (t/is (= "user@example.com" (.getProperty session "mail.user")))
    (t/is (= "smtp.example.com" (.getProperty session "mail.host")))
    (t/is (= "smtp.example.com" (.getProperty session "mail.smtp.host")))
    (t/is (= "587" (.getProperty session "mail.smtp.port")))
    (t/is (= "user@example.com" (.getProperty session "mail.smtp.user")))
    (t/is (= "true" (.getProperty session "mail.smtp.auth")))
    (t/is (= "true" (.getProperty session "mail.smtp.starttls.enable")))
    (t/is (= "true" (.getProperty session "mail.smtp.starttls.required")))
    (t/is (= "no-reply@example.com" (.getProperty session "mail.from")))
    (t/is (= "5000" (.getProperty session "mail.smtp.timeout")))
    (t/is (= "5000" (.getProperty session "mail.smtp.connectiontimeout")))
    (t/is (= "false" (.getProperty session "mail.debug")))))

(t/deftest smtp-session-with-minimal-config
  (let [session (@#'emails/create-smtp-session {::emails/host "smtp.example.com"})]
    (t/testing "authentication is off without a username"
      (t/is (= "false" (.getProperty session "mail.smtp.auth"))))
    (t/testing "the timeout has a default"
      (t/is (= "30000" (.getProperty session "mail.smtp.timeout"))))
    (t/testing "nil options do not become properties"
      (t/is (nil? (.getProperty session "mail.from")))
      (t/is (nil? (.getProperty session "mail.smtp.starttls.enable"))))))

(t/deftest smtp-session-debug-property-follows-flag
  (with-redefs [cf/flags (conj cf/flags :smtp-debug)]
    (let [session (@#'emails/create-smtp-session {})]
      (t/is (= "true" (.getProperty session "mail.debug"))))))

(t/deftest smtp-session-rejects-invalid-config
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected valid smtp config"
                          (@#'emails/create-smtp-session {::emails/port "587"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MESSAGE CONSTRUCTION (OFFLINE)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private session
  (jakarta.mail.Session/getInstance (java.util.Properties.)))

(defn- message
  [cfg params]
  (@#'emails/create-smtp-message cfg session (merge {:body "hello"} params)))

(t/deftest message-assigns-recipients-by-type
  (let [msg (message {} {:to "one@example.com"
                         :cc ["two@example.com" "three@example.com"]
                         :bcc "four@example.com"
                         :subject "subject"})]
    (t/is (= ["one@example.com"]
             (mapv str (.getRecipients msg jakarta.mail.Message$RecipientType/TO))))
    (t/is (= ["two@example.com" "three@example.com"]
             (mapv str (.getRecipients msg jakarta.mail.Message$RecipientType/CC))))
    (t/is (= ["four@example.com"]
             (mapv str (.getRecipients msg jakarta.mail.Message$RecipientType/BCC))))))

(t/deftest message-uses-default-from-and-reply-to
  (let [msg (message {::emails/default-from "default@example.com"
                      ::emails/default-reply-to "reply@example.com"}
                     {:to "one@example.com" :subject "subject"})]
    (t/is (= "default@example.com" (str (first (.getFrom msg)))))
    (t/is (= "reply@example.com" (str (first (.getReplyTo msg)))))))

(t/deftest message-params-override-default-from-and-reply-to
  (let [msg (message {::emails/default-from "default@example.com"
                      ::emails/default-reply-to "reply@example.com"}
                     {:to "one@example.com"
                      :from "custom@example.com"
                      :reply-to "custom-reply@example.com"
                      :subject "subject"})]
    (t/is (= "custom@example.com" (str (first (.getFrom msg)))))
    (t/is (= "custom-reply@example.com" (str (first (.getReplyTo msg)))))))

(t/deftest message-keeps-subject-and-charset
  (let [msg (message {} {:to "one@example.com" :subject "Ásunto" :charset "utf-8"})]
    (t/is (= "Ásunto" (.getSubject msg)))))

(t/deftest message-adds-extra-headers
  (let [msg (message {} {:to "one@example.com"
                         :subject "subject"
                         :headers {:x-custom "value"}
                         :extra-data "token"})]
    (t/is (= ["value"] (vec (.getHeader msg "x-custom"))))
    (t/is (= ["token"] (vec (.getHeader msg "X-Penpot-Data"))))))

(t/deftest message-adds-empty-penpot-data-header-without-extra-data
  (let [msg (message {} {:to "one@example.com" :subject "subject"})]
    (t/is (= [""] (vec (.getHeader msg "X-Penpot-Data"))))))

(t/deftest message-with-string-body-has-one-text-part
  (let [msg (message {} {:to "one@example.com" :subject "subject" :body "hello"})
        mixed (.getContent msg)
        part (.getBodyPart mixed 0)]
    (t/is (instance? jakarta.mail.internet.MimeMultipart mixed))
    (t/is (= 1 (.getCount mixed)))
    (t/is (= "text/plain; charset=utf-8" (.getContentType part)))
    (t/is (= "hello" (.getContent part)))))

(t/deftest message-with-map-body-has-alternative-parts
  (let [msg (message {} {:to "one@example.com"
                         :subject "subject"
                         :body {"text/plain" "plain"
                                "text/html" "<p>html</p>"}})
        alternative (.getContent (.getBodyPart (.getContent msg) 0))
        plain (.getBodyPart alternative 0)
        html (.getBodyPart alternative 1)]
    (t/is (= 2 (.getCount alternative)))
    (t/is (= "plain" (.getContent plain)))
    (t/is (= "<p>html</p>" (.getContent html)))
    (t/is (str/starts-with? (.getContentType html) "text/html"))))

(t/deftest message-with-partial-map-body-is-valid
  (let [msg (message {} {:to "one@example.com"
                         :subject "subject"
                         :body {"text/html" "<b>html</b>"}})
        alternative (.getContent (.getBodyPart (.getContent msg) 0))]
    (t/is (= 1 (.getCount alternative)))
    (t/is (= "<b>html</b>" (.getContent (.getBodyPart alternative 0))))))

(t/deftest message-rejects-invalid-body-type
  (t/is (thrown-with-msg? IllegalArgumentException #"invalid email body"
                          (message {} {:to "one@example.com"
                                       :subject "subject"
                                       :body 42}))))

(t/deftest message-adds-attachments-to-mixed-part
  (let [msg (message {} {:to "one@example.com"
                         :subject "subject"
                         :body "hello"
                         :attachments {"report.txt" "trace"}})
        mixed (.getContent msg)
        attachment (.getBodyPart mixed 1)]
    (t/is (= 2 (.getCount mixed)))
    (t/is (= "report.txt" (.getFileName attachment)))
    (t/is (= "trace" (.getContent attachment)))))

(t/deftest message-is-saved
  (let [msg (message {} {:to "one@example.com" :subject "subject" :body "hello"})]
    (t/testing "saveChanges assigns a message id"
      (t/is (seq (.getHeader msg "Message-ID"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SENDMAIL HANDLER WITHOUT NETWORK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest sendmail-handler-without-flags-does-not-send
  (let [handler (ig/init-key ::emails/sendmail {})]
    (t/is (nil? (handler nil {:to "one@example.com"
                              :subject "subject"
                              :body "hello"})))))

(t/deftest sendmail-handler-with-log-emails-dumps-message
  (let [dump    (atom nil)
        handler (ig/init-key ::emails/sendmail {})]
    (with-redefs [cf/flags (conj cf/flags :log-emails)
                  l/slf4j-log-handler (fn [_ _ _ record]
                                        (reset! dump (force (::l/message record))))]
      (handler nil {:id :test
                    :to "one@example.com"
                    :subject "hi"
                    :body "hello body"}))
    (t/is (str/includes? @dump "email console dump:"))
    (t/is (str/includes? @dump ":id :test"))
    (t/is (str/includes? @dump "hi"))
    (t/is (str/includes? @dump "hello body"))))

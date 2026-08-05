;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.bounce-handling-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.email :as email]
   [app.http.awsns :as awsns]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.data.json :as j]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- decode-row
  [{:keys [content] :as row}]
  (cond-> row
    (db/pgobject? content)
    (assoc :content (db/decode-transit-pgobject content))))

(defn bounce-report
  [{:keys [token email] :or {email "user@example.com"}}]
  {"notificationType" "Bounce",
   "bounce" {"feedbackId" "010701776d7dd251-c08d280d-9f47-41aa-b959-0094fec779d9-000000",
             "bounceType" "Permanent",
             "bounceSubType" "General",
             "bouncedRecipients" [{"emailAddress" email,
                                   "action" "failed",
                                   "status" "5.1.1",
                                   "diagnosticCode" "smtp; 550 5.1.1 user unknown"}]
             "timestamp" "2021-02-04T14:41:38.000Z",
             "remoteMtaIp" "22.22.22.22",
             "reportingMTA" "dsn; b224-13.smtp-out.eu-central-1.amazonses.com"}
   "mail" {"timestamp" "2021-02-04T14:41:37.020Z",
           "source" "no-reply@penpot.app",
           "sourceArn" "arn:aws:ses:eu-central-1:1111111111:identity/penpot.app",
           "sourceIp" "22.22.22.22",
           "sendingAccountId" "1111111111",
           "messageId" "010701776d7dccfc-3c0094e7-01d7-458d-8100-893320186028-000000",
           "destination" [email],
           "headersTruncated" false,
           "headers" [{"name" "Received","value" "from app-pre"},
                      {"name" "Date","value" "Thu, 4 Feb 2021 14:41:36 +0000 (UTC)"},
                      {"name" "From","value" "Penpot <no-reply@penpot.app>"},
                      {"name" "Reply-To","value" "Penpot <no-reply@penpot.app>"},
                      {"name" "To","value" email},
                      {"name" "Message-ID","value" "<2054501.5.1612449696846@penpot.app>"},
                      {"name" "Subject","value" "test"},
                      {"name" "MIME-Version","value" "1.0"},
                      {"name" "Content-Type","value" "multipart/mixed;  boundary=\"----=_Part_3_1150363050.1612449696845\""},
                      {"name" "X-Penpot-Data","value" token}],
           "commonHeaders" {"from" ["Penpot <no-reply@penpot.app>"],
                            "replyTo" ["Penpot <no-reply@penpot.app>"],
                            "date" "Thu, 4 Feb 2021 14:41:36 +0000 (UTC)",
                            "to" [email],
                            "messageId" "<2054501.5.1612449696846@penpot.app>",
                            "subject" "test"}}})


(defn complaint-report
  [{:keys [token email] :or {email "user@example.com"}}]
  {"notificationType" "Complaint",
   "complaint" {"feedbackId" "0107017771528618-dcf4d61f-c889-4c8b-a6ff-6f0b6553b837-000000",
                "complaintSubType" nil,
                "complainedRecipients" [{"emailAddress" email}],
                "timestamp" "2021-02-05T08:32:49.000Z",
                "userAgent" "Yahoo!-Mail-Feedback/2.0",
                "complaintFeedbackType" "abuse",
                "arrivalDate" "2021-02-05T08:31:15.000Z"},
   "mail" {"timestamp" "2021-02-05T08:31:13.715Z",
           "source" "no-reply@penpot.app",
           "sourceArn" "arn:aws:ses:eu-central-1:111111111:identity/penpot.app",
           "sourceIp" "22.22.22.22",
           "sendingAccountId" "11111111111",
           "messageId" "0107017771510f33-a0696d28-859c-4f08-9211-8392d1b5c226-000000",
           "destination" ["user@yahoo.com"],
           "headersTruncated" false,
           "headers" [{"name" "Received","value" "from smtp"},
                      {"name" "Date","value" "Fri, 5 Feb 2021 08:31:13 +0000 (UTC)"},
                      {"name" "From","value" "Penpot <no-reply@penpot.app>"},
                      {"name" "Reply-To","value" "Penpot <no-reply@penpot.app>"},
                      {"name" "To","value" email},
                      {"name" "Message-ID","value" "<1833063698.279.1612513873536@penpot.app>"},
                      {"name" "Subject","value" "Verify email."},
                      {"name" "MIME-Version","value" "1.0"},
                      {"name" "Content-Type","value" "multipart/mixed;  boundary=\"----=_Part_276_1174403980.1612513873535\""},
                      {"name" "X-Penpot-Data","value" token}],
           "commonHeaders" {"from" ["Penpot <no-reply@penpot.app>"],
                            "replyTo" ["Penpot <no-reply@penpot.app>"],
                            "date" "Fri, 5 Feb 2021 08:31:13 +0000 (UTC)",
                            "to" [email],
                            "messageId" "<1833063698.279.1612513873536@penpot.app>",
                            "subject" "Verify email."}}})

(t/deftest test-parse-bounce-report
  (let [profile (th/create-profile* 1)
        report  (bounce-report {:token (tokens/generate th/*system*
                                                        {:iss :profile-identity
                                                         :profile-id (:id profile)})})
        result  (#'awsns/parse-notification th/*system* report)]
    ;; (pprint result)

    (t/is (= "bounce" (:type result)))
    (t/is (= "permanent" (:kind result)))
    (t/is (= "general" (:category result)))
    (t/is (= ["user@example.com"] (mapv :email (:recipients result))))
    (t/is (= (:id profile) (:profile-id result)))))

(t/deftest test-parse-complaint-report
  (let [profile (th/create-profile* 1)
        report  (complaint-report {:token (tokens/generate th/*system*
                                                           {:iss :profile-identity
                                                            :profile-id (:id profile)})})
        result  (#'awsns/parse-notification th/*system* report)]
    ;; (pprint result)
    (t/is (= "complaint" (:type result)))
    (t/is (= "abuse" (:kind result)))
    (t/is (= nil (:category result)))
    (t/is (= ["user@example.com"] (into [] (:recipients result))))
    (t/is (= (:id profile) (:profile-id result)))))

(t/deftest test-parse-complaint-report-without-token
  (let [props   (:app.setup/props th/*system*)
        cfg     {:app.setup/props props}
        report  (complaint-report {:token ""})
        result  (#'awsns/parse-notification cfg report)]
    (t/is (= "complaint" (:type result)))
    (t/is (= "abuse" (:kind result)))
    (t/is (= nil (:category result)))
    (t/is (= ["user@example.com"] (into [] (:recipients result))))
    (t/is (= nil (:profile-id result)))))

(t/deftest test-process-bounce-report
  (let [profile (th/create-profile* 1)
        pool    (:app.db/pool th/*system*)
        report  (bounce-report {:token (tokens/generate th/*system*
                                                        {:iss :profile-identity
                                                         :profile-id (:id profile)})})
        report  (#'awsns/parse-notification th/*system* report)]

    (#'awsns/process-report th/*system* report)

    (let [rows (->> (db/query pool :profile-complaint-report {:profile-id (:id profile)})
                    (mapv decode-row))]
      (t/is (= 1 (count rows)))
      (t/is (= "bounce" (get-in rows [0 :type])))
      (t/is (= "2021-02-04T14:41:38.000Z" (get-in rows [0 :content :timestamp]))))

    (let [rows (->> (db/query pool :global-complaint-report :all)
                    (mapv decode-row))]
      (t/is (= 1 (count rows)))
      (t/is (= "bounce" (get-in rows [0 :type])))
      (t/is (= "user@example.com" (get-in rows [0 :email]))))

    (let [prof (db/get-by-id pool :profile (:id profile))]
      (t/is (false? (:is-muted prof))))))

(t/deftest test-process-complaint-report
  (let [profile (th/create-profile* 1)
        pool    (:app.db/pool th/*system*)
        report  (complaint-report {:token (tokens/generate th/*system*
                                                           {:iss :profile-identity
                                                            :profile-id (:id profile)})})
        report  (#'awsns/parse-notification th/*system* report)]

    (#'awsns/process-report th/*system* report)

    (let [rows (->> (db/query pool :profile-complaint-report {:profile-id (:id profile)})
                    (mapv decode-row))]
      (t/is (= 1 (count rows)))
      (t/is (= "complaint" (get-in rows [0 :type])))
      (t/is (= "2021-02-05T08:31:15.000Z" (get-in rows [0 :content :timestamp]))))


    (let [rows (->> (db/query pool :global-complaint-report :all)
                    (mapv decode-row))]
      (t/is (= 1 (count rows)))
      (t/is (= "complaint" (get-in rows [0 :type])))
      (t/is (= "user@example.com" (get-in rows [0 :email]))))


    (let [prof (db/get-by-id pool :profile (:id profile))]
      (t/is (false? (:is-muted prof))))))

(t/deftest test-process-bounce-report-to-self
  (let [profile (th/create-profile* 1)
        pool    (:app.db/pool th/*system*)
        report  (bounce-report {:email (:email profile)
                                :token (tokens/generate th/*system*
                                                        {:iss :profile-identity
                                                         :profile-id (:id profile)})})
        report  (#'awsns/parse-notification th/*system* report)]

    (#'awsns/process-report th/*system* report)

    (let [rows (db/query pool :profile-complaint-report {:profile-id (:id profile)})]
      (t/is (= 1 (count rows))))

    (let [rows (db/query pool :global-complaint-report :all)]
      (t/is (= 1 (count rows))))

    (let [prof (db/get-by-id pool :profile (:id profile))]
      (t/is (true? (:is-muted prof))))))

(t/deftest test-process-complaint-report-to-self
  (let [profile (th/create-profile* 1)
        pool    (:app.db/pool th/*system*)
        report  (complaint-report {:email (:email profile)
                                   :token (tokens/generate th/*system*
                                                           {:iss :profile-identity
                                                            :profile-id (:id profile)})})
        report  (#'awsns/parse-notification th/*system* report)]

    (#'awsns/process-report th/*system* report)

    (let [rows (db/query pool :profile-complaint-report {:profile-id (:id profile)})]
      (t/is (= 1 (count rows))))

    (let [rows (db/query pool :global-complaint-report :all)]
      (t/is (= 1 (count rows))))

    (let [prof (db/get-by-id pool :profile (:id profile))]
      (t/is (true? (:is-muted prof))))))

(t/deftest test-allow-send-messages-predicate-with-bounces
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:profile-bounce-threshold 3
                               :profile-complaint-threshold 2})}]

    (let [profile (th/create-profile* 1)
          pool    (:app.db/pool th/*system*)]
      (th/create-complaint-for pool {:type :bounce :id (:id profile) :created-at (ct/in-past {:days 8})})
      (th/create-complaint-for pool {:type :bounce :id (:id profile)})
      (th/create-complaint-for pool {:type :bounce :id (:id profile)})

      (t/is (true? (email/allow-send-emails? pool profile)))
      (t/is (= 4 (:call-count @mock)))

      (th/create-complaint-for pool {:type :bounce :id (:id profile)})
      (t/is (false? (email/allow-send-emails? pool profile))))))


(t/deftest test-allow-send-messages-predicate-with-complaints
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:profile-bounce-threshold 3
                               :profile-complaint-threshold 2})}]
    (let [profile (th/create-profile* 1)
          pool    (:app.db/pool th/*system*)]
      (th/create-complaint-for pool {:type :bounce :id (:id profile) :created-at (ct/in-past {:days 8})})
      (th/create-complaint-for pool {:type :bounce :id (:id profile) :created-at (ct/in-past {:days 8})})
      (th/create-complaint-for pool {:type :bounce :id (:id profile)})
      (th/create-complaint-for pool {:type :bounce :id (:id profile)})
      (th/create-complaint-for pool {:type :complaint :id (:id profile)})

      (t/is (true? (email/allow-send-emails? pool profile)))
      (t/is (= 4 (:call-count @mock)))

      (th/create-complaint-for pool {:type :complaint :id (:id profile)})
      (t/is (false? (email/allow-send-emails? pool profile))))))

(t/deftest test-has-complaint-reports-predicate
  (let [profile (th/create-profile* 1)
        pool    (:app.db/pool th/*system*)]

    (t/is (false? (email/has-complaint-reports? pool (:email profile))))

    (th/create-global-complaint-for pool {:type :bounce :email (:email profile)})
    (t/is (false? (email/has-complaint-reports? pool (:email profile))))

    (th/create-global-complaint-for pool {:type :complaint :email (:email profile)})
    (t/is (true? (email/has-complaint-reports? pool (:email profile))))))

(t/deftest test-has-bounce-reports-predicate
  (let [profile (th/create-profile* 1)
        pool    (:app.db/pool th/*system*)]

    (t/is (false? (email/has-bounce-reports? pool (:email profile))))

    (th/create-global-complaint-for pool {:type :complaint :email (:email profile)})
    (t/is (false? (email/has-bounce-reports? pool (:email profile))))

    (th/create-global-complaint-for pool {:type :bounce :email (:email profile)})
    (t/is (true? (email/has-bounce-reports? pool (:email profile))))))

(t/deftest test-validate-sns-url-rejects-s3-and-other-services
  ;; S3 buckets are attacker-controlled
  (t/is (false? (#'awsns/valid-sns-url? "https://my-bucket.s3.amazonaws.com/cert.pem")))
  (t/is (false? (#'awsns/valid-sns-url? "https://my-bucket.s3.eu-central-1.amazonaws.com/cert.pem")))
  ;; Other AWS services
  (t/is (false? (#'awsns/valid-sns-url? "https://lambda.amazonaws.com/cert.pem")))
  (t/is (false? (#'awsns/valid-sns-url? "https://ec2.amazonaws.com/cert.pem")))
  ;; Plain amazonaws.com without sns prefix
  (t/is (false? (#'awsns/valid-sns-url? "https://amazonaws.com/cert.pem"))))

(t/deftest test-validate-sns-url-accepts-only-sns-hosts
  ;; Valid SNS URLs with region
  (t/is (true? (#'awsns/valid-sns-url? "https://sns.eu-central-1.amazonaws.com/cert.pem")))
  (t/is (true? (#'awsns/valid-sns-url? "https://sns.us-east-1.amazonaws.com/cert.pem")))
  (t/is (true? (#'awsns/valid-sns-url? "https://sns.ap-southeast-1.amazonaws.com/cert.pem"))))

;; Helper to load test certificate and private key from resources
;; See: https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html
(defn- load-test-cert-and-key
  "Loads the test certificate and private key from test resources."
  []
  (let [cert-pem (slurp (clojure.java.io/resource "sns-test-cert.pem"))
        key-pem  (slurp (clojure.java.io/resource "sns-test-key.pem"))
        ;; Parse certificate
        cert-bytes (.getBytes (-> cert-pem
                                  (clojure.string/replace "-----BEGIN CERTIFICATE-----" "")
                                  (clojure.string/replace "-----END CERTIFICATE-----" "")
                                  (clojure.string/replace #"\s+" ""))
                              java.nio.charset.StandardCharsets/UTF_8)
        cert-input (java.io.ByteArrayInputStream. (.decode (java.util.Base64/getDecoder) cert-bytes))
        cf (java.security.cert.CertificateFactory/getInstance "X.509")
        cert (.generateCertificate cf cert-input)
        ;; Parse private key
        key-bytes (.getBytes (-> key-pem
                                 (clojure.string/replace "-----BEGIN PRIVATE KEY-----" "")
                                 (clojure.string/replace "-----END PRIVATE KEY-----" "")
                                 (clojure.string/replace #"\s+" ""))
                             java.nio.charset.StandardCharsets/UTF_8)
        key-spec (java.security.spec.PKCS8EncodedKeySpec. (.decode (java.util.Base64/getDecoder) key-bytes))
        kf (java.security.KeyFactory/getInstance "RSA")
        private-key (.generatePrivate kf key-spec)]
    {:cert cert
     :cert-bytes (.getEncoded cert)
     :private-key private-key
     :public-key (.getPublicKey cert)}))

(t/deftest test-verify-signature-end-to-end-v1
  (let [{:keys [cert-bytes private-key]} (load-test-cert-and-key)

        msg         {"Type"             "Notification"
                     "MessageId"        "test-msg-1"
                     "TopicArn"         "arn:aws:sns:us-east-1:123:topic"
                     "Message"          "test message"
                     "Timestamp"        "2021-02-04T14:41:37.020Z"
                     "SigningCertURL"   "https://sns.us-east-1.amazonaws.com/cert.pem"
                     "SignatureVersion" "1"}

        string-to-sign (#'awsns/build-string-to-sign msg)
        sig          (java.security.Signature/getInstance "SHA1withRSA")
        _            (.initSign sig private-key)
        _            (.update sig (.getBytes string-to-sign java.nio.charset.StandardCharsets/UTF_8))
        signature    (.encodeToString (java.util.Base64/getEncoder) (.sign sig))
        msg-with-sig (assoc msg "Signature" signature)]

    (with-redefs [awsns/fetch-certificate (fn [_ _]
                                            (java.io.ByteArrayInputStream. cert-bytes))]
      (t/is (true? (#'awsns/verify-signature {} msg-with-sig))))))

(t/deftest test-verify-signature-end-to-end-v2
  (let [{:keys [cert-bytes private-key]} (load-test-cert-and-key)

        msg         {"Type"             "Notification"
                     "MessageId"        "test-msg-2"
                     "TopicArn"         "arn:aws:sns:us-east-1:123:topic"
                     "Message"          "test message"
                     "Timestamp"        "2021-02-04T14:41:37.020Z"
                     "SigningCertURL"   "https://sns.us-east-1.amazonaws.com/cert.pem"
                     "SignatureVersion" "2"}

        string-to-sign (#'awsns/build-string-to-sign msg)
        sig          (java.security.Signature/getInstance "SHA256withRSA")
        _            (.initSign sig private-key)
        _            (.update sig (.getBytes string-to-sign java.nio.charset.StandardCharsets/UTF_8))
        signature    (.encodeToString (java.util.Base64/getEncoder) (.sign sig))
        msg-with-sig (assoc msg "Signature" signature)]

    (with-redefs [awsns/fetch-certificate (fn [_ _]
                                            (java.io.ByteArrayInputStream. cert-bytes))]
      (t/is (true? (#'awsns/verify-signature {} msg-with-sig))))))

(t/deftest test-verify-signature-end-to-end-subscription-confirmation
  (let [{:keys [cert-bytes private-key]} (load-test-cert-and-key)

        msg         {"Type"             "SubscriptionConfirmation"
                     "MessageId"        "test-msg-3"
                     "TopicArn"         "arn:aws:sns:us-east-1:123:topic"
                     "Message"          "You have chosen to subscribe"
                     "Timestamp"        "2021-02-04T14:41:37.020Z"
                     "Token"            "test-token-123"
                     "SubscribeURL"     "https://sns.us-east-1.amazonaws.com/confirm"
                     "SigningCertURL"   "https://sns.us-east-1.amazonaws.com/cert.pem"
                     "SignatureVersion" "1"}

        string-to-sign (#'awsns/build-string-to-sign msg)
        sig          (java.security.Signature/getInstance "SHA1withRSA")
        _            (.initSign sig private-key)
        _            (.update sig (.getBytes string-to-sign java.nio.charset.StandardCharsets/UTF_8))
        signature    (.encodeToString (java.util.Base64/getEncoder) (.sign sig))
        msg-with-sig (assoc msg "Signature" signature)]

    (with-redefs [awsns/fetch-certificate (fn [_ _]
                                            (java.io.ByteArrayInputStream. cert-bytes))]
      (t/is (true? (#'awsns/verify-signature {} msg-with-sig))))))

(t/deftest test-verify-signature-rejects-wrong-key
  (let [{:keys [cert-bytes]} (load-test-cert-and-key)
        ;; Generate a different keypair for signing
        keypair-gen (java.security.KeyPairGenerator/getInstance "RSA")
        _           (.initialize keypair-gen 2048)
        kp          (.generateKeyPair keypair-gen)
        wrong-private-key (.getPrivate kp)

        msg         {"Type"             "Notification"
                     "MessageId"        "test-msg-4"
                     "TopicArn"         "arn:aws:sns:us-east-1:123:topic"
                     "Message"          "test message"
                     "Timestamp"        "2021-02-04T14:41:37.020Z"
                     "SigningCertURL"   "https://sns.us-east-1.amazonaws.com/cert.pem"
                     "SignatureVersion" "1"}

        string-to-sign (#'awsns/build-string-to-sign msg)
        sig          (java.security.Signature/getInstance "SHA1withRSA")
        _            (.initSign sig wrong-private-key)
        _            (.update sig (.getBytes string-to-sign java.nio.charset.StandardCharsets/UTF_8))
        signature    (.encodeToString (java.util.Base64/getEncoder) (.sign sig))
        msg-with-sig (assoc msg "Signature" signature)]

    (with-redefs [awsns/fetch-certificate (fn [_ _]
                                            (java.io.ByteArrayInputStream. cert-bytes))]
      (t/is (false? (#'awsns/verify-signature {} msg-with-sig))))))

(t/deftest test-verify-signature-rejects-unsupported-version
  (let [msg {"Type" "Notification"
             "MessageId" "test-msg-3"
             "TopicArn" "arn:aws:sns:us-east-1:123:topic"
             "Message" "test message"
             "Timestamp" "2021-02-04T14:41:37.020Z"
             "SigningCertURL" "https://sns.us-east-1.amazonaws.com/cert.pem"
             "SignatureVersion" "3"
             "Signature" "fake=="}]

    (t/is (thrown? clojure.lang.ExceptionInfo
                   (#'awsns/verify-signature {} msg)))))

(t/deftest test-build-string-to-sign-v1-notification
  (let [msg {"Type"             "Notification"
             "MessageId"        "msg-123"
             "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
             "Message"          "{\"notificationType\":\"Bounce\"}"
             "Timestamp"        "2021-02-04T14:41:37.020Z"
             "SigningCertURL"   "https://sns.eu-central-1.amazonaws.com/cert.pem"
             "SignatureVersion" "1"
             "Signature"        "abc123=="}
        result (#'awsns/build-string-to-sign msg)]
    (t/is (string? result))
    (t/is (.contains result "MessageId"))
    (t/is (.contains result "msg-123"))
    (t/is (.contains result "TopicArn"))
    (t/is (.contains result "Message"))
    (t/is (.contains result "Timestamp"))
    ;; V1 does NOT include SigningCertURL, SignatureVersion, or Signature
    (t/is (not (.contains result "SigningCertURL")))
    (t/is (not (.contains result "SignatureVersion")))
    (t/is (not (.contains result "Signature")))))

(t/deftest test-build-string-to-sign-v2-notification
  (let [msg {"Type"             "Notification"
             "MessageId"        "msg-123"
             "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
             "Message"          "{\"notificationType\":\"Bounce\"}"
             "Timestamp"        "2021-02-04T14:41:37.020Z"
             "SigningCertURL"   "https://sns.eu-central-1.amazonaws.com/cert.pem"
             "SignatureVersion" "2"
             "Signature"        "abc123=="}
        result (#'awsns/build-string-to-sign msg)]
    (t/is (string? result))
    (t/is (.contains result "MessageId"))
    (t/is (.contains result "TopicArn"))
    ;; V2 uses the same fields as V1 (only hash algorithm differs: SHA1 vs SHA256)
    ;; SigningCertURL and SignatureVersion are metadata, not part of the signed content
    (t/is (not (.contains result "SigningCertURL")))
    (t/is (not (.contains result "SignatureVersion")))
    ;; Signature is never part of the string-to-sign
    (t/is (not (.contains result "Signature\n")))))

(t/deftest test-build-string-to-sign-subscription-confirmation
  (let [msg {"Type"             "SubscriptionConfirmation"
             "MessageId"        "msg-456"
             "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
             "Message"          "You have chosen to subscribe"
             "Timestamp"        "2021-02-04T14:41:37.020Z"
             "Token"            "test-token-123"
             "SigningCertURL"   "https://sns.eu-central-1.amazonaws.com/cert.pem"
             "SignatureVersion" "1"
             "Signature"        "xyz789=="
             "SubscribeURL"     "https://sns.eu-central-1.amazonaws.com/confirm"}
        result (#'awsns/build-string-to-sign msg)]
    (t/is (string? result))
    (t/is (.contains result "SubscribeURL"))
    (t/is (.contains result "https://sns.eu-central-1.amazonaws.com/confirm"))
    ;; Token must be included for SubscriptionConfirmation
    (t/is (.contains result "Token"))
    (t/is (.contains result "test-token-123"))))

(t/deftest test-handle-request-returns-4xx-for-invalid-signature
  (let [{:keys [cert-bytes]} (load-test-cert-and-key)
        body (j/write-str
              {"Type"             "Notification"
               "MessageId"        "msg-123"
               "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
               "Message"          "{\"test\":\"data\"}"
               "Timestamp"        "2021-02-04T14:41:37.020Z"
               "SigningCertURL"   "https://sns.eu-central-1.amazonaws.com/cert.pem"
               "SignatureVersion" "1"
               "Signature"        "invalid-signature=="})
        result (with-redefs [awsns/fetch-certificate (fn [_ _]
                                                       (java.io.ByteArrayInputStream. cert-bytes))]
                 (#'awsns/handle-request th/*system* body))]
    (t/is (= 400 (:status result)))))

(t/deftest test-handle-request-returns-4xx-for-invalid-url
  (let [body (j/write-str
              {"Type"             "Notification"
               "MessageId"        "msg-123"
               "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
               "Message"          "{\"test\":\"data\"}"
               "Timestamp"        "2021-02-04T14:41:37.020Z"
               "SigningCertURL"   "https://evil.com/cert.pem"
               "SignatureVersion" "1"
               "Signature"        "fake-signature=="})
        result (#'awsns/handle-request th/*system* body)]
    (t/is (= 400 (:status result)))))

(t/deftest test-handle-request-rejects-invalid-signing-cert-url
  (let [pool (:app.db/pool th/*system*)
        profile (th/create-profile* 1)
        token (tokens/generate th/*system*
                               {:iss :profile-identity
                                :profile-id (:id profile)})
        body (j/write-str
              {"Type"             "Notification"
               "MessageId"        "msg-123"
               "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
               "Message"          (j/write-str {"notificationType" "Bounce"
                                                "bounce" {"bounceType" "Permanent"
                                                          "bounceSubType" "General"
                                                          "bouncedRecipients" [{"emailAddress" "victim@example.com"}]
                                                          "timestamp" "2021-02-04T14:41:38.000Z"}
                                                "mail" {"source" "no-reply@penpot.app"
                                                        "destination" ["victim@example.com"]
                                                        "timestamp" "2021-02-04T14:41:37.020Z"
                                                        "headers" [{"name" "X-Penpot-Data" "value" token}]}})
               "Timestamp"        "2021-02-04T14:41:37.020Z"
               "SigningCertURL"   "https://evil.com/cert.pem"
               "SignatureVersion" "1"
               "Signature"        "fake-signature=="})]
    (#'awsns/handle-request th/*system* body)
    (let [reports (db/query pool :global-complaint-report :all)]
      (t/is (empty? reports)))))

(t/deftest test-handle-request-rejects-invalid-subscribe-url
  (let [pool (:app.db/pool th/*system*)
        body (j/write-str
              {"Type"             "SubscriptionConfirmation"
               "MessageId"        "msg-456"
               "TopicArn"         "arn:aws:sns:eu-central-1:123:topic"
               "Message"          "You have chosen to subscribe"
               "Timestamp"        "2021-02-04T14:41:37.020Z"
               "SigningCertURL"   "https://sns.eu-central-1.amazonaws.com/cert.pem"
               "SignatureVersion" "1"
               "Signature"        "fake-signature=="
               "SubscribeURL"     "http://attacker.com/confirm"})]
    (#'awsns/handle-request th/*system* body)
    (let [reports (db/query pool :global-complaint-report :all)]
      (t/is (empty? reports)))))

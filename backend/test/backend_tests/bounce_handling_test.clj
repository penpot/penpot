;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.bounce-handling-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as email]
   [app.http.awsns :as awsns]
   [app.http.client :as http]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
   [clojure.data.json :as j]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]])
  (:import
   java.io.ByteArrayInputStream
   java.nio.charset.StandardCharsets
   java.security.cert.Certificate
   java.security.cert.CertificateFactory
   java.security.KeyFactory
   java.security.KeyPairGenerator
   java.security.Signature
   java.security.spec.PKCS8EncodedKeySpec
   java.util.Base64))

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

;; See: https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html
(defn- load-test-cert-and-key
  "Loads the test certificate and private key from test resources."
  []
  (let [cert-pem    (slurp (io/resource "sns-test-cert.pem"))
        key-pem     (slurp (io/resource "sns-test-key.pem"))
        cf          (CertificateFactory/getInstance "X.509")
        cert        (.generateCertificate cf (ByteArrayInputStream. (.getBytes ^String cert-pem StandardCharsets/UTF_8)))
        key-bytes   (-> key-pem
                        (str/replace "-----BEGIN PRIVATE KEY-----" "")
                        (str/replace "-----END PRIVATE KEY-----" "")
                        (str/replace #"\s+" ""))
        key-spec    (PKCS8EncodedKeySpec. (.decode (Base64/getDecoder) ^String key-bytes))
        private-key (.generatePrivate (KeyFactory/getInstance "RSA") key-spec)]
    {:cert cert
     :private-key private-key}))

(def ^:private topic-arn "arn:aws:sns:eu-central-1:123:penpot-bounces")
(def ^:private cert-url "https://sns.eu-central-1.amazonaws.com/cert.pem")

(defn- sign-message
  "Adds a Signature to the message computed with the given private key."
  [private-key msg]
  (let [algorithm (if (= "2" (get msg "SignatureVersion")) "SHA256withRSA" "SHA1withRSA")
        sig       (Signature/getInstance algorithm)]
    (.initSign sig private-key)
    (.update sig (.getBytes ^String (#'awsns/build-string-to-sign msg) StandardCharsets/UTF_8))
    (assoc msg "Signature" (.encodeToString (Base64/getEncoder) (.sign sig)))))

(defn- notification
  [message & {:as attrs}]
  (merge {"Type"             "Notification"
          "MessageId"        "msg-123"
          "TopicArn"         topic-arn
          "Message"          message
          "Timestamp"        "2021-02-04T14:41:37.020Z"
          "SigningCertURL"   cert-url
          "SignatureVersion" "1"}
         attrs))

(defn- subscription-confirmation
  [& {:as attrs}]
  (merge {"Type"             "SubscriptionConfirmation"
          "MessageId"        "msg-456"
          "TopicArn"         topic-arn
          "Message"          "You have chosen to subscribe"
          "Timestamp"        "2021-02-04T14:41:37.020Z"
          "Token"            "test-token-123"
          "SubscribeURL"     "https://sns.eu-central-1.amazonaws.com/?Action=ConfirmSubscription"
          "SigningCertURL"   cert-url
          "SignatureVersion" "1"}
         attrs))

(defn- system-with-cert-cache
  []
  (assoc th/*system* ::awsns/cert-cache (#'awsns/create-cert-cache)))

(defn- handle-sns
  "Runs handle-request with the test topic allowed and the test
   certificate served for any SigningCertURL. Returns the result plus
   the number of certificate fetches and the outbound HTTP requests."
  [msg & {:keys [allowed-topics fetch-fn]
          :or   {allowed-topics #{topic-arn}}}]
  (let [{:keys [cert]} (load-test-cert-and-key)
        fetches        (atom 0)
        requests       (atom [])
        fetch-fn       (or fetch-fn (fn [_ _] cert))]
    (binding [cf/config (assoc cf/config :aws-sns-topic-arns allowed-topics)]
      (with-redefs [awsns/fetch-certificate (fn [cfg url]
                                              (swap! fetches inc)
                                              (fetch-fn cfg url))
                    http/req                (fn [_ request & _]
                                              (swap! requests conj request)
                                              {:status 200})]
        (let [result (#'awsns/handle-request (system-with-cert-cache) (j/write-str msg))]
          (assoc result :fetches @fetches :requests @requests))))))

(defn- global-reports
  []
  (db/query (:app.db/pool th/*system*) :global-complaint-report :all))

(t/deftest test-verify-signature-end-to-end-v1
  (let [{:keys [cert private-key]} (load-test-cert-and-key)
        msg (sign-message private-key (notification "test message"))]
    (with-redefs [awsns/fetch-certificate (constantly cert)]
      (t/is (true? (#'awsns/verify-signature (system-with-cert-cache) msg))))))

(t/deftest test-verify-signature-end-to-end-v2
  (let [{:keys [cert private-key]} (load-test-cert-and-key)
        msg (sign-message private-key (notification "test message" "SignatureVersion" "2"))]
    (with-redefs [awsns/fetch-certificate (constantly cert)]
      (t/is (true? (#'awsns/verify-signature (system-with-cert-cache) msg))))))

(t/deftest test-verify-signature-end-to-end-subscription-confirmation
  (let [{:keys [cert private-key]} (load-test-cert-and-key)
        msg (sign-message private-key (subscription-confirmation))]
    (with-redefs [awsns/fetch-certificate (constantly cert)]
      (t/is (true? (#'awsns/verify-signature (system-with-cert-cache) msg))))))

(t/deftest test-verify-signature-rejects-wrong-key
  (let [{:keys [cert]} (load-test-cert-and-key)
        keypair-gen (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
        wrong-key   (.getPrivate (.generateKeyPair keypair-gen))
        msg         (sign-message wrong-key (notification "test message"))]
    (with-redefs [awsns/fetch-certificate (constantly cert)]
      (t/is (false? (#'awsns/verify-signature (system-with-cert-cache) msg))))))

(t/deftest test-verify-signature-rejects-malformed-or-missing-signature
  (let [{:keys [cert]} (load-test-cert-and-key)]
    (with-redefs [awsns/fetch-certificate (constantly cert)]
      (t/is (false? (#'awsns/verify-signature (system-with-cert-cache)
                                              (notification "m" "Signature" "not base64 !!"))))
      (t/is (false? (#'awsns/verify-signature (system-with-cert-cache)
                                              (notification "m")))))))

(t/deftest test-verify-signature-rejects-unsupported-version
  (t/is (thrown? clojure.lang.ExceptionInfo
                 (#'awsns/verify-signature (system-with-cert-cache)
                                           (notification "m"
                                                         "SignatureVersion" "3"
                                                         "Signature" "fake==")))))

(t/deftest test-verify-signature-caches-certificate
  (let [{:keys [cert private-key]} (load-test-cert-and-key)
        fetches (atom 0)
        system  (system-with-cert-cache)
        msg     (sign-message private-key (notification "test message"))]
    (with-redefs [awsns/fetch-certificate (fn [_ _] (swap! fetches inc) cert)]
      (t/is (true? (#'awsns/verify-signature system msg)))
      (t/is (true? (#'awsns/verify-signature system msg)))
      (t/is (= 1 @fetches)))))

(t/deftest test-fetch-certificate-parses-certificate
  (let [pem (slurp (io/resource "sns-test-cert.pem"))]
    (with-redefs [http/req (fn [& _]
                             {:status 200
                              :body (ByteArrayInputStream. (.getBytes ^String pem StandardCharsets/UTF_8))})]
      (t/is (instance? Certificate (#'awsns/fetch-certificate th/*system* cert-url))))))

(t/deftest test-fetch-certificate-raises-and-closes-body-on-error-status
  (let [closed? (atom false)
        body    (proxy [ByteArrayInputStream] [(byte-array 0)]
                  (close [] (reset! closed? true)))]
    (with-redefs [http/req (fn [& _] {:status 503 :body body})]
      (let [error (try
                    (#'awsns/fetch-certificate th/*system* cert-url)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (t/is (= :cert-fetch-failed (:code (ex-data error))))
        (t/is (true? @closed?))))))

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

(t/deftest test-handle-request-processes-valid-bounce
  (let [profile           (th/create-profile* 1)
        {:keys [private-key]} (load-test-cert-and-key)
        token             (tokens/generate th/*system* {:iss :profile-identity
                                                        :profile-id (:id profile)})
        msg               (->> (notification (j/write-str (bounce-report {:token token})))
                               (sign-message private-key))
        result            (handle-sns msg)
        rows              (global-reports)]
    (t/is (= 200 (:status result)))
    (t/is (= 1 (count rows)))
    (t/is (= "user@example.com" (:email (first rows))))))

(t/deftest test-handle-request-rejects-signed-message-from-other-topic
  (let [profile           (th/create-profile* 1)
        {:keys [private-key]} (load-test-cert-and-key)
        token             (tokens/generate th/*system* {:iss :profile-identity
                                                        :profile-id (:id profile)})
        msg               (->> (notification (j/write-str (bounce-report {:token token}))
                                             "TopicArn" "arn:aws:sns:eu-central-1:999:attacker")
                               (sign-message private-key))
        result            (handle-sns msg)]
    (t/is (= 400 (:status result)))
    (t/is (zero? (:fetches result)))
    (t/is (empty? (global-reports)))))

(t/deftest test-handle-request-rejects-all-topics-when-none-configured
  (let [{:keys [private-key]} (load-test-cert-and-key)
        msg    (sign-message private-key (notification "{}"))
        result (handle-sns msg :allowed-topics nil)]
    (t/is (= 400 (:status result)))
    (t/is (zero? (:fetches result)))))

(t/deftest test-handle-request-confirms-subscription-from-allowed-topic
  (let [{:keys [private-key]} (load-test-cert-and-key)
        msg    (sign-message private-key (subscription-confirmation))
        result (handle-sns msg)]
    (t/is (= 200 (:status result)))
    (t/is (= [(get msg "SubscribeURL")] (mapv :uri (:requests result))))))

(t/deftest test-handle-request-ignores-subscription-from-other-topic
  (let [{:keys [private-key]} (load-test-cert-and-key)
        msg    (->> (subscription-confirmation "TopicArn" "arn:aws:sns:eu-central-1:999:attacker")
                    (sign-message private-key))
        result (handle-sns msg)]
    (t/is (= 400 (:status result)))
    (t/is (empty? (:requests result)))))

(t/deftest test-handle-request-rejects-invalid-subscribe-url
  (let [{:keys [private-key]} (load-test-cert-and-key)
        msg    (->> (subscription-confirmation "SubscribeURL" "http://attacker.com/confirm")
                    (sign-message private-key))
        result (handle-sns msg)]
    (t/is (= 400 (:status result)))
    (t/is (empty? (:requests result)))))

(t/deftest test-handle-request-returns-4xx-for-invalid-signature
  (let [result (handle-sns (notification "{\"test\":\"data\"}" "Signature" "invalid-signature=="))]
    (t/is (= 400 (:status result)))
    (t/is (= 1 (:fetches result)))))

(t/deftest test-handle-request-rejects-invalid-signing-cert-url
  (let [profile (th/create-profile* 1)
        token   (tokens/generate th/*system* {:iss :profile-identity
                                              :profile-id (:id profile)})
        msg     (notification (j/write-str (bounce-report {:token token :email "victim@example.com"}))
                              "SigningCertURL" "https://evil.com/cert.pem"
                              "Signature" "fake-signature==")
        result  (handle-sns msg)]
    (t/is (= 400 (:status result)))
    (t/is (zero? (:fetches result)))
    (t/is (empty? (global-reports)))))

(t/deftest test-handle-request-returns-4xx-for-missing-message
  (let [{:keys [private-key]} (load-test-cert-and-key)
        msg    (sign-message private-key (dissoc (notification "x") "Message"))
        result (handle-sns msg)]
    (t/is (= 400 (:status result)))))

(t/deftest test-handle-request-returns-5xx-when-certificate-fetch-fails
  (let [{:keys [private-key]} (load-test-cert-and-key)
        msg    (sign-message private-key (notification "{}"))]
    (t/is (= 500 (:status (handle-sns msg :fetch-fn (fn [_ _]
                                                      (ex/raise :type :internal
                                                                :code :cert-fetch-failed))))))
    (t/is (= 500 (:status (handle-sns msg :fetch-fn (fn [_ _]
                                                      (throw (java.net.http.HttpTimeoutException. "timeout")))))))))

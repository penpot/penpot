;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns backend-tests.awsns-signature-test
  (:require
   [app.http.awsns :as awsns]
   [clojure.java.io :as io]
   [clojure.test :as t])
  (:import
   java.security.cert.CertificateFactory))

(def ^:private notification
  {"Type" "Notification"
   "Message" "Hello SNS"
   "MessageId" "message-123"
   "Subject" "Example subject"
   "Timestamp" "2026-08-05T12:34:56.000Z"
   "TopicArn" "arn:aws:sns:us-east-1:123456789012:example"
   "SigningCertURL" "https://sns.us-east-1.amazonaws.com/cert.pem"})

(def ^:private notification-string
  (str "Message\nHello SNS\n"
       "MessageId\nmessage-123\n"
       "Subject\nExample subject\n"
       "Timestamp\n2026-08-05T12:34:56.000Z\n"
       "TopicArn\narn:aws:sns:us-east-1:123456789012:example\n"
       "Type\nNotification\n"))

(def ^:private subscription
  {"Type" "SubscriptionConfirmation"
   "Message" "Confirm subscription"
   "MessageId" "subscription-123"
   "SubscribeURL" "https://sns.us-east-1.amazonaws.com/confirm"
   "Timestamp" "2026-08-05T12:34:56.000Z"
   "Token" "token-123"
   "TopicArn" "arn:aws:sns:us-east-1:123456789012:example"})

(t/deftest exact-signing-strings
  (doseq [version ["1" "2"]]
    (t/testing (str "Signature version " version)
      (t/is (= notification-string
               (#'awsns/build-string-to-sign
                (assoc notification "SignatureVersion" version))))
      (t/is (= (str "Message\nHello SNS\n"
                    "MessageId\nmessage-123\n"
                    "Timestamp\n2026-08-05T12:34:56.000Z\n"
                    "TopicArn\narn:aws:sns:us-east-1:123456789012:example\n"
                    "Type\nNotification\n")
               (#'awsns/build-string-to-sign
                (assoc (dissoc notification "Subject") "SignatureVersion" version))))
      (t/is (= (str "Message\nConfirm subscription\n"
                    "MessageId\nsubscription-123\n"
                    "SubscribeURL\nhttps://sns.us-east-1.amazonaws.com/confirm\n"
                    "Timestamp\n2026-08-05T12:34:56.000Z\n"
                    "Token\ntoken-123\n"
                    "TopicArn\narn:aws:sns:us-east-1:123456789012:example\n"
                    "Type\nSubscriptionConfirmation\n")
               (#'awsns/build-string-to-sign
                (assoc subscription "SignatureVersion" version)))))))

;; Reproduce from backend/: write notification-string's literal bytes to a file
;; (including the final newline), then run for sha1 and sha256 respectively:
;; openssl dgst -sha1 -sign test/resources/sns-test-key.pem vector.txt | openssl base64 -A
;; openssl dgst -sha256 -sign test/resources/sns-test-key.pem vector.txt | openssl base64 -A
(def ^:private signatures
  {"1" "ojH2OVkiNWhhAchpy/GB0Bv2eapJHGzC3xZlwFxRJpWQLlc7prZMP6xni2dDPsxDw6+V6ycPZH1H8i6IlUIEqWuxjvcxku2etlNdqUX8QEQSbzB/gvdHO4IU4bu2F3Ji3culMBnxi6jnNjiXCSVAckslv4wRaS2hPXqtpfk3pyMpTaA2XeoSxib+SUhFtl4KFNHcGNFNIOlW8BtMn6Lf1Yh0dvU2jg1iO+Yu90Zla6ECoI5uXo5JcBs5KViUK0wayVGOmglvOdwiVh4l62ms5lJkInvEMBUAsHkUxLe/2FIMI8SvkvztKOd/oEAsNH9EfYNw+cSFfYCSpzKYxG0eNA=="
   "2" "rmUlc1/hWz2wnEF8ASuXIkHrpuXJ4qEbimGdWyyc5dJF7kwYA2HWn1B5ZW++L4eBQuap9Eeqae7EMGfLiI5exyAUp3a7+8wnEonaSknOdizJeNGP37sCUPZaubvBocyxxVJSCcCkZg+eucqjnua/GYlV/f9EKbRkjNm+DamTlmQfM2YhY9zwARWC+AUOxZIggxI4//KPYat8i0P0uoj6C2UcxI7zvjyer6/aKcss170apF2xDWd+SlpV3Ope5zpl8ST5h1mUgTwmwdqVecZAoWjcCWam83QbAq3BPgugw7KWYIbw1qXM/3CK/yBtCNvy8TvoC/fo16LG9f+LaAvtTA=="})

(t/deftest independently-signed-notification
  (let [cert (with-open [input (io/input-stream (io/resource "sns-test-cert.pem"))]
               (.generateCertificate (CertificateFactory/getInstance "X.509") input))
        cfg  {::awsns/cert-cache (#'awsns/create-cert-cache)}]
    (with-redefs [awsns/fetch-certificate (constantly cert)]
      (doseq [[version signature] signatures]
        (t/testing (str "Signature version " version)
          (let [message (assoc notification
                               "SignatureVersion" version
                               "Signature" signature)]
            (t/is (true? (#'awsns/verify-signature cfg message)))
            (t/is (false? (#'awsns/verify-signature cfg (assoc message "Message" "Tampered"))))))))))

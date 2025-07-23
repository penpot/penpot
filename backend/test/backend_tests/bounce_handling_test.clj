;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.bounce-handling-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.email :as email]
   [app.http.awsns :as awsns]
   [app.tokens :as tokens]
   [backend-tests.helpers :as th]
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
        props   (:app.setup/props th/*system*)
        cfg     {:app.setup/props props}
        report  (bounce-report {:token (tokens/generate props
                                                        {:iss :profile-identity
                                                         :profile-id (:id profile)})})
        result  (#'awsns/parse-notification cfg report)]
    ;; (pprint result)

    (t/is (= "bounce" (:type result)))
    (t/is (= "permanent" (:kind result)))
    (t/is (= "general" (:category result)))
    (t/is (= ["user@example.com"] (mapv :email (:recipients result))))
    (t/is (= (:id profile) (:profile-id result)))))

(t/deftest test-parse-complaint-report
  (let [profile (th/create-profile* 1)
        props   (:app.setup/props th/*system*)
        cfg     {:app.setup/props props}
        report  (complaint-report {:token (tokens/generate props
                                                           {:iss :profile-identity
                                                            :profile-id (:id profile)})})
        result  (#'awsns/parse-notification cfg report)]
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
        props   (:app.setup/props th/*system*)
        pool    (:app.db/pool th/*system*)
        cfg     {:app.setup/props props :app.db/pool pool}
        report  (bounce-report {:token (tokens/generate props
                                                        {:iss :profile-identity
                                                         :profile-id (:id profile)})})
        report  (#'awsns/parse-notification cfg report)]

    (#'awsns/process-report cfg report)

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
        props   (:app.setup/props th/*system*)
        pool    (:app.db/pool th/*system*)
        cfg     {:app.setup/props props
                 :app.db/pool pool}
        report  (complaint-report {:token (tokens/generate props
                                                           {:iss :profile-identity
                                                            :profile-id (:id profile)})})
        report  (#'awsns/parse-notification cfg report)]

    (#'awsns/process-report cfg report)

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
        props   (:app.setup/props th/*system*)
        pool    (:app.db/pool th/*system*)
        cfg     {:app.setup/props props :app.db/pool pool}
        report  (bounce-report {:email (:email profile)
                                :token (tokens/generate props
                                                        {:iss :profile-identity
                                                         :profile-id (:id profile)})})
        report  (#'awsns/parse-notification cfg report)]

    (#'awsns/process-report cfg report)

    (let [rows (db/query pool :profile-complaint-report {:profile-id (:id profile)})]
      (t/is (= 1 (count rows))))

    (let [rows (db/query pool :global-complaint-report :all)]
      (t/is (= 1 (count rows))))

    (let [prof (db/get-by-id pool :profile (:id profile))]
      (t/is (true? (:is-muted prof))))))

(t/deftest test-process-complaint-report-to-self
  (let [profile (th/create-profile* 1)
        props   (:app.setup/props th/*system*)
        pool    (:app.db/pool th/*system*)
        cfg     {:app.setup/props props :app.db/pool pool}
        report  (complaint-report {:email (:email profile)
                                   :token (tokens/generate props
                                                           {:iss :profile-identity
                                                            :profile-id (:id profile)})})
        report  (#'awsns/parse-notification cfg report)]

    (#'awsns/process-report cfg report)

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

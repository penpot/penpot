;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.awsns
  "AWS SNS webhook handler for bounces."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.http.client :as http]
   [app.main :as-alias main]
   [app.setup :as-alias setup]
   [app.tokens :as tokens]
   [app.worker :as-alias wrk]
   [clojure.data.json :as j]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [yetti.request :as yreq]
   [yetti.response :as-alias yres]))

(declare parse-json)
(declare handle-request)
(declare parse-notification)
(declare process-report)

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (http/client? (::http/client params)) "expect a valid http client")
  (assert (sm/valid? ::setup/props (::setup/props params)) "expected valid setup props")
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(defmethod ig/init-key ::routes
  [_ cfg]
  (letfn [(handler [request]
            (let [data (-> request yreq/body slurp)]
              (px/run! :vthread (partial handle-request cfg data)))
            {::yres/status 200})]
    ["/sns" {:handler handler
             :allowed-methods #{:post}}]))

(defn handle-request
  [cfg data]
  (try
    (let [body  (parse-json data)
          mtype (get body "Type")]
      (cond
        (= mtype "SubscriptionConfirmation")
        (let [surl   (get body "SubscribeURL")
              stopic (get body "TopicArn")]
          (l/info :action "subscription received" :topic stopic :url surl)
          (http/req! cfg {:uri surl :method :post :timeout 10000} {:sync? true}))

        (= mtype "Notification")
        (when-let [message (parse-json (get body "Message"))]
          (let [notification (parse-notification cfg message)]
            (process-report cfg notification)))

        :else
        (l/warn :hint "unexpected data received"
                :report (pr-str body))))

    (catch Throwable cause
      (l/error :hint "unexpected exception on awsns"
               :cause cause))))

(defn- parse-bounce
  [data]
  {:type        "bounce"
   :kind        (str/lower (get data "bounceType"))
   :category    (str/lower (get data "bounceSubType"))
   :feedback-id (get data "feedbackId")
   :timestamp   (get data "timestamp")
   :recipients  (->> (get data "bouncedRecipients")
                     (mapv (fn [item]
                             {:email  (str/lower (get item "emailAddress"))
                              :status (get item "status")
                              :action (get item "action")
                              :dcode  (get item "diagnosticCode")})))})

(defn- parse-complaint
  [data]
  {:type          "complaint"
   :user-agent    (get data "userAgent")
   :kind          (get data "complaintFeedbackType")
   :category      (get data "complaintSubType")
   :timestamp     (get data "arrivalDate")
   :feedback-id   (get data "feedbackId")
   :recipients    (->> (get data "complainedRecipients")
                       (mapv #(get % "emailAddress"))
                       (mapv str/lower))})

(defn- extract-headers
  [mail]
  (reduce (fn [acc item]
            (let [key (get item "name")
                  val (get item "value")]
              (assoc acc (str/lower key) val)))
          {}
          (get mail "headers")))

(defn- extract-identity
  [cfg headers]
  (let [tdata (get headers "x-penpot-data")]
    (when-not (str/empty? tdata)
      (let [result (tokens/verify (::setup/props cfg) {:token tdata :iss :profile-identity})]
        (:profile-id result)))))

(defn- parse-notification
  [cfg message]
  (let [type (get message "notificationType")
        data (case type
               "Bounce" (parse-bounce (get message "bounce"))
               "Complaint" (parse-complaint (get message "complaint"))
               {:type (keyword (str/lower type))
                :message message})]
    (when data
      (let [mail (get message "mail")]
        (when-not mail
          (ex/raise :type :internal
                    :code :incomplete-notification
                    :hint "no email data received, please enable full headers report"))
        (let [headers (extract-headers mail)
              mail    {:destination (get mail "destination")
                       :source      (get mail "source")
                       :timestamp   (get mail "timestamp")
                       :subject     (get-in mail ["commonHeaders" "subject"])
                       :headers     headers}]
          (assoc data
                 :mail mail
                 :profile-id (extract-identity cfg headers)))))))

(defn- parse-json
  [v]
  (try
    (j/read-str v)
    (catch Throwable cause
      (l/wrn :hint "unable to decode request body"
             :cause cause))))

(defn- register-bounce-for-profile
  [{:keys [::db/pool]} {:keys [type kind profile-id] :as report}]
  (when (= kind "permanent")
    (try
      (db/insert! pool :profile-complaint-report
                  {:profile-id profile-id
                   :type (name type)
                   :content (db/tjson report)})

      (catch Throwable cause
        (l/warn :hint "unable to persist profile complaint"
                :cause cause)))

    (doseq [recipient (:recipients report)]
      (db/insert! pool :global-complaint-report
                  {:email (:email recipient)
                   :type (name type)
                   :content (db/tjson report)}))

    (let [profile (db/exec-one! pool (sql/select :profile {:id profile-id}))]
      (when (some #(= (:email profile) (:email %)) (:recipients report))
        ;; If the report matches the profile email, this means that
        ;; the report is for itself, can be caused when a user
        ;; registers with an invalid email or the user email is
        ;; permanently rejecting receiving the email. In this case we
        ;; have no option to mark the user as muted (and in this case
        ;; the profile will be also inactive.

        (l/inf :hint "mark profile: muted"
               :profile-id (str (:id profile))
               :email (:email profile)
               :reason "bounce report"
               :report-id (:feedback-id report))

        (db/update! pool :profile
                    {:is-muted true}
                    {:id profile-id}
                    {::db/return-keys false})))))

(defn- register-complaint-for-profile
  [{:keys [::db/pool]} {:keys [type profile-id] :as report}]

  (try
    (db/insert! pool :profile-complaint-report
                {:profile-id profile-id
                 :type (name type)
                 :content (db/tjson report)})
    (catch Throwable cause
      (l/warn :hint "unable to persist profile complaint"
              :cause cause)))

  ;; TODO: maybe also try to find profiles by email and if exists
  ;; register profile reports for them?
  (doseq [email (:recipients report)]
    (db/insert! pool :global-complaint-report
                {:email email
                 :type (name type)
                 :content (db/tjson report)}))

  (let [profile (db/exec-one! pool (sql/select :profile {:id profile-id}))]
    (when (some #(= % (:email profile)) (:recipients report))
      ;; If the report matches the profile email, this means that
      ;; the report is for itself, rare case but can happen; In this
      ;; case just mark profile as muted (very rare case).
      (l/inf :hint "mark profile: muted"
             :profile-id (str (:id profile))
             :email (:email profile)
             :reason "complaint report"
             :report-id (:feedback-id report))

      (db/update! pool :profile
                  {:is-muted true}
                  {:id profile-id}
                  {::db/return-keys false}))))

(defn- process-report
  [cfg {:keys [type profile-id] :as report}]
  (cond
    ;; In this case we receive a bounce/complaint notification without
    ;; confirmed identity, we just emit a warning but do nothing about
    ;; it because this is not a normal case. All notifications should
    ;; come with profile identity.
    (nil? profile-id)
    (l/wrn :hint "not-identified report"
           ::l/body (pp/pprint-str report {:length 40 :level 6}))

    (= "bounce" type)
    (do
      (l/trc :hint "bounce report"
             ::l/body (pp/pprint-str report {:length 40 :level 6}))
      (register-bounce-for-profile cfg report))

    (= "complaint" type)
    (do
      (l/trc :hint "complaint report"
             ::l/body (pp/pprint-str report {:length 40 :level 6}))
      (register-complaint-for-profile cfg report))

    :else
    (l/wrn :hint "unrecognized report"
           ::l/body (pp/pprint-str report {:length 20 :level 4}))))

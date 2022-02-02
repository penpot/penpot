;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.awsns
  "AWS SNS webhook handler for bounces."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.util.http :as http]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [jsonista.core :as j]))

(declare parse-json)
(declare parse-notification)
(declare process-report)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [request]
    (let [body  (parse-json (slurp (:body request)))
          mtype (get body "Type")]
      (cond
        (= mtype "SubscriptionConfirmation")
        (let [surl   (get body "SubscribeURL")
              stopic (get body "TopicArn")]
          (l/info :action "subscription received" :topic stopic :url surl)
          (http/send! {:uri surl :method :post :timeout 10000}))

        (= mtype "Notification")
        (when-let [message (parse-json (get body "Message"))]
          (let [notification (parse-notification cfg message)]
            (process-report cfg notification)))

        :else
        (l/warn :hint "unexpected data received"
                :report (pr-str body)))
      {:status 200 :body ""})))

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
  [{:keys [tokens] :as cfg} headers]
  (let [tdata (get headers "x-penpot-data")]
    (when-not (str/empty? tdata)
      (let [result (tokens :verify {:token tdata :iss :profile-identity})]
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
  (ex/ignoring
   (j/read-value v)))

(defn- register-bounce-for-profile
  [{:keys [pool]} {:keys [type kind profile-id] :as report}]
  (when (= kind "permanent")
    (db/with-atomic [conn pool]
      (db/insert! conn :profile-complaint-report
                  {:profile-id profile-id
                   :type (name type)
                   :content (db/tjson report)})

      ;; TODO: maybe also try to find profiles by mail and if exists
      ;; register profile reports for them?
      (doseq [recipient (:recipients report)]
        (db/insert! conn :global-complaint-report
                    {:email (:email recipient)
                     :type (name type)
                     :content (db/tjson report)}))

      (let [profile (db/exec-one! conn (sql/select :profile {:id profile-id}))]
        (when (some #(= (:email profile) (:email %)) (:recipients report))
          ;; If the report matches the profile email, this means that
          ;; the report is for itself, can be caused when a user
          ;; registers with an invalid email or the user email is
          ;; permanently rejecting receiving the email. In this case we
          ;; have no option to mark the user as muted (and in this case
          ;; the profile will be also inactive.
          (db/update! conn :profile
                      {:is-muted true}
                      {:id profile-id}))))))

(defn- register-complaint-for-profile
  [{:keys [pool]} {:keys [type profile-id] :as report}]
  (db/with-atomic [conn pool]
    (db/insert! conn :profile-complaint-report
                {:profile-id profile-id
                 :type (name type)
                 :content (db/tjson report)})

    ;; TODO: maybe also try to find profiles by email and if exists
    ;; register profile reports for them?
    (doseq [email (:recipients report)]
      (db/insert! conn :global-complaint-report
                  {:email email
                   :type (name type)
                   :content (db/tjson report)}))

    (let [profile (db/exec-one! conn (sql/select :profile {:id profile-id}))]
      (when (some #(= % (:email profile)) (:recipients report))
        ;; If the report matches the profile email, this means that
        ;; the report is for itself, rare case but can happen; In this
        ;; case just mark profile as muted (very rare case).
        (db/update! conn :profile
                    {:is-muted true}
                    {:id profile-id})))))

(defn- process-report
  [cfg {:keys [type profile-id] :as report}]
  (l/trace :action "processing report" :report (pr-str report))
  (cond
    ;; In this case we receive a bounce/complaint notification without
    ;; confirmed identity, we just emit a warning but do nothing about
    ;; it because this is not a normal case. All notifications should
    ;; come with profile identity.
    (nil? profile-id)
    (l/warn :msg "a notification without identity received from AWS"
            :report (pr-str report))

    (= "bounce" type)
    (register-bounce-for-profile cfg report)

    (= "complaint" type)
    (register-complaint-for-profile cfg report)

    :else
    (l/warn :msg "unrecognized report received from AWS"
            :report (pr-str report))))



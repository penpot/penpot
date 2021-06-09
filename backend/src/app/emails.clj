;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.emails
  "Main api for send emails."
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.util.emails :as emails]
   [app.util.logging :as l]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

;; --- PUBLIC API

(defn render
  [email-factory context]
  (email-factory context))

(defn send!
  "Schedule the email for sending."
  [{:keys [::conn ::factory] :as context}]
  (us/verify fn? factory)
  (us/verify some? conn)
  (let [email (factory context)]
    (wrk/submit! (assoc email
                        ::wrk/task :sendmail
                        ::wrk/delay 0
                        ::wrk/max-retries 1
                        ::wrk/priority 200
                        ::wrk/conn conn))))


;; --- BOUNCE/COMPLAINS HANDLING

(def sql:profile-complaint-report
  "select (select count(*)
             from profile_complaint_report
            where type = 'complaint'
              and profile_id = ?
              and created_at > now() - ?::interval) as complaints,
          (select count(*)
             from profile_complaint_report
            where type = 'bounce'
              and profile_id = ?
              and created_at > now() - ?::interval) as bounces;")

(defn allow-send-emails?
  [conn profile]
  (when-not (:is-muted profile false)
    (let [complaint-threshold (cfg/get :profile-complaint-threshold)
          complaint-max-age   (cfg/get :profile-complaint-max-age)
          bounce-threshold    (cfg/get :profile-bounce-threshold)
          bounce-max-age      (cfg/get :profile-bounce-max-age)

          {:keys [complaints bounces] :as result}
          (db/exec-one! conn [sql:profile-complaint-report
                              (:id profile)
                              (db/interval complaint-max-age)
                              (:id profile)
                              (db/interval bounce-max-age)])]

      (and (< complaints complaint-threshold)
           (< bounces bounce-threshold)))))

(defn has-complaint-reports?
  ([conn email] (has-complaint-reports? conn email nil))
  ([conn email {:keys [threshold] :or {threshold 1}}]
   (let [reports (db/exec! conn (sql/select :global-complaint-report
                                            {:email email :type "complaint"}
                                            {:limit 10}))]
     (>= (count reports) threshold))))

(defn has-bounce-reports?
  ([conn email] (has-bounce-reports? conn email nil))
  ([conn email {:keys [threshold] :or {threshold 1}}]
   (let [reports (db/exec! conn (sql/select :global-complaint-report
                                            {:email email :type "bounce"}
                                            {:limit 10}))]
     (>= (count reports) threshold))))


;; --- EMAIL FACTORIES

(s/def ::subject ::us/string)
(s/def ::content ::us/string)

(s/def ::feedback
  (s/keys :req-un [::subject ::content]))

(def feedback
  "A profile feedback email."
  (emails/template-factory ::feedback))

(s/def ::name ::us/string)
(s/def ::register
  (s/keys :req-un [::name]))

(def register
  "A new profile registration welcome email."
  (emails/template-factory ::register))

(s/def ::token ::us/string)
(s/def ::password-recovery
  (s/keys :req-un [::name ::token]))

(def password-recovery
  "A password recovery notification email."
  (emails/template-factory ::password-recovery))

(s/def ::pending-email ::us/email)
(s/def ::change-email
  (s/keys :req-un [::name ::pending-email ::token]))

(def change-email
  "Password change confirmation email"
  (emails/template-factory ::change-email))

(s/def :internal.emails.invite-to-team/invited-by ::us/string)
(s/def :internal.emails.invite-to-team/team ::us/string)
(s/def :internal.emails.invite-to-team/token ::us/string)

(s/def ::invite-to-team
  (s/keys :req-un [:internal.emails.invite-to-team/invited-by
                   :internal.emails.invite-to-team/token
                   :internal.emails.invite-to-team/team]))

(def invite-to-team
  "Teams member invitation email."
  (emails/template-factory ::invite-to-team))


;; --- SENDMAIL TASK

(declare send-console!)

(s/def ::username ::cfg/smtp-username)
(s/def ::password ::cfg/smtp-password)
(s/def ::tls ::cfg/smtp-tls)
(s/def ::ssl ::cfg/smtp-ssl)
(s/def ::host ::cfg/smtp-host)
(s/def ::port ::cfg/smtp-port)
(s/def ::default-reply-to ::cfg/smtp-default-reply-to)
(s/def ::default-from ::cfg/smtp-default-from)
(s/def ::enabled ::cfg/smtp-enabled)

(defmethod ig/pre-init-spec ::sendmail-handler [_]
  (s/keys :req-un [::enabled]
          :opt-un [::username
                   ::password
                   ::tls
                   ::ssl
                   ::host
                   ::port
                   ::default-from
                   ::default-reply-to]))

(defmethod ig/init-key ::sendmail-handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (if (:enabled cfg)
      (emails/send! cfg props)
      (send-console! cfg props))))

(defn- send-console!
  [cfg email]
  (let [baos (java.io.ByteArrayOutputStream.)
        mesg (emails/smtp-message cfg email)]
    (.writeTo mesg baos)
    (let [out (with-out-str
                (println "email console dump:")
                (println "******** start email" (:id email) "**********")
                (println (.toString baos))
                (println "******** end email "(:id email) "**********"))]
      (l/info :email out))))


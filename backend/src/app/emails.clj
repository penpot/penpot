;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.emails
  "Main api for send emails."
  (:require
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.tasks :as tasks]
   [app.util.emails :as emails]
   [clojure.spec.alpha :as s]))

;; --- Defaults

(defn default-context
  []
  {:assets-uri (:assets-uri cfg/config)
   :public-uri (:public-uri cfg/config)})

;; --- Public API

(defn render
  [email-factory context]
  (email-factory context))

(defn send!
  "Schedule the email for sending."
  [conn email-factory context]
  (us/verify fn? email-factory)
  (us/verify map? context)
  (let [email (email-factory context)]
    (tasks/submit! conn {:name "sendmail"
                         :delay 0
                         :max-retries 1
                         :priority 200
                         :props email})))


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


;; --- Emails

(s/def ::subject ::us/string)
(s/def ::content ::us/string)

(s/def ::feedback
  (s/keys :req-un [::subject ::content]))

(def feedback
  "A profile feedback email."
  (emails/template-factory ::feedback default-context))

(s/def ::name ::us/string)
(s/def ::register
  (s/keys :req-un [::name]))

(def register
  "A new profile registration welcome email."
  (emails/template-factory ::register default-context))

(s/def ::token ::us/string)
(s/def ::password-recovery
  (s/keys :req-un [::name ::token]))

(def password-recovery
  "A password recovery notification email."
  (emails/template-factory ::password-recovery default-context))

(s/def ::pending-email ::us/email)
(s/def ::change-email
  (s/keys :req-un [::name ::pending-email ::token]))

(def change-email
  "Password change confirmation email"
  (emails/template-factory ::change-email default-context))

(s/def :internal.emails.invite-to-team/invited-by ::us/string)
(s/def :internal.emails.invite-to-team/team ::us/string)
(s/def :internal.emails.invite-to-team/token ::us/string)

(s/def ::invite-to-team
  (s/keys :keys [:internal.emails.invite-to-team/invited-by
                 :internal.emails.invite-to-team/token
                 :internal.emails.invite-to-team/team]))

(def invite-to-team
  "Teams member invitation email."
  (emails/template-factory ::invite-to-team default-context))

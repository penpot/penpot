;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tasks.sendmail
  (:require
   [clojure.data.json :as json]
   [clojure.tools.logging :as log]
   [postal.core :as postal]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]
   [uxbox.metrics :as mtx]
   [uxbox.util.http :as http]))

(defmulti sendmail (fn [config email] (:sendmail-backend config)))

(defmethod sendmail "console"
  [config email]
  (let [out (with-out-str
              (println "email console dump:")
              (println "******** start email" (:id email) "**********")
              (println " from: " (:from email))
              (println " to: " (:to email "---"))
              (println " reply-to: " (:reply-to email))
              (println " subject: " (:subject email))
              (println " content:")
              (doseq [item (:content email)]
                (when (= (:type item) "text/plain")
                  (println (:value item))))
              (println "******** end email "(:id email) "**********"))]
    (log/info out)))

(defmethod sendmail "sendgrid"
  [config email]
  (let [apikey  (:sendmail-backend-apikey config)
        dest    (mapv #(array-map :email %) (:to email))
        params  {:personalizations [{:to dest
                                     :subject (:subject email)}]
                 :from {:email (:from email)}
                 :reply_to {:email (:reply-to email)}
                 :content (:content email)}
        headers {"Authorization" (str "Bearer " apikey)
                 "Content-Type" "application/json"}
        body    (json/write-str params)]


    (try
      (let [response (http/send! {:method :post
                                  :headers headers
                                  :uri "https://api.sendgrid.com/v3/mail/send"
                                  :body body})]
        (when-not (= 202 (:status response))
          (log/error "Unexpected status from sendgrid:" (pr-str response))))
      (catch Throwable error
        (log/error "Error on sending email to sendgrid:" (pr-str error))))))

(defn- get-smtp-config
  [config]
  {:host (:smtp-host config)
   :port (:smtp-port config)
   :user (:smtp-user config)
   :pass (:smtp-password config)
   :ssl (:smtp-ssl config)
   :tls (:smtp-tls config)})

(defn- email->postal
  [email]
  {:from (:from email)
   :to (:to email)
   :subject (:subject email)
   :body (d/concat [:alternative]
                   (map (fn [{:keys [type value]}]
                          {:type (str type "; charset=utf-8")
                           :content value})
                        (:content email)))})

(defmethod sendmail "smtp"
  [config email]
  (let [config (get-smtp-config config)
        email  (email->postal email)
        result (postal/send-message config email)]
    (when (not= (:error result) :SUCCESS)
      (ex/raise :type :sendmail-error
                :code :email-not-sent
                :context result))))

(defn handler
  {:uxbox.tasks/name "sendmail"}
  [{:keys [props] :as task}]
  (sendmail cfg/config props))

(mtx/instrument-with-summary!
 {:var #'handler
  :id "tasks__sendmail"
  :help "Timing of sendmail task."})

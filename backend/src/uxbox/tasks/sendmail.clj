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
   [promesa.core :as p]
   [uxbox.config :as cfg]
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
    (-> (http/send! {:method :post
                     :headers headers
                     :uri "https://api.sendgrid.com/v3/mail/send"
                     :body body})
        (p/handle (fn [response error]
                    (cond
                      error
                      (log/error "Error on sending email to sendgrid:" (pr-str error))

                      (= 202 (:status response))
                      nil

                      :else
                      (log/error "Unexpected status from sendgrid:" (pr-str response))))))))

(defn handler
  {:uxbox.tasks/name "sendmail"}
  [{:keys [props] :as task}]
  (sendmail cfg/config props))


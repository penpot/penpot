;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.jobs.sendmail
  "Email sending jobs."
  (:require
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [uxbox.core :refer [system]]
   [postal.core :as postal]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.util.jobs :as uj]
   [uxbox.util.blob :as blob]
   [uxbox.util.exceptions :as ex]
   [mount.core :as mount :refer [defstate]]))

;; TODO: implement low priority sending emails.

(defn- decode-email-row
  [{:keys [data] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data)))))

(defn- fetch-emails
  [conn]
  (let [sql "select eq.* from email_queue as eq
              where eq.status = 'pending'
                and eq.priority = 10
                and eq.deleted_at is null
              order by eq.priority desc,
                       eq.created_at desc;"]
    (-> (db/query conn sql)
        (p/then (partial mapv decode-email-row)))))

(defn- fetch-failed-emails
  [conn]
  (let [sql "select eq.* from email_queue as eq
              where eq.status = 'failed'
                and eq.deleted_at is null
                and eq.retries < $1
              order by eq.priority desc,
                       eq.created_at desc;"]
    (-> (db/query conn sql)
        (p/then (partial mapv decode-email-row)))))

(defn- mark-email-as-sent
  [conn id]
  (let [sql "update email_queue
                set status = 'ok'
              where id = $1
                and deleted_at is null;"]
    (-> (db/query-one conn [sql id])
        (p/then (constantly nil)))))

(defn- mark-email-as-failed
  [conn id]
  (let [sql "update email_queue
                set status = 'failed',
                    retries = retries + 1
              where id = $1
                and deleted_at is null;"]
    (-> (db/query-one conn [sql id])
        (p/then (constantly nil)))))

(defn- get-smtp-config
  [config]
  {:host (:smtp-host config)
   :port (:smtp-port config)
   :user (:smtp-user config)
   :pass (:smtp-password config)
   :ssl (:smtp-ssl config)
   :tls (:smtp-tls config)
   :noop (not (:smtp-enabled config))})

(defn- send-email-to-console
  [email]
  (let [out (with-out-str
              (println "email console dump:")
              (println "******** start email" (:id email) "**********")
              (println " from: " (:from email))
              (println " to: " (:to email "---"))
              (println " reply-to: " (:reply-to email))
              (println " subject: " (:subject email))
              (println " content:")
              (doseq [item (rest (:body email))]
                (when (str/starts-with? (:type item) "text/plain")
                  (println (:content item))))
              (println "******** end email "(:id email) "**********"))]
    (log/info out)
    {:error :SUCCESS}))

(defn impl-sendmail
  [email]
  (p/future
    (let [config (get-smtp-config cfg/config)
          result (if (:noop config)
                   (send-email-to-console email)
                   (postal/send-message config email))]
      (when (not= (:error result) :SUCCESS)
        (ex/raise :type :sendmail-error
                  :code :email-not-sent
                  :context result))
      nil)))

(defn send-email
  [conn {:keys [id data] :as entry}]
  (-> (impl-sendmail data)
      (p/then (fn [_]
                (mark-email-as-sent conn id)))
      (p/catch (fn [e]
                 (log/error e "Error on sending email" id)
                 (mark-email-as-failed conn id)))))

;; --- Main Task Functions

(defn send-emails
  [opts]
  (db/with-atomic [conn db/pool]
    (p/let [items (fetch-emails conn)]
      (p/run! (partial send-email conn) items))))

(defn send-failed-emails
  [opts]
  (db/with-atomic [conn db/pool]
    (p/let [items (fetch-failed-emails conn)]
      (p/run! (partial send-email conn) items))))

(defstate sendmail-task
  :start (uj/schedule! system #'send-emails {::uj/interval (* 10 1000)})) ;; 20s

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.scheduled-jobs.emails
  "Email sending async tasks."
  (:require [clojure.tools.logging :as log]
            [suricatta.core :as sc]
            [postal.core :as postal]
            [uxbox.db :as db]
            [uxbox.config :as cfg]
            [uxbox.sql :as sql]
            [uxbox.util.quartz :as qtz]
            [uxbox.util.blob :as blob]
            [uxbox.util.transit :as t]
            [uxbox.util.data :as data]))

;; --- Impl details

(defn- decode-email-data
  [{:keys [data] :as result}]
  (merge result (when data
                  {:data (-> data blob/decode t/decode)})))

(defn- fetch-pending-emails
  [conn]
  (let [sqlv (sql/get-pending-emails)]
    (->> (sc/fetch conn sqlv)
         (map data/normalize-attrs)
         (map decode-email-data))))

(defn- fetch-immediate-emails
  [conn]
  (let [sqlv (sql/get-immediate-emails)]
    (->> (sc/fetch conn sqlv)
         (map data/normalize-attrs)
         (map decode-email-data))))

(defn- fetch-failed-emails
  [conn]
  (let [sqlv (sql/get-pending-emails)]
    (->> (sc/fetch conn sqlv)
         (map data/normalize-attrs)
         (map decode-email-data))))

(defn- mark-email-as-sent
  [conn id]
  (let [sqlv (sql/mark-email-as-sent {:id id})]
    (sc/execute conn sqlv)))

(defn- mark-email-as-failed
  [conn id]
  (let [sqlv (sql/mark-email-as-failed {:id id})]
    (sc/execute conn sqlv)))

(defn- send-email-to-console
  [{:keys [id data] :as entry}]
  (println "******** start email:" id "**********")
  (println (->> (:body data)
                (filter #(= (:uxbox.emails.core/type %) :text/plain))
                (first)
                (:content)))
  (println "********** end email:" id "**********")
  {:error :SUCCESS})

(defn- get-smtp-config
  [config]
  {:host (:smtp-host config)
   :port (:smtp-port config)
   :user (:smtp-user config)
   :pass (:smtp-password config)
   :ssl (:smtp-ssl config)
   :tls (:smtp-tls config)
   :noop (not (:smtp-enabled config))})

(defn- send-email
  [{:keys [id data] :as entry}]
  (let [config (get-smtp-config cfg/config)
        result (if (:noop config)
                 (send-email-to-console entry)
                 (postal/send-message config data))]
    (if (= (:error result) :SUCCESS)
      (log/debug "Message" id "sent successfully.")
      (log/warn "Message" id "failed with:" (:message result)))
    (if (= (:error result) :SUCCESS)
      true
      false)))

(defn- send-emails
  [conn entries]
  (loop [entries entries]
    (if-let [entry (first entries)]
      (do (if (send-email entry)
            (mark-email-as-sent conn (:id entry))
            (mark-email-as-failed conn (:id entry)))
          (recur (rest entries))))))

;; --- Jobs

(defn send-immediate-emails
  {::qtz/interval (* 60 1 1000) ;; every 1min
   ::qtz/repeat? true
   ::qtz/job true}
  []
  (log/info "task-send-immediate-emails...")
  (with-open [conn (db/connection)]
    (sc/atomic conn
      (->> (fetch-immediate-emails conn)
           (send-emails conn)))))

(defn send-pending-emails
  {::qtz/interval (* 60 5 1000) ;; every 5min
   ::qtz/repeat? true
   ::qtz/job true}
  []
  (with-open [conn (db/connection)]
    (sc/atomic conn
      (->> (fetch-pending-emails conn)
           (send-emails conn)))))

(defn send-failed-emails
  "Job that resends failed to send messages."
  {::qtz/interval (* 60 5 1000) ;; every 5min
   ::qtz/repeat? true
   ::qtz/job true}
  []
  (log/info "task-send-failed-emails...")
  (with-open [conn (db/connection)]
    (sc/atomic conn
      (->> (fetch-failed-emails conn)
           (send-emails conn)))))

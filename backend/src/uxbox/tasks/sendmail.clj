;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.sendmail
  "Email sending jobs."
  (:require
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [postal.core :as postal]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.config :as cfg]
   [uxbox.core :refer [system]]
   [uxbox.util.blob :as blob]))

(defn- get-smtp-config
  [config]
  {:host (:smtp-host config)
   :port (:smtp-port config)
   :user (:smtp-user config)
   :pass (:smtp-password config)
   :ssl (:smtp-ssl config)
   :tls (:smtp-tls config)
   :enabled (:smtp-enabled config)})

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

(defn send-email
  [email]
  (p/future
    (let [config (get-smtp-config cfg/config)
          result (if (:enabled config)
                   (postal/send-message config email)
                   (send-email-to-console email))]
      (when (not= (:error result) :SUCCESS)
        (ex/raise :type :sendmail-error
                  :code :email-not-sent
                  :context result))
      nil)))

(defn handler
  {:uxbox.tasks/name "sendmail"}
  [{:keys [props] :as task}]
  (send-email props))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.emails
  "Main api for send emails."
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.tasks :as tasks]
   [uxbox.util.emails :as emails]))

;; --- Defaults

(defn default-context
  []
  {:assets-uri (:assets-uri cfg/config)
   :public-uri (:public-uri cfg/config)})

;; --- Public API

(defn render
  [email context]
  (let [defaults {:from (:sendmail-from cfg/config)
                  :reply-to (:sendmail-reply-to cfg/config)}]
    (email (merge defaults context))))

(defn send!
  "Schedule the email for sending."
  ([email context] (send! db/pool email context))
  ([conn email-factory context]
   (us/verify fn? email-factory)
   (us/verify map? context)
   (let [defaults {:from (:sendmail-from cfg/config)
                   :reply-to (:sendmail-reply-to cfg/config)}
         data (merge defaults context)
         email (email-factory data)]
     (tasks/submit! conn {:name "sendmail"
                          :delay 0
                          :props email}))))

;; --- Emails

(s/def ::name ::us/string)
(s/def ::register
  (s/keys :req-un [::name]))

(def register
  "A new profile registration welcome email."
  (emails/build ::register default-context))

(s/def ::token ::us/string)
(s/def ::password-recovery
  (s/keys :req-un [::name ::token]))

(def password-recovery
  "A password recovery notification email."
  (emails/build ::password-recovery default-context))

(s/def ::pending-email ::us/email)
(s/def ::change-email
  (s/keys :req-un [::name ::pending-email ::token]))

(def change-email
  "Password change confirmation email"
  (emails/build ::change-email default-context))

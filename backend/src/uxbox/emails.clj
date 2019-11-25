;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.emails
  "Main api for send emails."
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.emails :as emails]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

(def default-context
  {:static media/resolve-asset
   :comment (constantly nil)})

(def register
  "A new profile registration welcome email."
  (emails/build :register default-context))

(defn render
  [email context]
  (let [defaults {:from (:email-from cfg/config)
                  :reply-to (:email-reply-to cfg/config)}]
    (email (merge defaults context))))

(defn send!
  "Schedule the email for sending."
  [email context]
  (s/assert fn? email)
  (s/assert map? context)
  (let [defaults {:from (:email-from cfg/config)
                  :reply-to (:email-reply-to cfg/config)}
        data (->> (merge defaults context)
                  (email)
                  (blob/encode))
        priority (case (:priority context :high) :low 1 :high 10)
        sql "insert into email_queue (data, priority)
             values ($1, $2) returning *"]
    (-> (db/query-one db/pool [sql data priority])
        (p/then' (constantly nil)))))

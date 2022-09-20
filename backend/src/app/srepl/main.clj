;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.main
  "A collection of adhoc fixes scripts."
  #_:clj-kondo/ignore
  (:require
   [app.common.logging :as l]
   [app.common.pprint :as p]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.commands.auth :as cmd.auth]
   [app.rpc.queries.profile :as profile]
   [app.srepl.fixes :as f]
   [app.srepl.helpers :as h]
   [app.util.time :as dt]
   [clojure.pprint :refer [pprint]]
   [cuerdas.core :as str]))

(defn print-available-tasks
  [system]
  (let [tasks (:app.worker/registry system)]
    (p/pprint (keys tasks) :level 200)))

(defn run-task!
  ([system name]
   (run-task! system name {}))
  ([system name params]
   (let [tasks (:app.worker/registry system)]
     (if-let [task-fn (get tasks name)]
       (task-fn params)
       (println (format "no task '%s' found" name))))))

(defn send-test-email!
  [system destination]
  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (us/verify!
   :expr (string? destination)
   :hint "destination should be provided")

  (let [handler (:app.emails/sendmail system)]
    (handler {:body "test email"
              :subject "test email"
              :to [destination]})))

(defn resend-email-verification-email!
  [system email]
  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (let [sprops  (:app.setup/props system)
        pool    (:app.db/pool system)
        profile (profile/retrieve-profile-data-by-email pool email)]

    (cmd.auth/send-email-verification! pool sprops profile)
    :email-sent))

(defn update-profile
  "Update a limited set of profile attrs."
  [system & {:keys [email id active? deleted?]}]

  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (us/verify!
   :expr (or (string? email) (uuid? id))
   :hint "email or id should be provided")

  (let [pool   (:app.db/pool system)
        params (cond-> {}
                 (true? active?) (assoc :is-active true)
                 (false? active?) (assoc :is-active false)
                 (true? deleted?) (assoc :deleted-at (dt/now)))
        opts   (cond-> {}
                 (some? email) (assoc :email (str/lower email))
                 (some? id)    (assoc :id id))]

    (some-> (db/update! pool :profile params opts)
            (profile/decode-profile-row))))


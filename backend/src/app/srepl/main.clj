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
  [system & {:keys [email id active? deleted? blocked?]}]

  (us/verify!
   :expr (some? system)
   :hint "system should be provided")

  (us/verify!
   :expr (or (string? email) (uuid? id))
   :hint "email or id should be provided")

  (let [params (cond-> {}
                 (true? active?) (assoc :is-active true)
                 (false? active?) (assoc :is-active false)
                 (true? deleted?) (assoc :deleted-at (dt/now))
                 (true? blocked?) (assoc :is-blocked true)
                 (false? blocked?) (assoc :is-blocked false))
        opts   (cond-> {}
                 (some? email) (assoc :email (str/lower email))
                 (some? id)    (assoc :id id))]

    (db/with-atomic [conn (:app.db/pool system)]
      (some-> (db/update! conn :profile params opts)
              (profile/decode-profile-row)))))

(defn mark-profile-as-blocked!
  "Mark the profile blocked and removes all the http sessiones
  associated with the profile-id."
  [system email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (db/get-by-params conn :profile
                                         {:email (str/lower email)}
                                         {:columns [:id :email]
                                          :check-not-found false})]
      (when-not (:is-blocked profile)
        (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
        (db/delete! conn :http-session {:profile-id (:id profile)})
        :blocked))))

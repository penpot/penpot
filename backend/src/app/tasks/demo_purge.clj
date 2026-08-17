;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.tasks.demo-purge
  "Task handler for delayed demo profile deletion. Submitted at demo
   creation time with a delay matching the configured deletion-delay."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props]}]
    (let [profile-id (get props :profile-id)
          now        (ct/now)]

      (l/trc :hint "demo-purge" :profile-id (str profile-id))

      ;; Mark the profile for immediate deletion
      (db/tx-run! cfg
                  (fn [{:keys [::db/conn] :as cfg}]
                    (db/update! conn :profile
                                {:deleted-at now}
                                {:id profile-id}
                                {::db/return-keys false})
                    (wrk/submit!
                     (-> cfg
                         (assoc ::wrk/task :delete-object)
                         (assoc ::wrk/params {:object :profile
                                              :deleted-at now
                                              :id profile-id}))))))))

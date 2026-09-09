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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(def schema:demo-purge-params
  [:map
   [:profile-id ::sm/uuid]])
(declare execute-demo-purge!)

(defmethod ig/assert-key ::demo-purge-job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/init-key ::demo-purge-job-def
  [_ cfg]
  {::jobs/name      :demo-purge
   ::jobs/schema    schema:demo-purge-params
   ::jobs/handler   (partial execute-demo-purge! cfg)
   ::jobs/decoder   (sm/decoder schema:demo-purge-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:demo-purge-params)})

(defn execute-demo-purge!
  "Plain job handler: mark the demo profile as deleted and submit the
  corresponding delete-object job."
  [cfg params]
  (let [profile-id (:profile-id params)
        now        (ct/now)]
    (l/trc :hint "demo-purge" :profile-id (str profile-id))

    (db/tx-run! cfg
                (fn [{:keys [::db/conn] :as cfg}]
                  (db/update! conn :profile
                              {:deleted-at now}
                              {:id profile-id}
                              {::db/return-keys false})
                  (jobs/submit! cfg
                                {::jobs/name :delete-object
                                 ::jobs/params {:object :profile
                                                :deleted-at now
                                                :id profile-id}})))))

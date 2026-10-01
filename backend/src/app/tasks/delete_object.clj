;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.delete-object
  "A generic task for object deletion cascade handling"
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.object-cascade :as cascade]
   [app.jobs :as jobs]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.storage :as sto]
   [integrant.core :as ig]))

(defn- absorb-library
  [cfg id]
  (db/tx-run! cfg files/absorb-library id))

(defmulti delete-object
  (fn [_ props] (:object props)))

(defmethod delete-object :snapshot
  [{:keys [::db/conn] :as cfg} {:keys [id file-id deleted-at]}]
  (l/trc :obj "snapshot" :id (str id) :file-id (str file-id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :file-change
              {:deleted-at deleted-at}
              {:id id :file-id file-id}
              {::db/return-keys false})

  (db/update! conn :file-data
              {:deleted-at deleted-at}
              {:id id :file-id file-id :type "snapshot"}
              {::db/return-keys false}))

(defmethod delete-object :file
  [cfg {:keys [id deleted-at]}]
  (cascade/update-cascade cfg :file id
                          {:deleted-at deleted-at
                           :absorb-file! absorb-library}))

(defmethod delete-object :project
  [cfg {:keys [id deleted-at]}]
  (cascade/update-cascade cfg :project id {:deleted-at deleted-at}))

(defmethod delete-object :team
  [cfg {:keys [id deleted-at]}]
  (cascade/update-cascade cfg :team id {:deleted-at deleted-at}))

(defmethod delete-object :profile
  [{:keys [::db/conn] :as cfg} {:keys [id deleted-at]}]
  (l/trc :obj "profile" :id (str id)
         :deleted-at (ct/format-inst deleted-at))

  (db/update! conn :profile
              {:deleted-at deleted-at}
              {:id id}
              {::db/return-keys false})

  (doseq [team (profile/get-owned-teams conn id)]
    (jobs/heartbeat cfg)
    (delete-object cfg (assoc team
                              :object :team
                              :deleted-at deleted-at))))

(defmethod delete-object :default
  [_cfg props]
  (l/wrn :obj (:object props) :hint "not implementation found"))

(def schema:delete-object-params
  [:map
   [:object [:enum :snapshot :team :project :profile :file]]
   [:deleted-at ::ct/inst]
   [:id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]])

(defn execute-delete-object
  "Plain job handler: run the delete-object multimethod on the provided
  params inside a single transaction."
  [cfg params]
  (db/tx-run! cfg delete-object params))

(defmethod ig/assert-key ::job-def
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :delete-object
   ::jobs/schema    schema:delete-object-params
   ::jobs/handler
   (fn [_context params]
     (execute-delete-object cfg params))
   ::jobs/decoder   (sm/decoder schema:delete-object-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:delete-object-params)})

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.delete-object
  "A generic task for object deletion cascade handling"
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.rpc.commands.files :as files]
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
  (cascade/run-cascade cfg :file id
                       {:deleted-at deleted-at
                        :absorb-file! absorb-library}))

(defmethod delete-object :project
  [cfg {:keys [id deleted-at]}]
  (cascade/run-cascade cfg :project id {:deleted-at deleted-at}))

(defmethod delete-object :team
  [cfg {:keys [id deleted-at]}]
  (cascade/run-cascade cfg :team id {:deleted-at deleted-at}))

(defmethod delete-object :profile
  [cfg {:keys [id deleted-at]}]
  (cascade/run-cascade cfg :profile id {:deleted-at deleted-at}))

(defmethod delete-object :default
  [_cfg props]
  (l/wrn :obj (:object props) :hint "not implementation found"))

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage params)) "expected valid storage to be provided"))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [{:keys [props] :as task}]
    (db/tx-run! cfg delete-object props)))

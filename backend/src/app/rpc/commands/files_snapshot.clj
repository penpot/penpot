;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-snapshot
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.file-snapshots :as fsnap]
   [app.main :as-alias main]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]))

(def ^:private schema:get-file-snapshots
  [:map {:title "get-file-snapshots"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-snapshots
  {::doc/added "1.20"
   ::sm/params schema:get-file-snapshots}
  [cfg {:keys [::rpc/profile-id file-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-read-permissions! conn profile-id file-id)
                 (fsnap/get-visible-snapshots conn file-id))))

(def ^:private schema:create-file-snapshot
  [:map
   [:file-id ::sm/uuid]
   [:label {:optional true} :string]])

(sv/defmethod ::create-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:create-file-snapshot
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id label]}]
  (files/check-edition-permissions! conn profile-id file-id)
  (let [file    (bfc/get-file cfg file-id :realize? true)
        project (db/get-by-id cfg :project (:project-id file))]

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/project-id (:project-id file))
        (assoc ::quotes/team-id (:team-id project))
        (assoc ::quotes/file-id (:id file))
        (quotes/check! {::quotes/id ::quotes/snapshots-per-file}
                       {::quotes/id ::quotes/snapshots-per-team}))

    (fsnap/create! cfg file
                   {:label label
                    :profile-id profile-id
                    :created-by "user"})))

(def ^:private schema:restore-file-snapshot
  [:map {:title "restore-file-snapshot"}
   [:file-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::restore-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:restore-file-snapshot
   ::db/transaction true}
  [{:keys [::db/conn ::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id file-id id] :as params}]
  (files/check-edition-permissions! conn profile-id file-id)
  (let [file (bfc/get-file cfg file-id)]
    (fsnap/create! cfg file
                   {:profile-id profile-id
                    :created-by "system"})
    (let [vern (fsnap/restore! cfg file-id id)]
      ;; Send to the clients a notification to reload the file
      (mbus/pub! msgbus
                 :topic (:id file)
                 :message {:type :file-restore
                           :file-id (:id file)
                           :vern vern})
      nil)))

(def ^:private schema:update-file-snapshot
  [:map {:title "update-file-snapshot"}
   [:id ::sm/uuid]
   [:label ::sm/text]])

(sv/defmethod ::update-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:update-file-snapshot
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id label]}]
  (let [snapshot (fsnap/get-minimal-snapshot conn id)]
    (files/check-edition-permissions! conn profile-id (:file-id snapshot))
    (fsnap/update! conn (assoc snapshot :label label))))

(def ^:private schema:remove-file-snapshot
  [:map {:title "remove-file-snapshot"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:remove-file-snapshot
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id]}]
  (let [snapshot (fsnap/get-minimal-snapshot conn id)]
    (files/check-edition-permissions! conn profile-id (:file-id snapshot))

    (when (not= (:created-by snapshot) "user")
      (ex/raise :type :validation
                :code :system-snapshots-cant-be-deleted
                :file-id (:file-id snapshot)
                :snapshot-id id
                :profile-id profile-id))

    (fsnap/delete! conn snapshot)))

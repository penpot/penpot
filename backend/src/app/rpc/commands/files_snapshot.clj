;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-snapshot
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.features :as-alias cfeat]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.main :as-alias main]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
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

;; --- COMMAND QUERY: get-file-snapshot

(def ^:private schema:get-file-snapshot
  [:map {:title "get-file-snapshot"}
   [:file-id ::sm/uuid]
   [:id ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-file-snapshot
  "Retrieve a file bundle with data from a specific snapshot for
  read-only preview. Does not modify any database state."
  {::doc/added "2.16"
   ::sm/params schema:get-file-snapshot
   ::sm/result files/schema:file-with-permissions
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id id] :as params}]
  (let [perms (bfc/get-file-permissions conn profile-id file-id)]
    (files/check-read-permissions! perms)
    (let [snapshot (fsnap/get-snapshot cfg file-id id)]
      (when-not snapshot
        (ex/raise :type :not-found
                  :code :snapshot-not-found
                  :hint "unable to find snapshot with the provided id"
                  :snapshot-id id
                  :file-id file-id))
      ;; Load current file metadata only (no data decoding) then overlay
      ;; the snapshot data so the client receives the same shape as a
      ;; normal get-file response but with historical page/object content.
      (let [base-file (bfc/get-file cfg file-id :load-data? false)]
        (-> base-file
            (assoc :data (:data snapshot))
            (assoc :version (:version snapshot))
            (assoc :features (:features snapshot))
            (assoc :revn (:revn snapshot))
            (assoc :vern (rand-int 100000))
            (assoc :permissions perms))))))

(def ^:private schema:create-file-snapshot
  [:map
   [:file-id ::sm/uuid]
   [:label {:optional true} :string]])

(sv/defmethod ::create-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:create-file-snapshot
   ::climit/id [[:create-file-snapshot/by-profile ::rpc/profile-id]
                [:create-file-snapshot/global]]}
  [cfg {:keys [::rpc/profile-id file-id label]}]
  (files/check-edition-permissions! cfg profile-id file-id)
  (let [file    (bfc/get-file cfg file-id :realize? true)
        project (db/get-by-id cfg :project (:project-id file))]

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/project-id (:project-id file))
        (assoc ::quotes/team-id (:team-id project))
        (assoc ::quotes/file-id (:id file))
        (quotes/check! {::quotes/id ::quotes/snapshots-per-file}
                       {::quotes/id ::quotes/snapshots-per-team}))

    (db/tx-run! cfg fsnap/create! file
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
   ::climit/id [[:restore-file-snapshot/by-profile ::rpc/profile-id]
                [:restore-file-snapshot/global]]}
  [{:keys [::db/pool ::mbus/msgbus] :as cfg} {:keys [::rpc/profile-id ::rpc/session-id file-id id] :as params}]

  ;; Check permissions and read current file state (short-lived, outside restore transaction)
  (files/check-edition-permissions! pool profile-id file-id)
  (let [file  (bfc/get-file cfg file-id)
        team  (teams/get-team pool
                              :profile-id profile-id
                              :file-id file-id)
        delay (ldel/get-deletion-delay team)
        file-revn (:revn file)]

    ;; Create backup snapshot of the current state (committed immediately
    ;; independently of the restore outcome)
    (fsnap/create! cfg file
                   {:profile-id profile-id
                    :deleted-at (ct/in-future delay)
                    :created-by "system"})

    ;; Restore snapshot inside its own transaction; the revn check
    ;; ensures no data is lost if the file was edited concurrently
    (db/tx-run! cfg
                (fn [{:keys [::db/conn] :as cfg}]
                  (let [current (bfc/get-minimal-file conn file-id {::db/for-update true})]
                    (when (not= (:revn current) file-revn)
                      (ex/raise :type :conflict
                                :code :file-modified
                                :hint "the file was modified during the restore process, please retry")))
                  (let [vern (fsnap/restore! cfg file-id id)]
                    (mbus/pub! msgbus
                               :topic (:id file)
                               :message {:type :file-restored
                                         :session-id session-id
                                         :file-id (:id file)
                                         :vern vern})
                    nil)))))

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

    (when (and (some? (:locked-by snapshot))
               (not= (:locked-by snapshot) profile-id))
      (ex/raise :type :validation
                :code :snapshot-is-locked
                :file-id (:file-id snapshot)
                :snapshot-id id
                :profile-id profile-id))

    (let [team  (teams/get-team conn
                                :profile-id profile-id
                                :file-id (:file-id snapshot))
          delay (ldel/get-deletion-delay team)]
      (fsnap/delete! conn (assoc snapshot :deleted-at (ct/in-future delay))))))

;;; Lock/unlock version endpoints

(def ^:private schema:lock-file-snapshot
  [:map {:title "lock-file-snapshot"}
   [:id ::sm/uuid]])

(sv/defmethod ::lock-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:lock-file-snapshot
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id]}]
  (let [snapshot (fsnap/get-minimal-snapshot conn id)]
    (files/check-edition-permissions! conn profile-id (:file-id snapshot))

    (when (not= (:created-by snapshot) "user")
      (ex/raise :type :validation
                :code :system-snapshots-cant-be-locked
                :hint "Only user-created versions can be locked"
                :snapshot-id id
                :profile-id profile-id))

    ;; Only the creator can lock their own version
    (when (not= (:profile-id snapshot) profile-id)
      (ex/raise :type :validation
                :code :only-creator-can-lock
                :hint "Only the version creator can lock it"
                :snapshot-id id
                :profile-id profile-id
                :creator-id (:profile-id snapshot)))

    ;; Check if already locked
    (when (:locked-by snapshot)
      (ex/raise :type :validation
                :code :snapshot-already-locked
                :hint "Version is already locked"
                :snapshot-id id
                :profile-id profile-id
                :locked-by (:locked-by snapshot)))

    (fsnap/lock-by! conn id profile-id)))

(def ^:private schema:unlock-file-snapshot
  [:map {:title "unlock-file-snapshot"}
   [:id ::sm/uuid]])

(sv/defmethod ::unlock-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:unlock-file-snapshot
   ::db/transaction  true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id]}]
  (let [snapshot (fsnap/get-minimal-snapshot conn id)]
    (files/check-edition-permissions! conn profile-id (:file-id snapshot))

    (when (not= (:created-by snapshot) "user")
      (ex/raise :type :validation
                :code :system-snapshots-cant-be-unlocked
                :hint "Only user-created versions can be unlocked"
                :snapshot-id id
                :profile-id profile-id))

    ;; Only the creator can unlock their own version
    (when (not= (:profile-id snapshot) profile-id)
      (ex/raise :type :validation
                :code :only-creator-can-unlock
                :hint "Only the version creator can unlock it"
                :snapshot-id id
                :profile-id profile-id
                :creator-id (:profile-id snapshot)))

    ;; Check if not locked
    (when (not (:locked-by snapshot))
      (ex/raise :type :validation
                :code :snapshot-not-locked
                :hint "Version is not locked"
                :snapshot-id id
                :profile-id profile-id))

    (fsnap/unlock! conn id)))

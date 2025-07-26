;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-snapshot
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.files.migrations :as fmg]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as feat.fdata]
   [app.features.file-migrations :refer [reset-migrations!]]
   [app.main :as-alias main]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(defn decode-row
  [{:keys [migrations] :as row}]
  (when row
    (cond-> row
      (some? migrations)
      (assoc :migrations (db/decode-pgarray migrations)))))

(def sql:get-file-snapshots
  "WITH changes AS (
      SELECT id, label, revn, created_at, created_by, profile_id, locked_by
        FROM file_change
       WHERE file_id = ?
         AND data IS NOT NULL
         AND (deleted_at IS NULL OR deleted_at > now())
   ), versions AS (
      (SELECT * FROM changes WHERE created_by = 'system' LIMIT 1000)
      UNION ALL
      (SELECT * FROM changes WHERE created_by != 'system' LIMIT 1000)
   )
   SELECT * FROM versions
    ORDER BY created_at DESC;")

(defn get-file-snapshots
  [conn file-id]
  (db/exec! conn [sql:get-file-snapshots file-id]))

(def ^:private schema:get-file-snapshots
  [:map {:title "get-file-snapshots"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-snapshots
  {::doc/added "1.20"
   ::sm/params schema:get-file-snapshots}
  [cfg {:keys [::rpc/profile-id file-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-read-permissions! conn profile-id file-id)
                 (get-file-snapshots conn file-id))))

(defn- generate-snapshot-label
  []
  (let [ts (-> (ct/now)
               (ct/format-inst)
               (str/replace #"[T:\.]" "-")
               (str/rtrim "Z"))]
    (str "snapshot-" ts)))

(defn create-file-snapshot!
  [cfg file & {:keys [label created-by deleted-at profile-id]
               :or {deleted-at :default
                    created-by :system}}]

  (assert (#{:system :user :admin} created-by)
          "expected valid keyword for created-by")

  (let [created-by
        (name created-by)

        deleted-at
        (cond
          (= deleted-at :default)
          (ct/plus (ct/now) (cf/get-deletion-delay))

          (ct/inst? deleted-at)
          deleted-at

          :else
          nil)

        label
        (or label (generate-snapshot-label))

        snapshot-id
        (uuid/next)

        data
        (blob/encode (:data file))

        features
        (into-array (:features file))

        migrations
        (into-array (:migrations file))]

    (l/dbg :hint "creating file snapshot"
           :file-id (str (:id file))
           :id (str snapshot-id)
           :label label)

    (db/insert! cfg :file-change
                {:id snapshot-id
                 :revn (:revn file)
                 :data data
                 :version (:version file)
                 :features features
                 :migrations migrations
                 :profile-id profile-id
                 :file-id (:id file)
                 :label label
                 :deleted-at deleted-at
                 :created-by created-by}
                {::db/return-keys false})

    {:id snapshot-id :label label}))

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
  (let [file    (bfc/get-file cfg file-id)
        project (db/get-by-id cfg :project (:project-id file))]

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/project-id (:project-id file))
        (assoc ::quotes/team-id (:team-id project))
        (assoc ::quotes/file-id (:id file))
        (quotes/check! {::quotes/id ::quotes/snapshots-per-file}
                       {::quotes/id ::quotes/snapshots-per-team}))

    (create-file-snapshot! cfg file
                           {:label label
                            :profile-id profile-id
                            :created-by :user})))

(defn restore-file-snapshot!
  [{:keys [::db/conn ::mbus/msgbus] :as cfg} file-id snapshot-id]
  (let [storage  (sto/resolve cfg {::db/reuse-conn true})
        file     (files/get-minimal-file conn file-id {::db/for-update true})
        vern     (rand-int Integer/MAX_VALUE)
        snapshot (some->> (db/get* conn :file-change
                                   {:file-id file-id
                                    :id snapshot-id}
                                   {::db/for-share true})
                          (feat.fdata/resolve-file-data cfg)
                          (decode-row))

        ;; If snapshot has tracked applied migrations, we reuse them,
        ;; if not we take a safest set of migrations as starting
        ;; point. This is because, at the time of implementing
        ;; snapshots, migrations were not taken into account so we
        ;; need to make this backward compatible in some way.
        file     (assoc file :migrations
                        (or (:migrations snapshot)
                            (fmg/generate-migrations-from-version 67)))]

    (when-not snapshot
      (ex/raise :type :not-found
                :code :snapshot-not-found
                :hint "unable to find snapshot with the provided label"
                :snapshot-id snapshot-id
                :file-id file-id))

    (when-not (:data snapshot)
      (ex/raise :type :validation
                :code :snapshot-without-data
                :hint "snapshot has no data"
                :label (:label snapshot)
                :file-id file-id))

    (l/dbg :hint "restoring snapshot"
           :file-id (str file-id)
           :label (:label snapshot)
           :snapshot-id (str (:id snapshot)))

    ;; If the file was already offloaded, on restoring the snapshot we
    ;; are going to replace the file data, so we need to touch the old
    ;; referenced storage object and avoid possible leaks
    (when (feat.fdata/offloaded? file)
      (sto/touch-object! storage (:data-ref-id file)))

    ;; In the same way, on reseting the file data, we need to restore
    ;; the applied migrations on the moment of taking the snapshot
    (reset-migrations! conn file)

    (db/update! conn :file
                {:data (:data snapshot)
                 :revn (inc (:revn file))
                 :vern vern
                 :version (:version snapshot)
                 :data-backend nil
                 :data-ref-id nil
                 :has-media-trimmed false
                 :features (:features snapshot)}
                {:id file-id})

    ;; clean object thumbnails
    (let [sql (str "update file_tagged_object_thumbnail "
                   "   set deleted_at = now() "
                   " where file_id=? returning media_id")
          res (db/exec! conn [sql file-id])]
      (doseq [media-id (into #{} (keep :media-id) res)]
        (sto/touch-object! storage media-id)))

    ;; clean file thumbnails
    (let [sql (str "update file_thumbnail "
                   "   set deleted_at = now() "
                   " where file_id=? returning media_id")
          res (db/exec! conn [sql file-id])]
      (doseq [media-id (into #{} (keep :media-id) res)]
        (sto/touch-object! storage media-id)))

    ;; Send to the clients a notification to reload the file
    (mbus/pub! msgbus
               :topic (:id file)
               :message {:type :file-restore
                         :file-id (:id file)
                         :vern vern})
    {:id (:id snapshot)
     :label (:label snapshot)}))

(def ^:private schema:restore-file-snapshot
  [:map {:title "restore-file-snapshot"}
   [:file-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::restore-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:restore-file-snapshot}
  [cfg {:keys [::rpc/profile-id file-id id] :as params}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (files/check-edition-permissions! conn profile-id file-id)
                (let [file (bfc/get-file cfg file-id)]
                  (create-file-snapshot! cfg file
                                         {:profile-id profile-id
                                          :created-by :system})
                  (restore-file-snapshot! cfg file-id id)))))

(def ^:private schema:update-file-snapshot
  [:map {:title "update-file-snapshot"}
   [:id ::sm/uuid]
   [:label ::sm/text]])

(defn- update-file-snapshot!
  [conn snapshot-id label]
  (-> (db/update! conn :file-change
                  {:label label
                   :created-by "user"
                   :deleted-at nil}
                  {:id snapshot-id}
                  {::db/return-keys true})
      (dissoc :data :features :migrations)))

(defn- get-snapshot
  "Get a minimal snapshot from database and lock for update"
  [conn id]
  (db/get conn :file-change
          {:id id}
          {::sql/columns [:id :file-id :created-by :deleted-at :profile-id :locked-by]
           ::db/for-update true}))

(sv/defmethod ::update-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:update-file-snapshot}
  [cfg {:keys [::rpc/profile-id id label]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (let [snapshot (get-snapshot conn id)]
                  (files/check-edition-permissions! conn profile-id (:file-id snapshot))
                  (update-file-snapshot! conn id label)))))

(def ^:private schema:remove-file-snapshot
  [:map {:title "remove-file-snapshot"}
   [:id ::sm/uuid]])

(defn- delete-file-snapshot!
  [conn snapshot-id]
  (db/update! conn :file-change
              {:deleted-at (ct/now)}
              {:id snapshot-id}
              {::db/return-keys false})
  nil)

(sv/defmethod ::delete-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:remove-file-snapshot}
  [cfg {:keys [::rpc/profile-id id]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (let [snapshot (get-snapshot conn id)]
                  (files/check-edition-permissions! conn profile-id (:file-id snapshot))

                  (when (not= (:created-by snapshot) "user")
                    (ex/raise :type :validation
                              :code :system-snapshots-cant-be-deleted
                              :snapshot-id id
                              :profile-id profile-id))

                  ;; Check if version is locked by someone else
                  (when (and (:locked-by snapshot)
                             (not= (:locked-by snapshot) profile-id))
                    (ex/raise :type :validation
                              :code :snapshot-is-locked
                              :hint "Cannot delete a locked version"
                              :snapshot-id id
                              :profile-id profile-id
                              :locked-by (:locked-by snapshot)))

                  (delete-file-snapshot! conn id)))))

;;; Lock/unlock version endpoints

(def ^:private schema:lock-file-snapshot
  [:map {:title "lock-file-snapshot"}
   [:id ::sm/uuid]])

(defn- lock-file-snapshot!
  [conn snapshot-id profile-id]
  (db/update! conn :file-change
              {:locked-by profile-id}
              {:id snapshot-id}
              {::db/return-keys false})
  nil)

(sv/defmethod ::lock-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:lock-file-snapshot}
  [cfg {:keys [::rpc/profile-id id]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (let [snapshot (get-snapshot conn id)]
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

                  (lock-file-snapshot! conn id profile-id)))))

(def ^:private schema:unlock-file-snapshot
  [:map {:title "unlock-file-snapshot"}
   [:id ::sm/uuid]])

(defn- unlock-file-snapshot!
  [conn snapshot-id]
  (db/update! conn :file-change
              {:locked-by nil}
              {:id snapshot-id}
              {::db/return-keys false})
  nil)

(sv/defmethod ::unlock-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:unlock-file-snapshot}
  [cfg {:keys [::rpc/profile-id id]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (let [snapshot (get-snapshot conn id)]
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

                  (unlock-file-snapshot! conn id)))))

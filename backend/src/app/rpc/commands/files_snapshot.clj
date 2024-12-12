;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-snapshot
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as feat.fdata]
   [app.main :as-alias main]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [cuerdas.core :as str]))

(def sql:get-file-snapshots
  "WITH changes AS (
      SELECT id, label, revn, created_at, created_by, profile_id
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

(def ^:private sql:get-file
  "SELECT f.*,
          p.id AS project_id,
          p.team_id AS team_id
     FROM file AS f
    INNER JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?")

(defn- get-file
  [cfg file-id]
  (let [file (->> (db/exec-one! cfg [sql:get-file file-id])
                  (feat.fdata/resolve-file-data cfg))]
    (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
      (-> file
          (update :data blob/decode)
          (update :data feat.fdata/process-pointers deref)
          (update :data feat.fdata/process-objects (partial into {}))
          (update :data assoc ::id file-id)
          (update :data blob/encode)))))

(defn- generate-snapshot-label
  []
  (let [ts (-> (dt/now)
               (dt/format-instant)
               (str/replace #"[T:\.]" "-")
               (str/rtrim "Z"))]
    (str "snapshot-" ts)))

(defn create-file-snapshot!
  [cfg profile-id file-id label]
  (let [file (get-file cfg file-id)

        ;; NOTE: final user never can provide label as `:system`
        ;; keyword because the validator implies label always as
        ;; string; keyword is used for signal a special case
        created-by
        (if (= label :system)
          "system"
          "user")

        deleted-at
        (if (= label :system)
          (dt/plus (dt/now) (cf/get-deletion-delay))
          nil)

        label
        (if (= label :system)
          (str "internal/snapshot/" (:revn file))
          (or label (generate-snapshot-label)))

        snapshot-id
        (uuid/next)]

    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/project-id (:project-id file))
        (assoc ::quotes/team-id (:team-id file))
        (assoc ::quotes/file-id (:id file))
        (quotes/check! {::quotes/id ::quotes/snapshots-per-file}
                       {::quotes/id ::quotes/snapshots-per-team}))

    (l/debug :hint "creating file snapshot"
             :file-id (str file-id)
             :id (str snapshot-id)
             :label label)

    (db/insert! cfg :file-change
                {:id snapshot-id
                 :revn (:revn file)
                 :data (:data file)
                 :version (:version file)
                 :features (:features file)
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
   ::sm/params schema:create-file-snapshot}
  [cfg {:keys [::rpc/profile-id file-id label]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (files/check-edition-permissions! conn profile-id file-id)
                (create-file-snapshot! cfg profile-id file-id label))))

(defn restore-file-snapshot!
  [{:keys [::db/conn ::mbus/msgbus] :as cfg} file-id snapshot-id]
  (let [storage  (sto/resolve cfg {::db/reuse-conn true})
        file     (files/get-minimal-file conn file-id {::db/for-update true})
        vern     (rand-int Integer/MAX_VALUE)
        snapshot (some->> (db/get* conn :file-change
                                   {:file-id file-id
                                    :id snapshot-id}
                                   {::db/for-share true})
                          (feat.fdata/resolve-file-data cfg))]

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

    ;; If the file was already offloaded, on restring the snapshot
    ;; we are going to replace the file data, so we need to touch
    ;; the old referenced storage object and avoid possible leaks
    (when (feat.fdata/offloaded? file)
      (sto/touch-object! storage (:data-ref-id file)))

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
                (create-file-snapshot! cfg profile-id file-id :system)
                (restore-file-snapshot! cfg file-id id))))

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
      (dissoc :data :features)))

(defn- get-snapshot
  "Get a minimal snapshot from database and lock for update"
  [conn id]
  (db/get conn :file-change
          {:id id}
          {::sql/columns [:id :file-id :created-by :deleted-at]
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
              {:deleted-at (dt/now)}
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

                  (delete-file-snapshot! conn id)))))

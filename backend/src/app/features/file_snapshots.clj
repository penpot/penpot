;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.features.file-snapshots
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as-alias cfeat]
   [app.common.files.migrations :as fmg]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as fdata]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [cuerdas.core :as str]
   [promesa.exec :as px]))

(def sql:snapshots
  "SELECT c.id,
          c.label,
          c.created_at,
          c.updated_at AS modified_at,
          c.deleted_at,
          c.profile_id,
          c.created_by,
          c.revn,
          c.features,
          c.migrations,
          c.version,
          c.file_id,
          c.data AS legacy_data,
          fd.data AS data,
          coalesce(fd.backend, 'db') AS backend,
          fd.metadata AS metadata
     FROM file_change AS c
     LEFT JOIN file_data AS fd ON (fd.file_id = c.file_id
                                   AND fd.id = c.id
                                   AND fd.type = 'snapshot')
    WHERE c.label IS NOT NULL")

(def ^:private sql:get-snapshot
  (str sql:snapshots " AND c.file_id = ? AND c.id = ?"))

(def ^:private sql:get-snapshots
  (str sql:snapshots " AND c.file_id = ?"))

(def ^:private sql:get-snapshot-without-data
  (str "WITH snapshots AS (" sql:snapshots ")"
       "SELECT c.id,
               c.label,
               c.revn,
               c.created_at,
               c.modified_at,
               c.deleted_at,
               c.profile_id,
               c.created_by,
               c.features,
               c.metadata,
               c.migrations,
               c.version,
               c.file_id
         FROM snapshots AS c
        WHERE c.id = ?"))

(defn- decode-snapshot
  [snapshot]
  (some-> snapshot (-> (d/update-when :metadata fdata/decode-metadata)
                       (d/update-when :migrations db/decode-pgarray [])
                       (d/update-when :features db/decode-pgarray #{}))))

(def sql:get-minimal-file
  "SELECT f.id,
          f.revn,
          f.modified_at,
          f.deleted_at,
          fd.backend AS backend,
          fd.metadata AS metadata
     FROM file AS f
     LEFT JOIN file_data AS fd ON (fd.file_id = f.id AND fd.id = f.id)
    WHERE f.id = ?")

(defn get-minimal-file
  [cfg id & {:as opts}]
  (-> (db/get-with-sql cfg [sql:get-minimal-file id] opts)
      (d/update-when :metadata fdata/decode-metadata)))

(defn get-minimal-snapshot
  [cfg snapshot-id]
  (-> (db/get-with-sql cfg [sql:get-snapshot-without-data snapshot-id])
      (decode-snapshot)))

(defn get-snapshot
  "Get snapshot with decoded data"
  [cfg file-id snapshot-id]
  (->> (db/get-with-sql cfg [sql:get-snapshot file-id snapshot-id])
       (decode-snapshot)
       (fdata/resolve-file-data cfg)
       (fdata/decode-file-data cfg)))

(def ^:private sql:get-visible-snapshots
  (str "WITH "
       "snapshots1 AS ( " sql:snapshots "),"
       "snapshots2 AS (
          SELECT c.id,
                 c.label,
                 c.version,
                 c.created_at,
                 c.modified_at,
                 c.created_by,
                 c.profile_id
            FROM snapshots1 AS c
           WHERE c.file_id = ?
             AND (c.deleted_at IS NULL OR deleted_at > now())
       ), snapshots3 AS (
          (SELECT * FROM snapshots2 WHERE created_by = 'system' LIMIT 1000)
          UNION ALL
          (SELECT * FROM snapshots2 WHERE created_by != 'system' LIMIT 1000)
       )
       SELECT * FROM snapshots3
        ORDER BY created_at DESC;"))

(defn get-visible-snapshots
  "Return a list of snapshots fecheable from the API, it has a limited
  set of fields and applies big but safe limits over all available
  snapshots. It return a ordered vector by the snapshot date of
  creation."
  [cfg file-id]
  (->> (db/exec! cfg [sql:get-visible-snapshots file-id])
       (mapv decode-snapshot)))

(def ^:private schema:decoded-file
  [:map {:title "DecodedFile"}
   [:id ::sm/uuid]
   [:revn :int]
   [:vern :int]
   [:data :map]
   [:version :int]
   [:features ::cfeat/features]
   [:migrations [::sm/set :string]]])

(def ^:private schema:snapshot
  [:map {:title "Snapshot"}
   [:id ::sm/uuid]
   [:revn [::sm/int {:min 0}]]
   [:version [::sm/int {:min 0}]]
   [:features ::cfeat/features]
   [:migrations [::sm/set ::sm/text]]
   [:profile-id {:optional true} ::sm/uuid]
   [:label ::sm/text]
   [:file-id ::sm/uuid]
   [:created-by [:enum "system" "user" "admin"]]
   [:deleted-at {:optional true} ::sm/inst]
   [:modified-at ::sm/inst]
   [:created-at ::sm/inst]])

(def ^:private schema:snapshot-params
  [:map {:title "SnapshotParams"}
   [:id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:label ::sm/text]
   [:modified-at {:optional true} ::sm/inst]])

(def ^:private check-snapshot
  (sm/check-fn schema:snapshot))

(def ^:private check-snapshot-params
  (sm/check-fn schema:snapshot-params))

(def ^:private check-decoded-file
  (sm/check-fn schema:decoded-file))

(defn- generate-snapshot-label
  []
  (let [ts (-> (dt/now)
               (dt/format-instant)
               (str/replace #"[T:\.]" "-")
               (str/rtrim "Z"))]
    (str "snapshot-" ts)))

(defn create!
  "Create a file snapshot; expects a non-encoded file."
  [cfg file & {:keys [label created-by deleted-at profile-id session-id]
               :or {deleted-at :default
                    created-by "system"}}]

  (let [file        (check-decoded-file file)


        snapshot-id (uuid/next)
        created-at  (dt/now)
        deleted-at  (cond
                      (= deleted-at :default)
                      (dt/plus (dt/now) (cf/get-deletion-delay))

                      (dt/instant? deleted-at)
                      deleted-at

                      :else
                      nil)

        label       (or label (generate-snapshot-label))
        data        (px/invoke! (::wrk/executor cfg) #(blob/encode (:data file)))
        features    (:features file)
        migrations  (:migrations file)

        snapshot    {:id snapshot-id
                     :revn (:revn file)
                     :version (:version file)
                     :file-id (:id file)
                     :features features
                     :migrations migrations
                     :label label
                     :created-at created-at
                     :modified-at created-at
                     :created-by created-by}

        snapshot   (cond-> snapshot
                     deleted-at
                     (assoc :deleted-at deleted-at)

                     :always
                     (check-snapshot))]

    (db/insert! cfg :file-change
                (-> snapshot
                    (update :features into-array)
                    (update :migrations into-array)
                    (assoc :updated-at created-at)
                    (assoc :profile-id profile-id)
                    (assoc :session-id session-id)
                    (dissoc :modified-at))
                {::db/return-keys false})

    (fdata/create! cfg
                   {:id snapshot-id
                    :file-id (:id file)
                    :type "snapshot"
                    :data data
                    :created-at created-at
                    :modified-at created-at})

    snapshot))

(defn update!
  [cfg params]

  (let [{:keys [id file-id label modified-at]}
        (check-snapshot-params params)

        modified-at
        (or modified-at (dt/now))]

    (-> (db/update! cfg :file-change
                    {:label label
                     :created-by "user"
                     :updated-at modified-at
                     :deleted-at nil}
                    {:file-id file-id
                     :id id}
                    {::db/return-keys false})
        (db/get-update-count)
        (pos?))))

(defn restore!
  [{:keys [::db/conn] :as cfg} file-id snapshot-id]
  (let [file (get-minimal-file conn file-id {::db/for-update true})
        vern (rand-int Integer/MAX_VALUE)

        storage
        (sto/resolve cfg {::db/reuse-conn true})

        snapshot
        (get-snapshot cfg file-id snapshot-id)]

    (when-not snapshot
      (ex/raise :type :not-found
                :code :snapshot-not-found
                :hint "unable to find snapshot with the provided label"
                :snapshot-id snapshot-id
                :file-id file-id))

    (when-not (:data snapshot)
      (ex/raise :type :internal
                :code :snapshot-without-data
                :hint "snapshot has no data"
                :label (:label snapshot)
                :file-id file-id))

    (let [;; If the snapshot has applied migrations stored, we reuse
          ;; them, if not, we take a safest set of migrations as
          ;; starting point. This is because, at the time of
          ;; implementing snapshots, migrations were not taken into
          ;; account so we need to make this backward compatible in
          ;; some way.
          migrations
          (or (:migrations snapshot)
              (fmg/generate-migrations-from-version 67))

          file
          (-> file
              (update :revn inc)
              (assoc :migrations migrations)
              (assoc :data (:data snapshot))
              (assoc :vern vern)
              (assoc :version (:version snapshot))
              (assoc :has-media-trimmed false)
              (assoc :modified-at (:modified-at snapshot))
              (assoc :features (:features snapshot)))]

      (l/dbg :hint "restoring snapshot"
             :file-id (str file-id)
             :label (:label snapshot)
             :snapshot-id (str (:id snapshot)))

      ;; In the same way, on reseting the file data, we need to restore
      ;; the applied migrations on the moment of taking the snapshot
      (bfc/update-file! cfg file ::bfc/reset-migrations true)

      ;; FIXME: this should be separated functions, we should not have
      ;; inline sql here.

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

      vern)))

(defn delete!
  [cfg {:keys [id file-id]}]
  (let [deleted-at (dt/now)]
    (db/update! cfg :file-change
                {:deleted-at deleted-at}
                {:id id :file-id file-id}
                {::db/return-keys false})
    true))


(defn reduce-snapshots
  "Process the file snapshots using efficient reduction."
  [cfg file-id xform f init]
  (let [conn  (db/get-connection cfg)
        xform (comp
               (map (partial fdata/resolve-file-data cfg))
               (map (partial fdata/decode-file-data cfg))
               xform)]

    (->> (db/plan conn [sql:get-snapshots file-id] {:fetch-size 1})
         (transduce xform f init))))


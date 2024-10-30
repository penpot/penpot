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
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as feat.fdata]
   [app.main :as-alias main]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [cuerdas.core :as str]))

(def sql:get-file-snapshots
  "SELECT id, label, revn, created_at, created_by, profile_id
     FROM file_change
    WHERE file_id = ?
      AND created_at < ?
      AND data IS NOT NULL
    ORDER BY created_at DESC
    LIMIT ?")

(defn get-file-snapshots
  [{:keys [::db/conn]} {:keys [file-id limit start-at]
                        :or {limit Long/MAX_VALUE}}]
  (let [start-at (or start-at (dt/now))
        limit    (min limit 20)]
    (db/exec! conn [sql:get-file-snapshots file-id start-at limit])))

(def ^:private schema:get-file-snapshots
  [:map {:title "get-file-snapshots"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-snapshots
  {::doc/added "1.20"
   ::sm/params schema:get-file-snapshots}
  [cfg params]
  (db/run! cfg get-file-snapshots params))

(defn restore-file-snapshot!
  [{:keys [::db/conn ::mbus/msgbus] :as cfg} {:keys [file-id id]}]
  (let [storage  (sto/resolve cfg {::db/reuse-conn true})
        file     (files/get-minimal-file conn file-id {::db/for-update true})
        vern     (rand-int Integer/MAX_VALUE)
        snapshot (db/get* conn :file-change
                          {:file-id file-id
                           :id id}
                          {::db/for-share true})]

    (when-not snapshot
      (ex/raise :type :not-found
                :code :snapshot-not-found
                :hint "unable to find snapshot with the provided label"
                :id id
                :file-id file-id))

    (let [snapshot (feat.fdata/resolve-file-data cfg snapshot)]
      (when-not (:data snapshot)
        (ex/raise :type :precondition
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
       :label (:label snapshot)})))

(def ^:private
  schema:restore-file-snapshot
  [:and
   [:map
    [:file-id ::sm/uuid]
    [:id {:optional true} ::sm/uuid]]
   [::sm/contains-any #{:id :label}]])

(sv/defmethod ::restore-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:restore-file-snapshot}
  [cfg params]
  (db/tx-run! cfg restore-file-snapshot! params))

(defn- get-file
  [cfg file-id]
  (let [file (->> (db/get cfg :file {:id file-id})
                  (feat.fdata/resolve-file-data cfg))]
    (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
      (-> file
          (update :data blob/decode)
          (update :data feat.fdata/process-pointers deref)
          (update :data feat.fdata/process-objects (partial into {}))
          (update :data blob/encode)))))

(defn- generate-snapshot-label
  []
  (let [ts (-> (dt/now)
               (dt/format-instant)
               (str/replace #"[T:\.]" "-")
               (str/rtrim "Z"))]
    (str "snapshot-" ts)))

(defn take-file-snapshot!
  [cfg {:keys [file-id label ::rpc/profile-id]}]
  (let [label (or label (generate-snapshot-label))
        file  (-> (get-file cfg file-id)
                  (update :data
                          (fn [data]
                            (-> data
                                (blob/decode)
                                (assoc :id file-id)))))

        snapshot-id
        (uuid/next)

        snapshot-data
        (-> (:data file)
            (feat.fdata/process-pointers deref)
            (feat.fdata/process-objects (partial into {}))
            (blob/encode))]

    (l/debug :hint "creating file snapshot"
             :file-id (str file-id)
             :id (str snapshot-id)
             :label label)

    (db/insert! cfg :file-change
                {:id snapshot-id
                 :revn (:revn file)
                 :data snapshot-data
                 :version (:version file)
                 :features (:features file)
                 :profile-id profile-id
                 :file-id (:id file)
                 :label label
                 :created-by "user"}
                {::db/return-keys false})

    {:id snapshot-id :label label}))

(def ^:private schema:take-file-snapshot
  [:map
   [:file-id ::sm/uuid]
   [:label {:optional true} :string]])

(sv/defmethod ::take-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:take-file-snapshot}
  [cfg params]
  (db/tx-run! cfg take-file-snapshot! params))

(def ^:private schema:update-file-snapshot
  [:map {:title "update-file-snapshot"}
   [:id ::sm/uuid]
   [:label ::sm/text]])

(defn update-file-snapshot!
  [{:keys [::db/conn] :as cfg} {:keys [id label]}]
  (let [result
        (db/update! conn :file-change
                    {:label label
                     :created-by "user"}
                    {:id id}
                    {::db/return-keys true})]

    (select-keys result [:id :label :revn :created-at :profile-id :created-by])))

(sv/defmethod ::update-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:update-file-snapshot}
  [cfg params]
  (db/tx-run! cfg update-file-snapshot! params))

(def ^:private schema:remove-file-snapshot
  [:map {:title "remove-file-snapshot"}
   [:id ::sm/uuid]])

(defn remove-file-snapshot!
  [{:keys [::db/conn] :as cfg} {:keys [id]}]
  (db/delete! conn :file-change
              {:id id :created-by "user"}
              {::db/return-keys false})
  nil)

(sv/defmethod ::remove-file-snapshot
  {::doc/added "1.20"
   ::sm/params schema:remove-file-snapshot}
  [cfg params]
  (db/tx-run! cfg remove-file-snapshot! params))



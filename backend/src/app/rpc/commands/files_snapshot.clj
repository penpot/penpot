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
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [cuerdas.core :as str]))

(defn check-authorized!
  [{:keys [::db/pool]} profile-id]
  (when-not (or (= "devenv" (cf/get :host))
                (let [profile (ex/ignoring (profile/get-profile pool profile-id))
                      admins  (or (cf/get :admins) #{})]
                  (contains? admins (:email profile))))
    (ex/raise :type :authentication
              :code :authentication-required
              :hint "only admins allowed")))

(def sql:get-file-snapshots
  "SELECT id, label, revn, created_at
     FROM file_change
    WHERE file_id = ?
      AND created_at < ?
      AND label IS NOT NULL
    ORDER BY created_at DESC
    LIMIT ?")

(defn get-file-snapshots
  [{:keys [::db/conn]} {:keys [file-id limit start-at]
                        :or {limit Long/MAX_VALUE}}]
  (let [start-at (or start-at (dt/now))
        limit    (min limit 20)]
    (->> (db/exec! conn [sql:get-file-snapshots file-id start-at limit])
         (mapv (fn [row]
                 (update row :created-at dt/format-instant :rfc1123))))))

(def ^:private schema:get-file-snapshots
  [:map [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-snapshots
  {::doc/added "1.20"
   ::doc/skip true
   ::sm/params schema:get-file-snapshots}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (check-authorized! cfg profile-id)
  (db/run! cfg get-file-snapshots params))

(defn restore-file-snapshot!
  [{:keys [::db/conn] :as cfg} {:keys [file-id id]}]
  (let [storage  (sto/resolve cfg {::db/reuse-conn true})
        file     (files/get-minimal-file conn file-id {::db/for-update true})
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

      {:id (:id snapshot)
       :label (:label snapshot)})))

(defn- resolve-snapshot-by-label
  [conn file-id label]
  (->> (db/query conn :file-change
                 {:file-id file-id
                  :label label}
                 {::sql/order-by [[:created-at :desc]]
                  ::sql/columns [:file-id :id :label]})
       (first)))

(def ^:private
  schema:restore-file-snapshot
  [:and
   [:map
    [:file-id ::sm/uuid]
    [:id {:optional true} ::sm/uuid]
    [:label {:optional true} :string]]
   [::sm/contains-any #{:id :label}]])

(sv/defmethod ::restore-file-snapshot
  {::doc/added "1.20"
   ::doc/skip true
   ::sm/params schema:restore-file-snapshot}
  [cfg {:keys [::rpc/profile-id file-id id label] :as params}]
  (check-authorized! cfg profile-id)
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (let [params (cond-> params
                                   (and (not id) (string? label))
                                   (merge (resolve-snapshot-by-label conn file-id label)))]
                      (restore-file-snapshot! cfg params)))))

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

(defn take-file-snapshot!
  [cfg {:keys [file-id label ::rpc/profile-id]}]
  (let [file  (get-file cfg file-id)
        id    (uuid/next)]

    (l/debug :hint "creating file snapshot"
             :file-id (str file-id)
             :label label)

    (db/insert! cfg :file-change
                {:id id
                 :revn (:revn file)
                 :data (:data file)
                 :version (:version file)
                 :features (:features file)
                 :profile-id profile-id
                 :file-id (:id file)
                 :label label}
                {::db/return-keys false})

    {:id id :label label}))

(defn generate-snapshot-label
  []
  (let [ts (-> (dt/now)
               (dt/format-instant)
               (str/replace #"[T:\.]" "-")
               (str/rtrim "Z"))]
    (str "snapshot-" ts)))

(def ^:private schema:take-file-snapshot
  [:map [:file-id ::sm/uuid]])

(sv/defmethod ::take-file-snapshot
  {::doc/added "1.20"
   ::doc/skip true
   ::sm/params schema:take-file-snapshot}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (check-authorized! cfg profile-id)
  (db/tx-run! cfg (fn [cfg]
                    (let [params (update params :label (fn [label]
                                                         (or label (generate-snapshot-label))))]
                      (take-file-snapshot! cfg params)))))


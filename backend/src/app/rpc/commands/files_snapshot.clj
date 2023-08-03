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
   [app.main :as-alias main]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.profile :as profile]
   [app.rpc.doc :as-alias doc]
   [app.storage :as sto]
   [app.util.services :as sv]
   [app.util.time :as dt]))

(defn check-authorized!
  [{:keys [::db/pool]} profile-id]
  (when-not (or (= "devenv" (cf/get :host))
                (let [profile (ex/ignoring (profile/get-profile pool profile-id))
                      admins  (or (cf/get :admins) #{})]
                  (contains? admins (:email profile))))
    (ex/raise :type :authentication
              :code :authentication-required
              :hint "only admins allowed")))

(defn get-file-snapshots
  [{:keys [::db/conn]} {:keys [file-id limit start-at]
                        :or {limit Long/MAX_VALUE}}]
  (let [query    (str "select id, label, revn, created_at "
                      "  from file_change "
                      " where file_id = ? "
                      "   and created_at < ? "
                      "   and label is not null "
                      "   and data is not null "
                      " order by created_at desc "
                      " limit ?")
        start-at (or start-at (dt/now))
        limit    (min limit 20)]

    (->> (db/exec! conn [query file-id start-at limit])
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
  (db/run! cfg #(get-file-snapshots % params)))

(defn restore-file-snapshot!
  [{:keys [::db/conn ::sto/storage] :as cfg} {:keys [file-id id]}]
  (let [storage  (media/configure-assets-storage storage conn)
        params   {:id id :file-id file-id}
        options  {:columns [:id :data :revn]}
        snapshot (db/get* conn :file-change params options)]

    (when (and (some? snapshot)
               (some? (:data snapshot)))

      (l/debug :hint "snapshot found"
               :snapshot-id (:id snapshot)
               :file-id file-id)

      (db/update! conn :file
                    {:data (:data snapshot)}
                    {:id file-id})

      ;; clean object thumbnails
      (let [sql (str "delete from file_object_thumbnail "
                     " where file_id=? returning media_id")
            res (db/exec! conn [sql file-id])]

        (doseq [media-id (into #{} (keep :media-id) res)]
          (sto/del-object! storage media-id)))

      ;; clean object thumbnails
      (let [sql (str "delete from file_thumbnail "
                     " where file_id=? returning media_id")
            res (db/exec! conn [sql file-id])]
        (doseq [media-id (into #{} (keep :media-id) res)]
          (sto/del-object! storage media-id)))

      {:id (:id snapshot)})))

(def ^:private schema:restore-file-snapshot
  [:map
   [:file-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::restore-file-snapshot
  {::doc/added "1.20"
   ::doc/skip true
   ::sm/params schema:restore-file-snapshot}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (check-authorized! cfg profile-id)
  (db/tx-run! cfg #(restore-file-snapshot! % params)))

(defn take-file-snapshot!
  [{:keys [::db/conn]} {:keys [file-id label]}]
  (when-let [file (db/get* conn :file {:id file-id})]
    (let [id    (uuid/next)
          label (or label (str "Snapshot at " (dt/format-instant (dt/now) :rfc1123)))]
      (l/debug :hint "persisting file snapshot" :file-id file-id :label label)
      (db/insert! conn :file-change
                  {:id id
                   :revn (:revn file)
                   :data (:data file)
                   :features (:features file)
                   :file-id (:id file)
                   :label label})
      {:id id})))

(def ^:private schema:take-file-snapshot
  [:map [:file-id ::sm/uuid]])

(sv/defmethod ::take-file-snapshot
  {::doc/added "1.20"
   ::doc/skip true
   ::sm/params schema:take-file-snapshot}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (check-authorized! cfg profile-id)
  (db/tx-run! cfg #(take-file-snapshot! % params)))


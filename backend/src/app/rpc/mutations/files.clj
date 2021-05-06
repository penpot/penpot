;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.mutations.files
  (:require
   [app.common.exceptions :as ex]
   [app.common.pages :as cp]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.projects :as proj]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::url ::us/url)

;; --- Mutation: Create File

(declare create-file)

(s/def ::is-shared ::us/boolean)
(s/def ::create-file
  (s/keys :req-un [::profile-id ::name ::project-id]
          :opt-un [::id ::is-shared]))

(sv/defmethod ::create-file
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id project-id)
    (create-file conn params)))


(defn create-file-role
  [conn {:keys [file-id profile-id role]}]
  (let [params {:file-id file-id
                :profile-id profile-id}]
    (->> (perms/assign-role-flags params role)
         (db/insert! conn :file-profile-rel))))

(defn create-file
  [conn {:keys [id name project-id is-shared]
         :or {is-shared false}
         :as params}]
  (let [id   (or id (uuid/next))
        data (cp/make-file-data id)
        file (db/insert! conn :file
                         {:id id
                          :project-id project-id
                          :name name
                          :is-shared is-shared
                          :data (blob/encode data)})]
    (->> (assoc params :file-id id :role :owner)
         (create-file-role conn))
    (assoc file :data data)))


;; --- Mutation: Rename File

(declare rename-file)

(s/def ::rename-file
  (s/keys :req-un [::profile-id ::name ::id]))

(sv/defmethod ::rename-file
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id id)
    (rename-file conn params)))

(defn- rename-file
  [conn {:keys [id name] :as params}]
  (db/update! conn :file
              {:name name}
              {:id id}))


;; --- Mutation: Set File shared

(declare set-file-shared)

(s/def ::set-file-shared
  (s/keys :req-un [::profile-id ::id ::is-shared]))

(sv/defmethod ::set-file-shared
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id id)
    (set-file-shared conn params)))

(defn- set-file-shared
  [conn {:keys [id is-shared] :as params}]
  (db/update! conn :file
              {:is-shared is-shared}
              {:id id}))


;; --- Mutation: Delete File

(declare mark-file-deleted)

(s/def ::delete-file
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::delete-file
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id id)

    ;; Schedule object deletion
    (wrk/submit! {::wrk/task :delete-object
                  ::wrk/delay cfg/deletion-delay
                  ::wrk/conn conn
                  :id id
                  :type :file})

    (mark-file-deleted conn params)))

(defn mark-file-deleted
  [conn {:keys [id] :as params}]
  (db/update! conn :file
              {:deleted-at (dt/now)}
              {:id id})
  nil)


;; --- Mutation: Link file to library

(declare link-file-to-library)

(s/def ::link-file-to-library
  (s/keys :req-un [::profile-id ::file-id ::library-id]))

(sv/defmethod ::link-file-to-library
  [{:keys [pool] :as cfg} {:keys [profile-id file-id library-id] :as params}]
  (when (= file-id library-id)
    (ex/raise :type :validation
              :code :invalid-library
              :hint "A file cannot be linked to itself"))
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (files/check-edition-permissions! conn profile-id library-id)
    (link-file-to-library conn params)))

(def sql:link-file-to-library
  "insert into file_library_rel (file_id, library_file_id)
   values (?, ?)
       on conflict do nothing;")

(defn- link-file-to-library
  [conn {:keys [file-id library-id] :as params}]
  (db/exec-one! conn [sql:link-file-to-library file-id library-id]))


;; --- Mutation: Unlink file from library

(declare unlink-file-from-library)

(s/def ::unlink-file-from-library
  (s/keys :req-un [::profile-id ::file-id ::library-id]))

(sv/defmethod ::unlink-file-from-library
  [{:keys [pool] :as cfg} {:keys [profile-id file-id library-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (unlink-file-from-library conn params)))

(defn- unlink-file-from-library
  [conn {:keys [file-id library-id] :as params}]
  (db/delete! conn :file-library-rel
              {:file-id file-id
               :library-file-id library-id}))


;; --- Mutation: Update syncrhonization status of a link

(declare update-sync)

(s/def ::update-sync
  (s/keys :req-un [::profile-id ::file-id ::library-id]))

(sv/defmethod ::update-sync
  [{:keys [pool] :as cfg} {:keys [profile-id file-id library-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (update-sync conn params)))

(defn- update-sync
  [conn {:keys [file-id library-id] :as params}]
  (db/update! conn :file-library-rel
              {:synced-at (dt/now)}
              {:file-id file-id
               :library-file-id library-id}))


;; --- Mutation: Ignore updates in linked files

(declare ignore-sync)

(s/def ::ignore-sync
  (s/keys :req-un [::profile-id ::file-id ::date]))

(sv/defmethod ::ignore-sync
  [{:keys [pool] :as cfg} {:keys [profile-id file-id date] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (ignore-sync conn params)))

(defn- ignore-sync
  [conn {:keys [file-id date] :as params}]
  (db/update! conn :file
              {:ignore-sync-until date}
              {:id file-id}))


;; A generic, Changes based (granular) file update method.

(s/def ::changes
  (s/coll-of map? :kind vector?))

(s/def ::session-id ::us/uuid)
(s/def ::revn ::us/integer)
(s/def ::update-file
  (s/keys :req-un [::id ::session-id ::profile-id ::revn ::changes]))

;; File changes that affect to the library, and must be notified
;; to all clients using it.
(defn library-change?
  [change]
  (or (#{:add-color :mod-color :del-color
         :add-media :mod-media :del-media
         :add-component :mod-component :del-component
         :add-typography :mod-typography :del-typography} (:type change))
      (and (#{:add-obj :mod-obj :del-obj
              :reg-objects :mov-objects} (:type change))
           (some? (:component-id change)))))

(declare insert-change)
(declare retrieve-lagged-changes)
(declare retrieve-team-id)
(declare send-notifications)
(declare update-file)

(sv/defmethod ::update-file
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (let [{:keys [id] :as file} (db/get-by-id conn :file id {:for-update true})]
      (files/check-edition-permissions! conn profile-id id)
      (update-file (assoc cfg :conn  conn)
                   (assoc params :file file)))))

(defn- update-file
  [{:keys [conn] :as cfg} {:keys [file changes session-id profile-id] :as params}]
  (when (> (:revn params)
           (:revn file))
    (ex/raise :type :validation
              :code :revn-conflict
              :hint "The incoming revision number is greater that stored version."
              :context {:incoming-revn (:revn params)
                        :stored-revn (:revn file)}))

  (let [file (-> file
                 (update :revn inc)
                 (update :data (fn [data]
                                 (-> data
                                     (blob/decode)
                                     (assoc :id (:id file))
                                     (pmg/migrate-data)
                                     (cp/process-changes changes)
                                     (blob/encode)))))]
    ;; Insert change to the xlog
    (db/insert! conn :file-change
                {:id (uuid/next)
                 :session-id session-id
                 :profile-id profile-id
                 :file-id (:id file)
                 :revn (:revn file)
                 :data (:data file)
                 :changes (blob/encode changes)})

    ;; Update file
    (db/update! conn :file
                {:revn (:revn file)
                 :data (:data file)
                 :has-media-trimmed false}
                {:id (:id file)})

    (let [params (assoc params :file file)]
      ;; Send asynchronous notifications
      (send-notifications cfg params)

      ;; Retrieve and return lagged data
      (retrieve-lagged-changes conn params))))

(defn- send-notifications
  [{:keys [msgbus conn] :as cfg} {:keys [file changes session-id] :as params}]
  (let [lchanges (filter library-change? changes)]

    ;; Asynchronously publish message to the msgbus
    (msgbus :pub {:topic (:id file)
                  :message
                  {:type :file-change
                   :profile-id (:profile-id params)
                   :file-id (:id file)
                   :session-id (:session-id params)
                   :revn (:revn file)
                   :changes changes}})

    (when (and (:is-shared file) (seq lchanges))
      (let [team-id (retrieve-team-id conn (:project-id file))]
        ;; Asynchronously publish message to the msgbus
        (msgbus :pub {:topic team-id
                      :message
                      {:type :library-change
                       :profile-id (:profile-id params)
                       :file-id (:id file)
                       :session-id session-id
                       :revn (:revn file)
                       :modified-at (dt/now)
                       :changes lchanges}})))))

(defn- retrieve-team-id
  [conn project-id]
  (:team-id (db/get-by-id conn :project project-id {:columns [:team-id]})))

(def ^:private
  sql:lagged-changes
  "select s.id, s.revn, s.file_id,
          s.session_id, s.changes
     from file_change as s
    where s.file_id = ?
      and s.revn > ?
    order by s.created_at asc")

(defn- retrieve-lagged-changes
  [conn params]
  (->> (db/exec! conn [sql:lagged-changes (:id params) (:revn params)])
       (mapv files/decode-row)))


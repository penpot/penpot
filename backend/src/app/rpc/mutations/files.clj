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
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.projects :as proj]
   [app.rpc.rlimit :as rlimit]
   [app.storage.impl :as simpl]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]))

(declare create-file)

;; --- Helpers & Specs

(s/def ::frame-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::url ::us/url)

;; --- Mutation: Create File

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
  [conn {:keys [id name project-id is-shared data deleted-at revn]
         :or {is-shared false
              revn 0
              deleted-at nil}
         :as params}]
  (let [id   (or id (:id data) (uuid/next))
        data (or data (cp/make-file-data id))
        file (db/insert! conn :file
                         {:id id
                          :project-id project-id
                          :name name
                          :revn revn
                          :is-shared is-shared
                          :data (blob/encode data)
                          :deleted-at deleted-at})]

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
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (unlink-file-from-library conn params)))

(defn- unlink-file-from-library
  [conn {:keys [file-id library-id] :as params}]
  (db/delete! conn :file-library-rel
              {:file-id file-id
               :library-file-id library-id}))


;; --- Mutation: Update synchronization status of a link

(declare update-sync)

(s/def ::update-sync
  (s/keys :req-un [::profile-id ::file-id ::library-id]))

(sv/defmethod ::update-sync
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
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
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (ignore-sync conn params)))

(defn- ignore-sync
  [conn {:keys [file-id date] :as params}]
  (db/update! conn :file
              {:ignore-sync-until date}
              {:id file-id}))


;; --- MUTATION: update-file

;; A generic, Changes based (granular) file update method.

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

(s/def ::changes
  (s/coll-of map? :kind vector?))

(s/def ::hint-origin ::us/keyword)
(s/def ::hint-events
  (s/every ::us/keyword :kind vector?))

(s/def ::change-with-metadata
  (s/keys :req-un [::changes]
          :opt-un [::hint-origin
                   ::hint-events]))

(s/def ::changes-with-metadata
  (s/every ::change-with-metadata :kind vector?))

(s/def ::session-id ::us/uuid)
(s/def ::revn ::us/integer)
(s/def ::update-file
  (s/and
   (s/keys :req-un [::id ::session-id ::profile-id ::revn]
           :opt-un [::changes ::changes-with-metadata])
   (fn [o]
     (or (contains? o :changes)
         (contains? o :changes-with-metadata)))))

(sv/defmethod ::update-file
  {::rlimit/permits (cf/get :rlimit-file-update)}
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (db/xact-lock! conn id)
    (let [{:keys [id] :as file} (db/get-by-id conn :file id {:for-key-share true})]
      (files/check-edition-permissions! conn profile-id id)
      (update-file (assoc cfg :conn  conn)
                   (assoc params :file file)))))

(defn- take-snapshot?
  "Defines the rule when file `data` snapshot should be saved."
  [{:keys [revn modified-at] :as file}]
  (let [freq    (or (cf/get :file-change-snapshot-every) 20)
        timeout (or (cf/get :file-change-snapshot-timeout)
                    (dt/duration {:hours 1}))]
    (or (= 1 freq)
        (zero? (mod revn freq))
        (> (inst-ms (dt/diff modified-at (dt/now)))
           (inst-ms timeout)))))

(defn- delete-from-storage
  [{:keys [storage] :as cfg} file]
  (p/do
    (when-let [backend (simpl/resolve-backend storage (:data-backend file))]
      (simpl/del-object backend file))))

(defn- update-file
  [{:keys [conn metrics] :as cfg} {:keys [file changes changes-with-metadata session-id profile-id] :as params}]
  (when (> (:revn params)
           (:revn file))

    (ex/raise :type :validation
              :code :revn-conflict
              :hint "The incoming revision number is greater that stored version."
              :context {:incoming-revn (:revn params)
                        :stored-revn (:revn file)}))

  (let [changes (if changes-with-metadata
                  (mapcat :changes changes-with-metadata)
                  changes)

        changes (vec changes)

        ;; Trace the number of changes processed
        _       (mtx/run! metrics {:id :update-file-changes :inc (count changes)})

        ts      (dt/now)
        file    (-> file
                    (update :revn inc)
                    (update :data (fn [data]
                                    ;; Trace the length of bytes of processed data
                                    (mtx/run! metrics {:id :update-file-bytes-processed :inc (alength data)})
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
                 :created-at ts
                 :file-id (:id file)
                 :revn (:revn file)
                 :data (when (take-snapshot? file)
                         (:data file))
                 :changes (blob/encode changes)})

    ;; Update file
    (db/update! conn :file
                {:revn (:revn file)
                 :data (:data file)
                 :data-backend nil
                 :modified-at ts
                 :has-media-trimmed false}
                {:id (:id file)})

    ;; We need to delete the data from external storage backend
    (when-not (nil? (:data-backend file))
      @(delete-from-storage cfg file))

    (db/update! conn :project
                {:modified-at ts}
                {:id (:project-id file)})

    (let [params (assoc params :file file :changes changes)]
      ;; Send asynchronous notifications
      (send-notifications cfg params)

      ;; Retrieve and return lagged data
      (retrieve-lagged-changes conn params))))

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
       (into [] (comp (map files/decode-row)
                      (map (fn [row]
                             (cond-> row
                               (= (:revn row) (:revn (:file params)))
                               (assoc :changes []))))))))

(defn- send-notifications
  [{:keys [conn] :as cfg} {:keys [file changes session-id] :as params}]
  (let [lchanges  (filter library-change? changes)
        msgbus-fn (:msgbus cfg)]


    ;; Asynchronously publish message to the msgbus
    (msgbus-fn :cmd :pub
               :topic (:id file)
               :message {:type :file-change
                         :profile-id (:profile-id params)
                         :file-id (:id file)
                         :session-id (:session-id params)
                         :revn (:revn file)
                         :changes changes})

    (when (and (:is-shared file) (seq lchanges))
      (let [team-id (retrieve-team-id conn (:project-id file))]
        ;; Asynchronously publish message to the msgbus
        (msgbus-fn :cmd :pub
                   :topic team-id
                   :message {:type :library-change
                             :profile-id (:profile-id params)
                             :file-id (:id file)
                             :session-id session-id
                             :revn (:revn file)
                             :modified-at (dt/now)
                             :changes lchanges})))))

(defn- retrieve-team-id
  [conn project-id]
  (:team-id (db/get-by-id conn :project project-id {:columns [:team-id]})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TEMPORARY FILES (behaves differently)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::create-temp-file ::create-file)

(sv/defmethod ::create-temp-file
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (db/with-atomic [conn pool]
    (proj/check-edition-permissions! conn profile-id project-id)
    (create-file conn (assoc params :deleted-at (dt/in-future {:days 1})))))

(s/def ::update-temp-file
  (s/keys :req-un [::changes ::revn ::session-id ::id]))

(sv/defmethod ::update-temp-file
  [{:keys [pool] :as cfg} {:keys [profile-id session-id id revn changes] :as params}]
  (db/with-atomic [conn pool]
    (db/insert! conn :file-change
                {:id (uuid/next)
                 :session-id session-id
                 :profile-id profile-id
                 :created-at (dt/now)
                 :file-id id
                 :revn revn
                 :data nil
                 :changes (blob/encode changes)})
    nil))

(s/def ::persist-temp-file
  (s/keys :req-un [::id ::profile-id]))

(sv/defmethod ::persist-temp-file
  [{:keys [pool] :as cfg} {:keys [id profile-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id id)
    (let [file (db/get-by-id conn :file id)
          revs (db/query conn :file-change
                         {:file-id id}
                         {:order-by [[:revn :asc]]})
          revn (count revs)]

      (when (nil? (:deleted-at file))
        (ex/raise :type :validation
                  :code :cant-persist-already-persisted-file))

      (loop [revs (seq revs)
             data (blob/decode (:data file))]
        (if-let [rev (first revs)]
          (recur (rest revs)
                 (->> rev :changes blob/decode (cp/process-changes data)))
          (db/update! conn :file
                      {:deleted-at nil
                       :revn revn
                       :data (blob/encode data)}
                      {:id id})))

      nil)))


;; --- Mutation: Upsert frame thumbnail

(def sql:upsert-frame-thumbnail
  "insert into file_frame_thumbnail(file_id, frame_id, data)
   values (?, ?, ?)
       on conflict(file_id, frame_id) do
          update set data = ?;")

(s/def ::data ::us/string)
(s/def ::upsert-file-frame-thumbnail
  (s/keys :req-un [::profile-id ::file-id ::frame-id ::data]))

(sv/defmethod ::upsert-file-frame-thumbnail
  [{:keys [pool] :as cfg} {:keys [profile-id file-id frame-id data]}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (db/exec-one! conn [sql:upsert-frame-thumbnail file-id frame-id data data])
    nil))

;; --- Mutation: Upsert file thumbnail

(def sql:upsert-file-thumbnail
  "insert into file_thumbnail (file_id, revn, data, props)
   values (?, ?, ?, ?::jsonb)
       on conflict(file_id, revn) do
          update set data = ?, props=?, updated_at=now();")

(s/def ::revn ::us/integer)
(s/def ::props map?)
(s/def ::upsert-file-thumbnail
  (s/keys :req-un [::profile-id ::file-id ::revn ::data ::props]))

(sv/defmethod ::upsert-file-thumbnail
  [{:keys [pool] :as cfg} {:keys [profile-id file-id revn data props]}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (let [props (db/tjson (or props {}))]
      (db/exec-one! conn [sql:upsert-file-thumbnail
                          file-id revn data props data props])
      nil)))

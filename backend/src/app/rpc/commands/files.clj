;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.pages.helpers :as cph]
   [app.common.pages.migrations :as pmg]
   [app.common.schema :as sm]
   [app.common.schema.desc-js-like :as-alias smdj]
   [app.common.schema.generators :as sg]
   [app.common.spec :as us]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- FEATURES

(def supported-features
  #{"storage/objects-map"
    "storage/pointer-map"
    "components/v2"})

(defn get-default-features
  []
  (cond-> #{}
    (contains? cf/flags :fdata-storage-pointer-map)
    (conj "storage/pointer-map")

    (contains? cf/flags :fdata-storage-objects-map)
    (conj "storage/objects-map")))

;; --- SPECS

(s/def ::features ::us/set-of-strings)
(s/def ::file-id ::us/uuid)
(s/def ::frame-id ::us/uuid)
(s/def ::id ::us/uuid)
(s/def ::is-shared ::us/boolean)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::search-term ::us/string)
(s/def ::team-id ::us/uuid)

;; --- HELPERS

(def long-cache-duration
  (dt/duration {:days 7}))

(defn decode-row
  [{:keys [data changes features] :as row}]
  (when row
    (cond-> row
      features (assoc :features (db/decode-pgarray features #{}))
      changes  (assoc :changes (blob/decode changes))
      data     (assoc :data (blob/decode data)))))

;; --- FILE PERMISSIONS

(def ^:private sql:file-permissions
  "select fpr.is_owner,
          fpr.is_admin,
          fpr.can_edit
     from file_profile_rel as fpr
    where fpr.file_id = ?
      and fpr.profile_id = ?
   union all
   select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = ?
      and tpr.profile_id = ?
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    inner join file as f on (f.project_id = ppr.project_id)
    where f.id = ?
      and ppr.profile_id = ?")

(defn get-file-permissions
  [conn profile-id file-id]
  (when (and profile-id file-id)
    (db/exec! conn [sql:file-permissions
                    file-id profile-id
                    file-id profile-id
                    file-id profile-id])))

(defn get-permissions
  ([conn profile-id file-id]
   (let [rows     (get-file-permissions conn profile-id file-id)
         is-owner (boolean (some :is-owner rows))
         is-admin (boolean (some :is-admin rows))
         can-edit (boolean (some :can-edit rows))]
     (when (seq rows)
       {:type :membership
        :is-owner is-owner
        :is-admin (or is-owner is-admin)
        :can-edit (or is-owner is-admin can-edit)
        :can-read true
        :is-logged (some? profile-id)})))

  ([conn profile-id file-id share-id]
   (let [perms  (get-permissions conn profile-id file-id)
         ldata  (some-> (db/get* conn :share-link {:id share-id :file-id file-id})
                        (dissoc :flags)
                        (update :pages db/decode-pgarray #{}))]

     ;; NOTE: in a future when share-link becomes more powerful and
     ;; will allow us specify which parts of the app is available, we
     ;; will probably need to tweak this function in order to expose
     ;; this flags to the frontend.
     (cond
       (some? perms) perms
       (some? ldata) {:type :share-link
                      :can-read true
                      :pages (:pages ldata)
                      :is-logged (some? profile-id)
                      :who-comment (:who-comment ldata)
                      :who-inspect (:who-inspect ldata)}))))

(def has-edit-permissions?
  (perms/make-edition-predicate-fn get-permissions))

(def has-read-permissions?
  (perms/make-read-predicate-fn get-permissions))

(def has-comment-permissions?
  (perms/make-comment-predicate-fn get-permissions))

(def check-edition-permissions!
  (perms/make-check-fn has-edit-permissions?))

(def check-read-permissions!
  (perms/make-check-fn has-read-permissions?))

;; A user has comment permissions if she has read permissions, or
;; explicit comment permissions through the share-id

(defn check-comment-permissions!
  [conn profile-id file-id share-id]
  (let [perms       (get-permissions conn profile-id file-id share-id)
        can-read    (has-read-permissions? perms)
        can-comment (has-comment-permissions? perms)]
    (when-not (or can-read can-comment)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found"))))

;; --- HELPERS

(defn get-team-id
  [conn project-id]
  (:team-id (db/get-by-id conn :project project-id {:columns [:team-id]})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FEATURES: pointer-map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn check-features-compatibility!
  [features]
  (let [not-supported (set/difference features supported-features)]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :features-not-supported
                :feature (first not-supported)
                :hint (format "features %s not supported" (str/join "," (map name not-supported)))))
    features))

(defn load-pointer
  [conn file-id id]
  (let [row (db/get conn :file-data-fragment
                    {:id id :file-id file-id}
                    {:columns [:content]
                     ::db/check-deleted? false})]
    (blob/decode (:content row))))

(defn- load-all-pointers!
  [{:keys [data] :as file}]
  (doseq [[_id page] (:pages-index data)]
    (when (pmap/pointer-map? page)
      (pmap/load! page)))
  (doseq [[_id component] (:components data)]
    (when (pmap/pointer-map? component)
      (pmap/load! component)))
  file)

(defn persist-pointers!
  [conn file-id]
  (doseq [[id item] @pmap/*tracked*]
    (when (pmap/modified? item)
      (let [content (-> item deref blob/encode)]
        (db/insert! conn :file-data-fragment
                    {:id id
                     :file-id file-id
                     :content content})))))

(defn process-pointers
  [file update-fn]
  (update file :data (fn resolve-fn [data]
                       (cond-> data
                         (contains? data :pages-index)
                         (update :pages-index resolve-fn)

                         :always
                         (update-vals (fn [val]
                                        (if (pmap/pointer-map? val)
                                          (update-fn val)
                                          val)))))))


(defn get-all-pointer-ids
  "Given a file, return all pointer ids used in the data."
  [fdata]
  (->> (concat (vals fdata)
               (vals (:pages-index fdata)))
       (into #{} (comp (filter pmap/pointer-map?)
                       (map pmap/get-id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-file-features!
  [conn {:keys [id features data] :as file} client-features]

  (when (and (contains? features "components/v2")
             (not (contains? client-features "components/v2")))
    (ex/raise :type :restriction
              :code :feature-mismatch
              :feature "components/v2"
              :hint "file has 'components/v2' feature enabled but frontend didn't specifies it"))

  ;; NOTE: this operation is needed because the components migration
  ;; generates a new page with random id which is returned to the
  ;; client; without persisting the migration this can cause that two
  ;; simultaneous clients can have a different view of the file data
  ;; and end persisting two pages with main components and breaking
  ;; the whole file
  (let [file (if (and (contains? client-features "components/v2")
                      (not (contains? features "components/v2")))
               (binding [pmap/*tracked* (atom {})]
                 (let [data        (ctf/migrate-to-components-v2 data)
                       features    (conj features "components/v2")
                       modified-at (dt/now)
                       features    (db/create-array conn "text" features)]
                   (db/update! conn :file
                               {:data (blob/encode data)
                                :modified-at modified-at
                                :features features}
                               {:id id})
                   (persist-pointers! conn id)
                   (-> file
                       (assoc :modified-at modified-at)
                       (assoc :features features)
                       (assoc :data data))))
               file)]

    (cond-> file
      (and (contains? features "storage/pointer-map")
           (not (contains? client-features "storage/pointer-map")))
      (process-pointers deref))))

;; --- COMMAND QUERY: get-file (by id)

(sm/def! ::features
  [:schema
   {:title "FileFeatures"
    ::smdj/inline true
    :gen/gen (sg/subseq supported-features)}
   ::sm/set-of-strings])

(sm/def! ::file
  [:map {:title "File"}
   [:id ::sm/uuid]
   [:features ::features]
   [:has-media-trimmed :boolean]
   [:comment-thread-seqn {:min 0} :int]
   [:name :string]
   [:revn {:min 0} :int]
   [:modified-at ::dt/instant]
   [:is-shared :boolean]
   [:project-id ::sm/uuid]
   [:created-at ::dt/instant]
   [:data {:optional true} :any]])

(sm/def! ::permissions-mixin
  [:map {:title "PermissionsMixin"}
   [:permissions ::perms/permissions]])

(sm/def! ::file-with-permissions
  [:merge {:title "FileWithPermissions"}
   ::file
   ::permissions-mixin])

(sm/def! ::get-file
  [:map {:title "get-file"}
   [:features {:optional true} ::features]
   [:id ::sm/uuid]])

(defn get-file
  [conn id client-features]
  ;; here we check if client requested features are supported
  (check-features-compatibility! client-features)
  (binding [pmap/*load-fn* (partial load-pointer conn id)]
    (let [file  (-> (db/get-by-id conn :file id)
                    (decode-row)
                    (pmg/migrate-file))

          file  (handle-file-features! conn file client-features)]

      ;; NOTE: if migrations are applied, probably new pointers generated so
      ;; instead of persiting them on each get-file, we just resolve them until
      ;; user updates the file and permanently persists the new pointers
      (cond-> file
        (pmg/migrated? file)
        (process-pointers deref)))))

(defn get-minimal-file
  [{:keys [::db/pool] :as cfg} id]
  (db/get pool :file {:id id} {:columns [:id :modified-at :revn]}))

(defn get-file-etag
  [{:keys [modified-at revn]}]
  (str (dt/format-instant modified-at :iso) "-" revn))

(sv/defmethod ::get-file
  "Retrieve a file by its ID. Only authenticated users."
  {::doc/added "1.17"
   ::cond/get-object #(get-minimal-file %1 (:id %2))
   ::cond/key-fn get-file-etag
   ::sm/params ::get-file
   ::sm/result ::file-with-permissions}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id features] :as params}]
  (dm/with-open [conn (db/open pool)]
    (let [perms (get-permissions conn profile-id id)]
      (check-read-permissions! perms)
      (let [file (-> (get-file conn id features)
                     (assoc :permissions perms))]
        (vary-meta file assoc ::cond/key (get-file-etag file))))))


;; --- COMMAND QUERY: get-file-fragment (by id)

(sm/def! ::file-fragment
  [:map {:title "FileFragment"}
   [:id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:created-at ::dt/instant]
   [:content any?]])

(sm/def! ::get-file-fragment
  [:map {:title "get-file-fragment"}
   [:file-id ::sm/uuid]
   [:fragment-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]])

(defn- get-file-fragment
  [conn file-id fragment-id]
  (some-> (db/get conn :file-data-fragment {:file-id file-id :id fragment-id})
          (update :content blob/decode)))

(sv/defmethod ::get-file-fragment
  "Retrieve a file by its ID. Only authenticated users."
  {::doc/added "1.17"
   ::sm/params ::get-file-fragment
   ::sm/result ::file-fragment}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id fragment-id share-id] }]
  (dm/with-open [conn (db/open pool)]
    (let [perms (get-permissions conn profile-id file-id share-id)]
      (check-read-permissions! perms)
      (-> (get-file-fragment conn file-id fragment-id)
          (rph/with-http-cache long-cache-duration)))))

;; --- COMMAND QUERY: get-project-files

(def ^:private sql:project-files
  "select f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.revn,
          f.is_shared
     from file as f
    where f.project_id = ?
      and f.deleted_at is null
    order by f.modified_at desc")

(defn get-project-files
  [conn project-id]
  (db/exec! conn [sql:project-files project-id]))

(sv/defmethod ::get-project-files
  "Get all files for the specified project."
  {::doc/added "1.17"
   ::sm/params [:map {:title "get-project-files"}
                [:project-id ::sm/uuid]]
   ::sm/result [:vector ::file]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id]}]
  (dm/with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (get-project-files conn project-id)))


;; --- COMMAND QUERY: has-file-libraries

(declare get-has-file-libraries)

(sv/defmethod ::has-file-libraries
  "Checks if the file has libraries. Returns a boolean"
  {::doc/added "1.15.1"
   ::sm/params [:map {:title "has-file-libraries"}
                [:file-id ::sm/uuid]]
   ::sm/result :boolean}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! pool profile-id file-id)
    (get-has-file-libraries conn file-id)))

(def ^:private sql:has-file-libraries
  "SELECT COUNT(*) > 0 AS has_libraries
     FROM file_library_rel AS flr
     JOIN file AS fl ON (flr.library_file_id = fl.id)
    WHERE flr.file_id = ?::uuid
      AND (fl.deleted_at IS NULL OR
           fl.deleted_at > now())")

(defn- get-has-file-libraries
  [conn file-id]
  (let [row (db/exec-one! conn [sql:has-file-libraries file-id])]
    (:has-libraries row)))


;; --- QUERY COMMAND: get-page

(defn- prune-objects
  "Given the page data and the object-id returns the page data with all
  other not needed objects removed from the `:objects` data
  structure."
  [{:keys [objects] :as page} object-id]
  (let [objects (cph/get-children-with-self objects object-id)]
    (assoc page :objects (d/index-by :id objects))))

(defn- prune-thumbnails
  "Given the page data, removes the `:thumbnail` prop from all
  shapes."
  [page]
  (update page :objects update-vals #(dissoc % :thumbnail)))

(defn get-page
  [conn {:keys [file-id page-id object-id features]}]
  (when (and (uuid? object-id)
             (not (uuid? page-id)))
    (ex/raise :type :validation
              :code :params-validation
              :hint "page-id is required when object-id is provided"))

  (let [file     (get-file conn file-id features)
        page-id  (or page-id (-> file :data :pages first))
        page     (dm/get-in file [:data :pages-index page-id])]
    (cond-> (prune-thumbnails page)
      (uuid? object-id)
      (prune-objects object-id))))

(sm/def! ::get-page
  [:map {:title "GetPage"}
   [:page-id {:optional true} ::sm/uuid]
   [:object-id {:optional true} ::sm/uuid]
   [:features {:optional true} ::features]])

(sv/defmethod ::get-page
  "Retrieves the page data from file and returns it. If no page-id is
  specified, the first page will be returned. If object-id is
  specified, only that object and its children will be returned in the
  page objects data structure.

  If you specify the object-id, the page-id parameter becomes
  mandatory.

  Mainly used for rendering purposes."
  {::doc/added "1.17"
   ::sm/params ::get-page}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (get-page conn params)))


;; --- COMMAND QUERY: get-team-shared-files

(def ^:private sql:team-shared-files
  "select f.id,
          f.revn,
          f.data,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    inner join project as p on (p.id = f.project_id)
    where f.is_shared = true
      and f.deleted_at is null
      and p.deleted_at is null
      and p.team_id = ?
    order by f.modified_at desc")

(defn get-team-shared-files
  [conn team-id]
  (letfn [(assets-sample [assets limit]
           (let [sorted-assets (->> (vals assets)
                                    (sort-by #(str/lower (:name %))))]
             {:count (count sorted-assets)
              :sample (into [] (take limit sorted-assets))}))

          (library-summary [{:keys [id data] :as file}]
            (binding [pmap/*load-fn* (partial load-pointer conn id)]
              (let [load-objects (fn [component]
                                   (binding [pmap/*load-fn* (partial load-pointer conn id)]
                                     (ctf/load-component-objects data component)))
                    components-sample (-> (assets-sample (ctkl/components data) 4)
                                          (update :sample
                                                  #(map load-objects %)))]
                {:components components-sample
                 :media (assets-sample (:media data) 3)
                 :colors (assets-sample (:colors data) 3)
                 :typographies (assets-sample (:typographies data) 3)})))]

    (->> (db/exec! conn [sql:team-shared-files team-id])
         (into #{} (comp
                    (map decode-row)
                    (map #(assoc % :library-summary (library-summary %)))
                    (map #(dissoc % :data)))))))

(s/def ::get-team-shared-files
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(sv/defmethod ::get-team-shared-files
  "Get all file (libraries) for the specified team."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (get-team-shared-files conn team-id)))


;; --- COMMAND QUERY: get-file-libraries

(def ^:private sql:get-file-libraries
  "WITH RECURSIVE libs AS (
     SELECT fl.*, flr.synced_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
      WHERE flr.file_id = ?::uuid
    UNION
     SELECT fl.*, flr.synced_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
       JOIN libs AS l ON (flr.file_id = l.id)
   )
   SELECT l.id,
          l.features,
          l.project_id,
          l.created_at,
          l.modified_at,
          l.deleted_at,
          l.name,
          l.revn,
          l.synced_at
     FROM libs AS l
    WHERE l.deleted_at IS NULL OR l.deleted_at > now();")

(defn get-file-libraries
  [conn file-id]
  (into []
        (comp
         (map #(assoc % :is-indirect false))
         (map decode-row))
        (db/exec! conn [sql:get-file-libraries file-id])))

(s/def ::get-file-libraries
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]))

(sv/defmethod ::get-file-libraries
  "Get libraries used by the specified file."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (get-file-libraries conn file-id)))


;; --- COMMAND QUERY: Files that use this File library

(def ^:private sql:library-using-files
  "SELECT f.id,
          f.name
     FROM file_library_rel AS flr
     JOIN file AS f ON (f.id = flr.file_id)
    WHERE flr.library_file_id = ?
      AND (f.deleted_at IS NULL OR f.deleted_at > now())")

(defn get-library-file-references
  [conn file-id]
  (db/exec! conn [sql:library-using-files file-id]))

(s/def ::get-library-file-references
  (s/keys :req [::rpc/profile-id] :req-un [::file-id]))

(sv/defmethod ::get-library-file-references
  "Returns all the file references that use specified file (library) id."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (get-library-file-references conn file-id)))

;; --- COMMAND QUERY: get-team-recent-files

(def sql:team-recent-files
  "with recent_files as (
     select f.id,
            f.revn,
            f.project_id,
            f.created_at,
            f.modified_at,
            f.name,
            f.is_shared,
            row_number() over w as row_num
       from file as f
       join project as p on (p.id = f.project_id)
      where p.team_id = ?
        and p.deleted_at is null
        and f.deleted_at is null
     window w as (partition by f.project_id order by f.modified_at desc)
      order by f.modified_at desc
   )
   select * from recent_files where row_num <= 10;")

(defn get-team-recent-files
  [conn team-id]
  (db/exec! conn [sql:team-recent-files team-id]))

(s/def ::get-team-recent-files
  (s/keys :req [::rpc/profile-id]
          :req-un [::team-id]))

(sv/defmethod ::get-team-recent-files
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (get-team-recent-files conn team-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- MUTATION COMMAND: rename-file

(defn rename-file
  [conn {:keys [id name]}]
  (db/update! conn :file
              {:name name
               :modified-at (dt/now)}
              {:id id}))

(sv/defmethod ::rename-file
  {::doc/added "1.17"
   ::webhooks/event? true

   ::sm/webhook
   [:map {:title "RenameFileEvent"}
    [:id ::sm/uuid]
    [:project-id ::sm/uuid]
    [:name :string]
    [:created-at ::dt/instant]
    [:modified-at ::dt/instant]]

   ::sm/params
   [:map {:title "RenameFileParams"}
    [:name {:min 1} :string]
    [:id ::sm/uuid]]

   ::sm/result
   [:map {:title "SimplifiedFile"}
    [:id ::sm/uuid]
    [:name :string]
    [:created-at ::dt/instant]
    [:modified-at ::dt/instant]]}

  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (let [file (rename-file conn params)]
      (rph/with-meta
        (select-keys file [:id :name :created-at :modified-at])
        {::audit/props {:project-id (:project-id file)
                        :created-at (:created-at file)
                        :modified-at (:modified-at file)}}))))

;; --- MUTATION COMMAND: set-file-shared

(defn unlink-files
  [conn {:keys [id] :as params}]
  (db/delete! conn :file-library-rel {:library-file-id id}))

(defn set-file-shared
  [conn {:keys [id is-shared] :as params}]
  (db/update! conn :file
              {:is-shared is-shared}
              {:id id}))

(def sql:get-referenced-files
  "SELECT f.id
     FROM file_library_rel AS flr
    INNER JOIN file AS f ON (f.id = flr.file_id)
    WHERE flr.library_file_id = ?
    ORDER BY f.created_at ASC;")

(defn absorb-library
  "Find all files using a shared library, and absorb all library assets
  into the file local libraries"
  [conn {:keys [id] :as params}]
  (let [library (db/get-by-id conn :file id)]
    (when (:is-shared library)
      (let [ldata (binding [pmap/*load-fn* (partial load-pointer conn id)]
                    (-> library decode-row load-all-pointers! pmg/migrate-file :data))
            rows  (db/exec! conn [sql:get-referenced-files id])]
        (doseq [file-id (map :id rows)]
          (binding [pmap/*load-fn* (partial load-pointer conn file-id)
                    pmap/*tracked* (atom {})]
            (let [file (-> (db/get-by-id conn :file file-id
                                         ::db/check-deleted? false
                                         ::db/remove-deleted? false)
                           (decode-row)
                           (load-all-pointers!)
                           (pmg/migrate-file))
                  data (ctf/absorb-assets (:data file) ldata)]
              (db/update! conn :file
                          {:revn (inc (:revn file))
                           :data (blob/encode data)
                           :modified-at (dt/now)}
                          {:id file-id})
              (persist-pointers! conn file-id))))))))

(s/def ::set-file-shared
  (s/keys :req [::rpc/profile-id]
          :req-un [::id ::is-shared]))

(sv/defmethod ::set-file-shared
  {::doc/added "1.17"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id is-shared] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (when-not is-shared
      (absorb-library conn params)
      (unlink-files conn params))

    (let [file (set-file-shared conn params)]
      (rph/with-meta
        (select-keys file [:id :name :is-shared])
        {::audit/props {:name (:name file)
                        :project-id (:project-id file)
                        :is-shared (:is-shared file)}}))))

;; --- MUTATION COMMAND: delete-file

(defn mark-file-deleted
  [conn {:keys [id] :as params}]
  (db/update! conn :file
              {:deleted-at (dt/now)}
              {:id id}))

(s/def ::delete-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]))

(sv/defmethod ::delete-file
  {::doc/added "1.17"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (absorb-library conn params)
    (let [file (mark-file-deleted conn params)]

      (rph/with-meta (rph/wrap)
        {::audit/props {:project-id (:project-id file)
                        :name (:name file)
                        :created-at (:created-at file)
                        :modified-at (:modified-at file)}}))))

;; --- MUTATION COMMAND: link-file-to-library

(def sql:link-file-to-library
  "insert into file_library_rel (file_id, library_file_id)
   values (?, ?)
       on conflict do nothing;")

(defn link-file-to-library
  [conn {:keys [file-id library-id] :as params}]
  (db/exec-one! conn [sql:link-file-to-library file-id library-id]))

(s/def ::link-file-to-library
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::library-id]))

(sv/defmethod ::link-file-to-library
  {::doc/added "1.17"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id library-id] :as params}]
  (when (= file-id library-id)
    (ex/raise :type :validation
              :code :invalid-library
              :hint "A file cannot be linked to itself"))
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (check-edition-permissions! conn profile-id library-id)
    (link-file-to-library conn params)))

;; --- MUTATION COMMAND: unlink-file-from-library

(defn unlink-file-from-library
  [conn {:keys [file-id library-id]}]
  (db/delete! conn :file-library-rel
              {:file-id file-id
               :library-file-id library-id}))

(s/def ::unlink-file-from-library
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::library-id]))

(sv/defmethod ::unlink-file-from-library
  {::doc/added "1.17"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (unlink-file-from-library conn params)))


;; --- MUTATION COMMAND: update-sync

(defn update-sync
  [conn {:keys [file-id library-id] :as params}]
  (db/update! conn :file-library-rel
              {:synced-at (dt/now)}
              {:file-id file-id
               :library-file-id library-id}))

(s/def ::update-file-library-sync-status
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::library-id]))

;; TODO: improve naming

(sv/defmethod ::update-file-library-sync-status
  "Update the synchronization statos of a file->library link"
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (update-sync conn params)))


;; --- MUTATION COMMAND: ignore-sync

(defn ignore-sync
  [conn {:keys [file-id date] :as params}]
  (db/update! conn :file
              {:ignore-sync-until date}
              {:id file-id}))

(s/def ::ignore-file-library-sync-status
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::date]))

;; TODO: improve naming
(sv/defmethod ::ignore-file-library-sync-status
  "Ignore updates in linked files"
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (->  (ignore-sync conn params)
         (update :features db/decode-pgarray #{}))))

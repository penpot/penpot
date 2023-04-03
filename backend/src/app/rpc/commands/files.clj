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
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape-tree :as ctt]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files.thumbnails :as-alias thumbs]
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

(def default-features
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
                :hint (format "features %s not supported" (str/join "," not-supported))))
    features))

(defn load-pointer
  [conn file-id id]
  (let [row (db/get conn :file-data-fragment
                    {:id id :file-id file-id}
                    {:columns [:content]
                     ::db/check-deleted? false})]
    (blob/decode (:content row))))

(defn load-all-pointers!
  [data]
  (doseq [[_id page] (:pages-index data)]
    (when (pmap/pointer-map? page)
      (pmap/load! page)))
  (doseq [[_id component] (:components data)]
    (when (pmap/pointer-map? component)
      (pmap/load! component)))
  data)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-file-features
  [{:keys [features] :as file} client-features]
  (when (and (contains? features "components/v2")
             (not (contains? client-features "components/v2")))
    (ex/raise :type :restriction
              :code :feature-mismatch
              :feature "components/v2"
              :hint "file has 'components/v2' feature enabled but frontend didn't specifies it"))

  (cond-> file
    (and (contains? client-features "components/v2")
         (not (contains? features "components/v2")))
    (update :data ctf/migrate-to-components-v2)

    (and (contains? features "storage/pointer-map")
         (not (contains? client-features "storage/pointer-map")))
    (process-pointers deref)))


;; --- COMMAND QUERY: get-file (by id)

(defn get-file
  [conn id client-features]
  ;; here we check if client requested features are supported
  (check-features-compatibility! client-features)
  (binding [pmap/*load-fn* (partial load-pointer conn id)]
    (-> (db/get-by-id conn :file id)
        (decode-row)
        (pmg/migrate-file)
        (handle-file-features client-features))))

(defn get-minimal-file
  [{:keys [::db/pool] :as cfg} id]
  (db/get pool :file {:id id} {:columns [:id :modified-at :revn]}))

(defn get-file-etag
  [{:keys [modified-at revn]}]
  (str (dt/format-instant modified-at :iso) "-" revn))

(s/def ::get-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::id]
          :opt-un [::features]))

(sv/defmethod ::get-file
  "Retrieve a file by its ID. Only authenticated users."
  {::doc/added "1.17"
   ::cond/get-object #(get-minimal-file %1 (:id %2))
   ::cond/key-fn get-file-etag}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id features]}]
  (dm/with-open [conn (db/open pool)]
    (let [perms (get-permissions conn profile-id id)]
      (check-read-permissions! perms)
      (let [file (-> (get-file conn id features)
                     (assoc :permissions perms))]
        (vary-meta file assoc ::cond/key (get-file-etag file))))))


;; --- COMMAND QUERY: get-file-fragment (by id)

(defn- get-file-fragment
  [conn file-id fragment-id]
  (some-> (db/get conn :file-data-fragment {:file-id file-id :id fragment-id})
          (update :content blob/decode)))

(s/def ::share-id ::us/uuid)
(s/def ::fragment-id ::us/uuid)

(s/def ::get-file-fragment
  (s/keys :req-un [::file-id ::fragment-id]
          :opt [::rpc/profile-id]
          :opt-un [::share-id]))

(sv/defmethod ::get-file-fragment
  "Retrieve a file by its ID. Only authenticated users."
  {::doc/added "1.17"
   ::rpc/:auth false}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id fragment-id share-id] }]
  (dm/with-open [conn (db/open pool)]
    (let [perms (get-permissions conn profile-id file-id share-id)]
      (check-read-permissions! perms)
      (-> (get-file-fragment conn file-id fragment-id)
          (rph/with-http-cache long-cache-duration)))))

;; --- COMMAND QUERY: get-file-object-thumbnails

(defn get-object-thumbnails
  ([conn file-id]
   (let [sql (str/concat
              "select object_id, data "
              "  from file_object_thumbnail"
              " where file_id=?")]
     (->> (db/exec! conn [sql file-id])
          (d/index-by :object-id :data))))

  ([conn file-id object-ids]
   (let [sql (str/concat
              "select object_id, data "
              "  from file_object_thumbnail"
              " where file_id=? and object_id = ANY(?)")
         ids (db/create-array conn "text" (seq object-ids))]
     (->> (db/exec! conn [sql file-id ids])
          (d/index-by :object-id :data)))))

(s/def ::get-file-object-thumbnails
  (s/keys :req [::rpc/profile-id] :req-un [::file-id]))

(sv/defmethod ::get-file-object-thumbnails
  "Retrieve a file object thumbnails."
  {::doc/added "1.17"
   ::cond/get-object #(get-minimal-file %1 (:file-id %2))
   ::cond/reuse-key? true
   ::cond/key-fn get-file-etag}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (get-object-thumbnails conn file-id)))


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

(s/def ::get-project-files
  (s/keys :req [::rpc/profile-id] :req-un [::project-id]))

(defn get-project-files
  [conn project-id]
  (db/exec! conn [sql:project-files project-id]))

(sv/defmethod ::get-project-files
  "Get all files for the specified project."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id]}]
  (dm/with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (get-project-files conn project-id)))


;; --- COMMAND QUERY: has-file-libraries

(declare get-has-file-libraries)

(s/def ::file-id ::us/uuid)

(s/def ::has-file-libraries
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]))

(sv/defmethod ::has-file-libraries
  "Checks if the file has libraries. Returns a boolean"
  {::doc/added "1.15.1"}
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
  (let [file     (get-file conn file-id features)
        page-id  (or page-id (-> file :data :pages first))
        page     (dm/get-in file [:data :pages-index page-id])]
    (cond-> (prune-thumbnails page)
      (uuid? object-id)
      (prune-objects object-id))))

(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::get-page
  (s/and
   (s/keys :req [::rpc/profile-id]
           :req-un [::file-id]
           :opt-un [::page-id ::object-id ::features])
   (fn [obj]
     (if (contains? obj :object-id)
       (contains? obj :page-id)
       true))))

(sv/defmethod ::get-page
  "Retrieves the page data from file and returns it. If no page-id is
  specified, the first page will be returned. If object-id is
  specified, only that object and its children will be returned in the
  page objects data structure.

  If you specify the object-id, the page-id parameter becomes
  mandatory.

  Mainly used for rendering purposes."
  {::doc/added "1.17"}
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

(def ^:private sql:file-libraries
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
          l.data,
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
  [conn file-id client-features]
  (check-features-compatibility! client-features)
  (->> (db/exec! conn [sql:file-libraries file-id])
       (map decode-row)
       (map #(assoc % :is-indirect false))
       (map (fn [{:keys [id] :as row}]
              (binding [pmap/*load-fn* (partial load-pointer conn id)]
                (-> row
                    ;; TODO: re-enable this dissoc and replace call
                    ;;       with other that gets files individually
                    ;;       See task https://tree.taiga.io/project/penpot/task/4904
                    ;; (update :data dissoc :pages-index)
                    (handle-file-features client-features)))))
       (vec)))

(s/def ::get-file-libraries
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::features]))

(sv/defmethod ::get-file-libraries
  "Get libraries used by the specified file."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id features]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (get-file-libraries conn file-id features)))


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


;; --- COMMAND QUERY: get-file-thumbnail

(defn get-file-thumbnail
  [conn file-id revn]
  (let [sql (sql/select :file-thumbnail
                        (cond-> {:file-id file-id}
                          revn (assoc :revn revn))
                        {:limit 1
                         :order-by [[:revn :desc]]})
        row (db/exec-one! conn sql)]
    (when-not row
      (ex/raise :type :not-found
                :code :file-thumbnail-not-found))

    {:data (:data row)
     :props (some-> (:props row) db/decode-transit-pgobject)
     :revn (:revn row)
     :file-id (:file-id row)}))

(s/def ::revn ::us/integer)

(s/def ::get-file-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::revn]))

(sv/defmethod ::get-file-thumbnail
  {::doc/added "1.17"}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id revn]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (-> (get-file-thumbnail conn file-id revn)
        (rph/with-http-cache long-cache-duration))))


;; --- COMMAND QUERY: get-file-data-for-thumbnail

;; FIXME: performance issue
;;
;; We need to improve how we set frame for thumbnail in order to avoid
;; loading all pages into memory for find the frame set for thumbnail.

(defn get-file-data-for-thumbnail
  [conn {:keys [data id] :as file}]
  (letfn [;; function responsible on finding the frame marked to be
          ;; used as thumbnail; the returned frame always have
          ;; the :page-id set to the page that it belongs.

          (get-thumbnail-frame [data]
            ;; NOTE: this is a hack for avoid perform blocking
            ;; operation inside the for loop, clojure lazy-seq uses
            ;; synchronized blocks that does not plays well with
            ;; virtual threads, so we need to perform the load
            ;; operation first. This operation forces all pointer maps
            ;; load into the memory.
            (->> (-> data :pages-index vals)
                 (filter pmap/pointer-map?)
                 (run! pmap/load!))

            ;; Then proceed to find the frame set for thumbnail

            (d/seek :use-for-thumbnail?
                    (for [page  (-> data :pages-index vals)
                          frame (-> page :objects ctt/get-frames)]
                      (assoc frame :page-id (:id page)))))

          ;; function responsible to filter objects data structure of
          ;; all unneeded shapes if a concrete frame is provided. If no
          ;; frame, the objects is returned untouched.
          (filter-objects [objects frame-id]
            (d/index-by :id (cph/get-children-with-self objects frame-id)))

          ;; function responsible of assoc available thumbnails
          ;; to frames and remove all children shapes from objects if
          ;; thumbnails is available
          (assoc-thumbnails [objects page-id thumbnails]
            (loop [objects objects
                   frames  (filter cph/frame-shape? (vals objects))]

              (if-let [frame  (-> frames first)]
                (let [frame-id (:id frame)
                      object-id (str page-id frame-id)
                      frame (if-let [thumb (get thumbnails object-id)]
                              (assoc frame :thumbnail thumb :shapes [])
                              (dissoc frame :thumbnail))

                      children-ids
                      (cph/get-children-ids objects frame-id)

                      bounds
                      (when (:show-content frame)
                        (gsh/selection-rect (concat [frame] (->> children-ids (map (d/getf objects))))))

                      frame
                      (cond-> frame
                        (some? bounds)
                        (assoc :children-bounds bounds))]

                  (if (:thumbnail frame)
                    (recur (-> objects
                               (assoc frame-id frame)
                               (d/without-keys children-ids))
                           (rest frames))
                    (recur (assoc objects frame-id frame)
                           (rest frames))))

                objects)))]

    (binding [pmap/*load-fn* (partial load-pointer conn id)]
      (let [frame     (get-thumbnail-frame data)
            frame-id  (:id frame)
            page-id   (or (:page-id frame)
                          (-> data :pages first))

            page      (dm/get-in data [:pages-index page-id])
            page      (cond-> page (pmap/pointer-map? page) deref)
            frame-ids (if (some? frame) (list frame-id) (map :id (ctt/get-frames (:objects page))))

            obj-ids   (map #(str page-id %) frame-ids)
            thumbs    (get-object-thumbnails conn id obj-ids)]

        (cond-> page
          ;; If we have frame, we need to specify it on the page level
          ;; and remove the all other unrelated objects.
          (some? frame-id)
          (-> (assoc :thumbnail-frame-id frame-id)
              (update :objects filter-objects frame-id))

          ;; Assoc the available thumbnails and prune not visible shapes
          ;; for avoid transfer unnecessary data.
          :always
          (update :objects assoc-thumbnails page-id thumbs))))))

(s/def ::get-file-data-for-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id]
          :opt-un [::features]))

(sv/defmethod ::get-file-data-for-thumbnail
  "Retrieves the data for generate the thumbnail of the file. Used
  mainly for render thumbnails on dashboard."
  {::doc/added "1.17"}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id features] :as props}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    ;; NOTE: we force here the "storage/pointer-map" feature, because
    ;; it used internally only and is independent if user supports it
    ;; or not.
    (let [feat (into #{"storage/pointer-map"} features)
          file (get-file conn file-id feat)]
      {:file-id file-id
       :revn (:revn file)
       :page (get-file-data-for-thumbnail conn file)})))

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

(s/def ::rename-file
  (s/keys :req [::rpc/profile-id]
          :req-un [::name ::id]))

(sv/defmethod ::rename-file
  {::doc/added "1.17"
   ::webhooks/event? true}
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

(defn absorb-library
  "Find all files using a shared library, and absorb all library assets
  into the file local libraries"
  [conn {:keys [id] :as params}]
  (let [library (db/get-by-id conn :file id)]
    (when (:is-shared library)
      (let [ldata (-> library decode-row pmg/migrate-file :data)]
        (binding [pmap/*load-fn* (partial load-pointer conn id)]
          (load-all-pointers! ldata))
        (->> (db/query conn :file-library-rel {:library-file-id id})
             (map :file-id)
             (keep #(db/get-by-id conn :file % ::db/check-deleted? false))
             (map decode-row)
             (map pmg/migrate-file)
             (run! (fn [{:keys [id data revn] :as file}]
                     (binding [pmap/*tracked* (atom {})
                               pmap/*load-fn* (partial load-pointer conn id)]
                       (let [data (ctf/absorb-assets data ldata)]
                         (db/update! conn :file
                                     {:revn (inc revn)
                                      :data (blob/encode data)
                                      :modified-at (dt/now)}
                                     {:id id}))
                       (persist-pointers! conn id)))))))))

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


;; --- MUTATION COMMAND: upsert-file-object-thumbnail

(def sql:upsert-object-thumbnail
  "insert into file_object_thumbnail(file_id, object_id, data)
   values (?, ?, ?)
       on conflict(file_id, object_id) do
          update set data = ?;")

(defn upsert-file-object-thumbnail!
  [conn {:keys [file-id object-id data]}]
  (if data
    (db/exec-one! conn [sql:upsert-object-thumbnail file-id object-id data data])
    (db/delete! conn :file-object-thumbnail {:file-id file-id :object-id object-id})))

(s/def ::data (s/nilable ::us/string))
(s/def ::thumbs/object-id ::us/string)
(s/def ::upsert-file-object-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::thumbs/object-id]
          :opt-un [::data]))

(sv/defmethod ::upsert-file-object-thumbnail
  {::doc/added "1.17"
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (upsert-file-object-thumbnail! conn params)
    nil))

;; --- MUTATION COMMAND: upsert-file-thumbnail

(def ^:private sql:upsert-file-thumbnail
  "insert into file_thumbnail (file_id, revn, data, props)
   values (?, ?, ?, ?::jsonb)
       on conflict(file_id, revn) do
          update set data = ?, props=?, updated_at=now();")

(defn- upsert-file-thumbnail!
  [conn {:keys [file-id revn data props]}]
  (let [props (db/tjson (or props {}))]
    (db/exec-one! conn [sql:upsert-file-thumbnail
                        file-id revn data props data props])))

(s/def ::revn ::us/integer)
(s/def ::props map?)
(s/def ::upsert-file-thumbnail
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::revn ::data ::props]))

(sv/defmethod ::upsert-file-thumbnail
  "Creates or updates the file thumbnail. Mainly used for paint the
  grid thumbnails."
  {::doc/added "1.17"
   ::audit/skip true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (when-not (db/read-only? conn)
      (upsert-file-thumbnail! conn params))
    nil))

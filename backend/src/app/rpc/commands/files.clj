;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.files.migrations :as fmg]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.schema.desc-js-like :as-alias smdj]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.uri :as uri]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.fdata :as feat.fdata]
   [app.features.logical-deletion :as ldel]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.msgbus :as mbus]
   [app.redis :as rds]
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
   [app.worker :as wrk]
   [cuerdas.core :as str]))

;; --- FEATURES

(defn resolve-public-uri
  [media-id]
  (when media-id
    (str (cf/get :public-uri) "/assets/by-id/" media-id)))

;; --- HELPERS

(def long-cache-duration
  (ct/duration {:days 7}))

(defn decode-row
  [{:keys [features] :as row}]
  (when row
    (cond-> row
      (db/pgarray? features) (assoc :features (db/decode-pgarray features #{})))))

(defn check-version!
  [file]
  (let [version (:version file)]
    (when (> version fmg/version)
      (ex/raise :type :restriction
                :code :file-version-not-supported
                :hint "file version is greated that the maximum"
                :file-version version
                :max-version fmg/version))
    file))


;; --- FILE DATA

;; --- FILE PERMISSIONS


(def ^:private sql:file-permissions
  "select fpr.is_owner,
          fpr.is_admin,
          fpr.can_edit
     from file_profile_rel as fpr
    inner join file as f on (f.id = fpr.file_id)
    where fpr.file_id = ?
      and fpr.profile_id = ?
      and f.deleted_at is null
   union all
   select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = ?
      and tpr.profile_id = ?
      and f.deleted_at is null
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    inner join file as f on (f.project_id = ppr.project_id)
    where f.id = ?
      and ppr.profile_id = ?
      and f.deleted_at is null")

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- COMMAND QUERY: get-file (by id)

(def schema:file
  [:map {:title "File"}
   [:id ::sm/uuid]
   [:features ::cfeat/features]
   [:has-media-trimmed ::sm/boolean]
   [:comment-thread-seqn [::sm/int {:min 0}]]
   [:name [:string {:max 250}]]
   [:revn [::sm/int {:min 0}]]
   [:vern [::sm/int {:min 0}]]
   [:modified-at ::ct/inst]
   [:is-shared ::sm/boolean]
   [:project-id ::sm/uuid]
   [:created-at ::ct/inst]
   [:data {:optional true} ::sm/any]])

(def schema:permissions-mixin
  [:map {:title "PermissionsMixin"}
   [:permissions perms/schema:permissions]])

(def schema:file-with-permissions
  [:merge {:title "FileWithPermissions"}
   schema:file
   schema:permissions-mixin])

(def ^:private
  schema:get-file
  [:map {:title "get-file"}
   [:features {:optional true} ::cfeat/features]
   [:id ::sm/uuid]])

(defn get-minimal-file
  [cfg id & {:as opts}]
  (let [opts (assoc opts ::sql/columns [:id :modified-at :deleted-at :revn :vern])]
    (db/get cfg :file {:id id} opts)))

(defn- get-minimal-file-with-perms
  [cfg {:keys [:id ::rpc/profile-id]}]
  (let [mfile (get-minimal-file cfg id)
        perms (get-permissions cfg profile-id id)]
    (assoc mfile :permissions perms)))

(defn get-file-etag
  [{:keys [::rpc/profile-id]} {:keys [modified-at revn vern permissions]}]
  (str profile-id "/" revn "/" vern "/" (hash fmg/available-migrations) "/"
       (ct/format-inst modified-at :iso)
       "/"
       (uri/map->query-string permissions)))

(sv/defmethod ::get-file
  "Retrieve a file by its ID. Only authenticated users."
  {::doc/added "1.17"
   ::cond/get-object #(get-minimal-file-with-perms %1 %2)
   ::cond/key-fn get-file-etag
   ::sm/params schema:get-file
   ::sm/result schema:file-with-permissions
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id project-id] :as params}]
  ;; The COND middleware makes initial request for a file and
  ;; permissions when the incoming request comes with an
  ;; ETAG. When ETAG does not matches, the request is resolved
  ;; and this code is executed, in this case the permissions
  ;; will be already prefetched and we just reuse them instead
  ;; of making an additional database queries.
  (let [perms (or (:permissions (::cond/object params))
                  (get-permissions conn profile-id id))]
    (check-read-permissions! perms)

    (let [team (teams/get-team conn
                               :profile-id profile-id
                               :project-id project-id
                               :file-id id)

          file (-> (bfc/get-file cfg id
                                 :project-id project-id)
                   (assoc :permissions perms)
                   (check-version!))]

      (-> (cfeat/get-team-enabled-features cf/flags team)
          (cfeat/check-client-features! (:features params))
          (cfeat/check-file-features! (:features file)))

      (as-> file file
        ;; This operation is needed for backward comapatibility with
        ;; frontends that does not support pointer-map resolution
        ;; mechanism; this just resolves the pointers on backend and
        ;; return a complete file
        (if (and (contains? (:features file) "fdata/pointer-map")
                 (not (contains? (:features params) "fdata/pointer-map")))
          (feat.fdata/realize-pointers cfg file)
          file)

        ;; This operation is needed for backward comapatibility with
        ;; frontends that does not support objects-map mechanism; this
        ;; just converts all objects map instaces to plain maps
        (if (and (contains? (:features file) "fdata/objects-map")
                 (not (contains? (:features params) "fdata/objects-map")))
          (feat.fdata/realize-objects cfg file)
          file)))))

;; --- COMMAND QUERY: get-file-fragment (by id)

(def schema:file-fragment
  [:map {:title "FileFragment"}
   [:id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:created-at ::ct/inst]
   [:content ::sm/any]])

(def schema:get-file-fragment
  [:map {:title "get-file-fragment"}
   [:file-id ::sm/uuid]
   [:fragment-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]])

(defn- get-file-fragment
  [cfg file-id fragment-id]
  (some-> (db/get cfg :file-data {:file-id file-id :id fragment-id :type "fragment"})
          (update :data blob/decode)))

(sv/defmethod ::get-file-fragment
  "Retrieve a file fragment by its ID. Only authenticated users."
  {::doc/added "1.17"
   ::rpc/auth false
   ::sm/params schema:get-file-fragment
   ::sm/result schema:file-fragment}
  [cfg {:keys [::rpc/profile-id file-id fragment-id share-id]}]
  (db/run! cfg (fn [cfg]
                 (let [perms (get-permissions cfg profile-id file-id share-id)]
                   (check-read-permissions! perms)
                   (-> (get-file-fragment cfg file-id fragment-id)
                       (rph/with-http-cache long-cache-duration))))))

;; --- COMMAND QUERY: get-project-files

(def ^:private sql:project-files
  "select f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.revn,
          f.vern,
          f.is_shared,
          ft.media_id AS thumbnail_id,
          p.team_id
     from file as f
     inner join project as p on (p.id = f.project_id)
     left join file_thumbnail as ft on (ft.file_id = f.id
                                        and ft.revn = f.revn
                                        and ft.deleted_at is null)
    where f.project_id = ?
      and f.deleted_at is null
    order by f.modified_at desc")

(defn get-project-files
  [conn project-id]
  (db/exec! conn [sql:project-files project-id]))

(def schema:get-project-files
  [:map {:title "get-project-files"}
   [:project-id ::sm/uuid]])

(def schema:files
  [:vector schema:file])

(sv/defmethod ::get-project-files
  "Get all files for the specified project."
  {::doc/added "1.17"
   ::sm/params schema:get-project-files
   ::sm/result schema:files}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id]}]
  (dm/with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (get-project-files conn project-id)))

;; --- COMMAND QUERY: has-file-libraries

(declare get-has-file-libraries)

(def schema:has-file-libraries
  [:map {:title "has-file-libraries"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::has-file-libraries
  "Checks if the file has libraries. Returns a boolean"
  {::doc/added "1.15.1"
   ::sm/params schema:has-file-libraries
   ::sm/result ::sm/boolean}
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


;; --- COMMAND QUERY: get-library-usage


(declare get-library-usage)

(def schema:get-library-usage
  [:map {:title "get-library-usage"}
   [:file-id ::sm/uuid]])
:sample
(sv/defmethod ::get-library-usage
  "Gets the number of files that use the specified library."
  {::doc/added "2.10.0"
   ::sm/params schema:get-library-usage
   ::sm/result ::sm/int}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! pool profile-id file-id)
    (get-library-usage conn file-id)))

(def ^:private sql:get-library-usage
  "SELECT COUNT(*) AS used
     FROM file_library_rel AS flr
     JOIN file AS fl ON (flr.library_file_id = fl.id)
    WHERE flr.library_file_id = ?::uuid
      AND (fl.deleted_at IS NULL OR
           fl.deleted_at > now())")

(defn- get-library-usage
  [conn file-id]
  (let [row (db/exec-one! conn [sql:get-library-usage file-id])]
    {:used-in (:used row)}))


;; --- QUERY COMMAND: get-page


(defn- prune-objects
  "Given the page data and the object-id returns the page data with all
  other not needed objects removed from the `:objects` data
  structure."
  [page id-or-ids]
  (update page :objects (fn [objects]
                          (reduce (fn [result object-id]
                                    (->> (cfh/get-children-with-self objects object-id)
                                         (filter some?)
                                         (d/index-by :id)
                                         (merge result)))
                                  {}
                                  (if (uuid? id-or-ids)
                                    [id-or-ids]
                                    id-or-ids)))))

(defn- prune-thumbnails
  "Given the page data, removes the `:thumbnail` prop from all
  shapes."
  [page]
  (update page :objects update-vals #(dissoc % :thumbnail)))

(defn get-page
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file-id page-id object-id share-id] :as params}]

  (when (and (uuid? object-id)
             (not (uuid? page-id)))
    (ex/raise :type :validation
              :code :params-validation
              :hint "page-id is required when object-id is provided"))

  (let [perms (get-permissions conn profile-id file-id share-id)

        file  (bfc/get-file cfg file-id :read-only? true)

        proj  (db/get conn :project {:id (:project-id file)})

        team  (-> (db/get conn :team {:id (:team-id proj)})
                  (teams/decode-row))

        _     (-> (cfeat/get-team-enabled-features cf/flags team)
                  (cfeat/check-client-features! (:features params))
                  (cfeat/check-file-features! (:features file)))

        page  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
                (let [page-id (or page-id (-> file :data :pages first))
                      page    (dm/get-in file [:data :pages-index page-id])]
                  (if (pmap/pointer-map? page)
                    (deref page)
                    page)))]

    (when-not perms
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "object not found"))

    (cond-> (prune-thumbnails page)
      (some? object-id)
      (prune-objects object-id))))

(def schema:get-page
  [:map {:title "get-page"}
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]
   [:object-id {:optional true} [:or ::sm/uuid [::sm/set ::sm/uuid]]]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-page
  "Retrieves the page data from file and returns it. If no page-id is
  specified, the first page will be returned. If object-id is
  specified, only that object and its children will be returned in the
  page objects data structure.

  If you specify the object-id, the page-id parameter becomes
  mandatory.

  Mainly used for rendering purposes on the exporter. It does not
  accepts client features."
  {::doc/added "1.17"
   ::sm/params schema:get-page}
  [cfg {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (check-read-permissions! conn profile-id file-id share-id)
                (get-page cfg (assoc params :profile-id profile-id)))))

;; --- COMMAND QUERY: get-team-shared-files

(defn- get-components-with-variants
  "Return a set with all the variant-ids, and a list of components, but
  with only one component by variant.

  Returns a vector of unique components and a set of all variant ids"
  [fdata]
  (loop [variant-ids #{}
         components' []
         components  (ctkl/components-seq fdata)]
    (if-let [{:keys [variant-id] :as component} (first components)]
      (cond
        (nil? variant-id)
        (recur variant-ids
               (conj components' component)
               (rest components))

        (contains? variant-ids variant-id)
        (recur variant-ids
               components'
               (rest components))

        :else
        (recur (conj variant-ids variant-id)
               (conj components' component)
               (rest components)))

      [(d/index-by :id components') variant-ids])))

(defn- sample-assets
  [assets limit]
  (let [assets (into [] (map val) assets)]
    {:count (count assets)
     :sample (->> assets
                  (sort-by #(str/lower (:name %)))
                  (into [] (take limit)))}))

(defn- calculate-library-summary
  "Calculate the file library summary (counters and samples)"
  [{:keys [data] :as file}]
  (let [load-objects
        (fn [sample]
          (mapv #(ctf/load-component-objects data %) sample))

        [components variant-ids]
        (get-components-with-variants data)

        components-sample
        (-> (sample-assets components 4)
            (update :sample load-objects))]

    {:components components-sample
     :variants {:count (count variant-ids)}
     :colors (sample-assets (:colors data) 3)
     :typographies (sample-assets (:typographies data) 3)}))

(def ^:private file-summary-cache-key-ttl
  (ct/duration {:days 30}))

(def file-summary-cache-key-prefix
  "penpot.library-summary.")

(defn- get-file-with-summary
  "Get a file without data with a summary of its local library content"
  [cfg id]
  (let [get-from-cache
        (fn [{:keys [::rds/conn]} cache-key]
          (when-let [result (rds/get conn cache-key)]
            (let [file    (bfc/get-file cfg id :load-data? false)
                  summary (t/decode-str result)]
              (-> (assoc file :library-summary summary)
                  (dissoc :data)))))

        calculate-from-db
        (fn []
          (let [file   (bfc/get-file cfg id)
                result (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)]
                         (calculate-library-summary file))]
            (-> file
                (assoc :library-summary result)
                (dissoc :legacy-data)
                (dissoc :data))))

        persist-to-cache
        (fn [{:keys [::rds/conn]} data cache-key]
          (rds/set conn cache-key (t/encode-str data)
                   (rds/build-set-args {:ex file-summary-cache-key-ttl})))]

    (if (contains? cf/flags :redis-cache)
      (let [cache-key (str file-summary-cache-key-prefix id)]
        (or (rds/run! cfg get-from-cache cache-key)
            (let [file (calculate-from-db)]
              (rds/run! cfg persist-to-cache (:library-summary file) cache-key)
              file)))
      (calculate-from-db))))

(def ^:private sql:team-shared-files
  "WITH file_library_agg AS (
      SELECT flr.file_id,
             coalesce(array_agg(flr.library_file_id) filter (WHERE flr.library_file_id IS NOT NULL), '{}') AS library_file_ids
        FROM file_library_rel flr
       GROUP BY flr.file_id
   )

   SELECT f.id,
          fla.library_file_ids,
          ft.media_id AS thumbnail_id
     FROM file AS f
    INNER JOIN project AS p ON (p.id = f.project_id)
     LEFT JOIN file_thumbnail AS ft ON (ft.file_id = f.id AND ft.revn = f.revn AND ft.deleted_at IS NULL)
     LEFT JOIN file_library_agg AS fla ON (fla.file_id = f.id)
    WHERE f.is_shared = true
      AND f.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND p.team_id = ?
    ORDER BY f.modified_at DESC")

(defn- get-team-shared-files
  [{:keys [::db/conn] :as cfg} {:keys [team-id profile-id]}]
  (teams/check-read-permissions! conn profile-id team-id)

  (let [process-row
        (fn [{:keys [id library-file-ids]}]
          (let [file (get-file-with-summary cfg id)]
            (assoc file :library-file-ids (db/decode-pgarray library-file-ids #{}))))

        xform
        (map process-row)]

    (->> (db/plan conn [sql:team-shared-files team-id] {:fetch-size 1})
         (transduce xform conj #{}))))

(def ^:private schema:get-team-shared-files
  [:map {:title "get-team-shared-files"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-team-shared-files
  "Get all file (libraries) for the specified team."
  {::doc/added "1.17"
   ::sm/params schema:get-team-shared-files}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg get-team-shared-files (assoc params :profile-id profile-id)))

;; --- COMMAND QUERY: get-file-summary

(defn- get-file-summary
  [cfg id]
  (let [file (get-file-with-summary cfg id)]
    (-> (:library-summary file)
        (assoc :name (:name file)))))

(def ^:private
  schema:get-file-summary
  [:map {:title "get-file-summary"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-file-summary
  "Retrieve a file summary by its ID. Only authenticated users."
  {::doc/added "1.20"
   ::sm/params schema:get-file-summary}
  [cfg {:keys [::rpc/profile-id id] :as params}]
  (check-read-permissions! cfg profile-id id)
  (get-file-summary cfg id))


;; --- COMMAND QUERY: get-file-libraries

(def ^:private schema:get-file-libraries
  [:map {:title "get-file-libraries"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-file-libraries
  "Get libraries used by the specified file."
  {::doc/added "1.17"
   ::sm/params schema:get-file-libraries}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id]}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (bfc/get-file-libraries conn file-id)))


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

(def ^:private schema:get-library-file-references
  [:map {:title "get-library-file-references"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-library-file-references
  "Returns all the file references that use specified file (library) id."
  {::doc/added "1.17"
   ::sm/params schema:get-library-file-references}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (check-read-permissions! conn profile-id file-id)
    (get-library-file-references conn file-id)))

;; --- COMMAND QUERY: get-team-recent-files

(def sql:team-recent-files
  "with recent_files as (
     select f.id,
            f.revn,
            f.vern,
            f.project_id,
            f.created_at,
            f.modified_at,
            f.name,
            f.is_shared,
            ft.media_id AS thumbnail_id,
            row_number() over w as row_num,
            p.team_id
       from file as f
      inner join project as p on (p.id = f.project_id)
       left join file_thumbnail as ft on (ft.file_id = f.id
                                          and ft.revn = f.revn
                                          and ft.deleted_at is null)
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

(def ^:private schema:get-team-recent-files
  [:map {:title "get-team-recent-files"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-team-recent-files
  {::doc/added "1.17"
   ::sm/params schema:get-team-recent-files}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (dm/with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (get-team-recent-files conn team-id)))

;; --- COMMAND QUERY: get-file-info


(defn- get-file-info
  [{:keys [::db/conn] :as cfg} {:keys [id] :as params}]
  (db/get conn :file
          {:id id}
          {::sql/columns [:id :deleted-at]}))

(sv/defmethod ::get-file-info
  "Retrieve minimal file info by its ID."
  {::rpc/auth false
   ::doc/added "2.2.0"
   ::sm/params schema:get-file}
  [cfg params]
  (db/tx-run! cfg get-file-info params))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- MUTATION COMMAND: rename-file

(defn rename-file
  [conn {:keys [id name]}]
  (db/update! conn :file
              {:name name
               :modified-at (ct/now)}
              {:id id}
              {::db/return-keys true}))

(sv/defmethod ::rename-file
  {::doc/added "1.17"
   ::webhooks/event? true

   ::sm/webhook
   [:map {:title "RenameFileEvent"}
    [:id ::sm/uuid]
    [:project-id ::sm/uuid]
    [:name [:string {:max 250}]]
    [:created-at ::ct/inst]
    [:modified-at ::ct/inst]]

   ::sm/params
   [:map {:title "RenameFileParams"}
    [:name [:string {:min 1 :max 250}]]
    [:id ::sm/uuid]]

   ::sm/result
   [:map {:title "SimplifiedFile"}
    [:id ::sm/uuid]
    [:name [:string {:max 250}]]
    [:created-at ::ct/inst]
    [:modified-at ::ct/inst]]

   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [file (rename-file conn params)]
    (rph/with-meta
      (select-keys file [:id :name :created-at :modified-at])
      {::audit/props {:project-id (:project-id file)
                      :created-at (:created-at file)
                      :modified-at (:modified-at file)}})))

;; --- MUTATION COMMAND: set-file-shared

(def ^:private sql:get-referenced-files
  "SELECT f.id
     FROM file_library_rel AS flr
    INNER JOIN file AS f ON (f.id = flr.file_id)
    WHERE flr.library_file_id = ?
      AND (f.deleted_at IS NULL OR f.deleted_at > now())
    ORDER BY f.created_at ASC;")

(defn- absorb-library-by-file!
  [cfg ldata file-id]

  (assert (db/connection-map? cfg)
          "expected cfg with valid connection")

  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)
            pmap/*tracked* (pmap/create-tracked)]
    (let [file (-> (bfc/get-file cfg file-id
                                 :include-deleted? true
                                 :lock-for-update? true)
                   (update :data ctf/absorb-assets ldata))]

      (l/trc :hint "library absorbed"
             :library-id (str (:id ldata))
             :file-id (str file-id))

      (bfc/update-file! cfg {:id file-id
                             :migrations (:migrations file)
                             :revn (inc (:revn file))
                             :data (:data file)
                             :modified-at (ct/now)
                             :has-media-trimmed false}))))

(defn- absorb-library
  "Find all files using a shared library, and absorb all library assets
  into the file local libraries"
  [cfg {:keys [id data] :as library}]

  (assert (db/connection-map? cfg)
          "expected cfg with valid connection")

  (let [ids (->> (db/exec! cfg [sql:get-referenced-files id])
                 (sequence bfc/xf-map-id))]

    (l/trc :hint "absorbing library"
           :library-id (str id)
           :files (str/join "," (map str ids)))

    (run! (partial absorb-library-by-file! cfg data) ids)
    library))

(defn absorb-library!
  [{:keys [::db/conn] :as cfg} id]
  (let [file (-> (bfc/get-file cfg id
                               :realize? true
                               :lock-for-update? true
                               :include-deleted? true)
                 (check-version!))

        proj (db/get* conn :project {:id (:project-id file)}
                      {::db/remove-deleted false})
        team (-> (db/get* conn :team {:id (:team-id proj)}
                          {::db/remove-deleted false})
                 (teams/decode-row))]

    (-> (cfeat/get-team-enabled-features cf/flags team)
        (cfeat/check-file-features! (:features file)))

    (absorb-library cfg file)))

(defn- set-file-shared
  [{:keys [::db/conn] :as cfg} {:keys [profile-id id] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [file (db/get-by-id conn :file id {:columns [:id :name :is-shared]})
        file (cond
               (and (true? (:is-shared file))
                    (false? (:is-shared params)))
               ;; When we disable shared flag on an already shared
               ;; file, we need to perform more complex operation,
               ;; so in this case we retrieve the complete file and
               ;; perform all required validations.
               (let [file (-> (absorb-library! cfg id)
                              (assoc :is-shared false))]
                 (db/delete! conn :file-library-rel {:library-file-id id})
                 (db/update! conn :file
                             {:is-shared false
                              :modified-at (ct/now)}
                             {:id id})
                 (select-keys file [:id :name :is-shared]))

               (and (false? (:is-shared file))
                    (true? (:is-shared params)))
               (let [file (assoc file :is-shared true)]
                 (db/update! conn :file
                             {:is-shared true
                              :modified-at (ct/now)}
                             {:id id})
                 file)

               :else
               (ex/raise :type :validation
                         :code :invalid-shared-state
                         :hint "unexpected state found"
                         :params-is-shared (:is-shared params)
                         :file-is-shared (:is-shared file)))]

    (rph/with-meta
      (select-keys file [:id :name :is-shared])
      {::audit/props {:name (:name file)
                      :project-id (:project-id file)
                      :is-shared (:is-shared file)}})))

(def ^:private
  schema:set-file-shared
  [:map {:title "set-file-shared"}
   [:id ::sm/uuid]
   [:is-shared ::sm/boolean]])

(sv/defmethod ::set-file-shared
  {::doc/added "1.17"
   ::webhooks/event? true
   ::sm/params schema:set-file-shared}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg set-file-shared (assoc params :profile-id profile-id)))

;; --- MUTATION COMMAND: delete-file

(defn- mark-file-deleted
  [conn team file-id]
  (let [delay (ldel/get-deletion-delay team)
        file  (db/update! conn :file
                          {:deleted-at (ct/in-future delay)}
                          {:id file-id}
                          {::db/return-keys [:id :name :is-shared :deleted-at
                                             :project-id :created-at :modified-at]})]
    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :file
                                :deleted-at (:deleted-at file)
                                :id file-id}})
    file))

(def ^:private
  schema:delete-file
  [:map {:title "delete-file"}
   [:id ::sm/uuid]])

(defn- delete-file
  [{:keys [::db/conn] :as cfg} {:keys [profile-id id] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [team (teams/get-team conn
                             :profile-id profile-id
                             :file-id id)
        file (mark-file-deleted conn team id)
        msgbus (::mbus/msgbus cfg)]

    (mbus/pub! msgbus
               :topic id
               :message {:type :file-deleted
                         :file-id id
                         :profile-id profile-id})

    (rph/with-meta (rph/wrap)
      {::audit/props {:project-id (:project-id file)
                      :name (:name file)
                      :created-at (:created-at file)
                      :modified-at (:modified-at file)}})))

(sv/defmethod ::delete-file
  {::doc/added "1.17"
   ::webhooks/event? true
   ::sm/params schema:delete-file}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg delete-file (assoc params :profile-id profile-id)))

;; --- MUTATION COMMAND: link-file-to-library

(def sql:link-file-to-library
  "insert into file_library_rel (file_id, library_file_id)
   values (?, ?)
       on conflict do nothing;")

(defn link-file-to-library
  [conn {:keys [file-id library-id] :as params}]
  (db/exec-one! conn [sql:link-file-to-library file-id library-id]))

(def ^:private
  schema:link-file-to-library
  [:map {:title "link-file-to-library"}
   [:file-id ::sm/uuid]
   [:library-id ::sm/uuid]])

(sv/defmethod ::link-file-to-library
  "Link a file to a library. Returns the recursive list of libraries used by that library"
  {::doc/added "1.17"
   ::webhooks/event? true
   ::sm/params schema:link-file-to-library}
  [cfg {:keys [::rpc/profile-id file-id library-id] :as params}]
  (when (= file-id library-id)
    (ex/raise :type :validation
              :code :invalid-library
              :hint "A file cannot be linked to itself"))

  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (check-edition-permissions! conn profile-id file-id)
                (check-edition-permissions! conn profile-id library-id)
                (link-file-to-library conn params)
                (bfc/get-libraries cfg [library-id]))))

;; --- MUTATION COMMAND: unlink-file-from-library

(defn unlink-file-from-library
  [conn {:keys [file-id library-id]}]
  (db/delete! conn :file-library-rel
              {:file-id file-id
               :library-file-id library-id}))

(def ^:private schema:unlink-file-to-library
  [:map {:title "unlink-file-to-library"}
   [:file-id ::sm/uuid]
   [:library-id ::sm/uuid]])

(sv/defmethod ::unlink-file-from-library
  {::doc/added "1.17"
   ::webhooks/event? true
   ::sm/params schema:unlink-file-to-library
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (check-edition-permissions! conn profile-id file-id)
  (unlink-file-from-library conn params)
  nil)

;; --- MUTATION COMMAND: update-sync

(defn update-sync
  [conn {:keys [file-id library-id] :as params}]
  (db/update! conn :file-library-rel
              {:synced-at (ct/now)}
              {:file-id file-id
               :library-file-id library-id}
              {::db/return-keys true}))

(def ^:private schema:update-file-library-sync-status
  [:map {:title "update-file-library-sync-status"}
   [:file-id ::sm/uuid]
   [:library-id ::sm/uuid]])

(sv/defmethod ::update-file-library-sync-status
  "Update the synchronization status of a file->library link"
  {::doc/added "1.17"
   ::sm/params schema:update-file-library-sync-status
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id file-id] :as params}]
  (check-edition-permissions! conn profile-id file-id)
  (update-sync conn params))

;; --- MUTATION COMMAND: ignore-sync

(defn ignore-sync
  [conn {:keys [file-id date] :as params}]
  (db/update! conn :file
              {:ignore-sync-until date
               :modified-at (ct/now)}
              {:id file-id}
              {::db/return-keys true}))

(def ^:private schema:ignore-file-library-sync-status
  [:map {:title "ignore-file-library-sync-status"}
   [:file-id ::sm/uuid]
   [:date ::ct/inst]])

;; TODO: improve naming
(sv/defmethod ::ignore-file-library-sync-status
  "Ignore updates in linked files"
  {::doc/added "1.17"
   ::sm/params schema:ignore-file-library-sync-status
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id file-id] :as params}]
  (check-edition-permissions! conn profile-id file-id)
  (->  (ignore-sync conn params)
       (update :features db/decode-pgarray #{})))

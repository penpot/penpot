;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.file
  "Admin file commands, served through `/api/admin/methods`.

  Access control lives in `wrap-authentication` (the `\"superuser\"`
  permission), not here, so a new command cannot forget it."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cfc]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.jobs :as jobs]
   [app.rpc :as-alias rpc]
   [app.rpc.admin.list :as adml]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as doc]
   [app.srepl.helpers :as h]
   [app.storage.tmp :as tmp]
   [app.tasks.restore-object]
   [app.util.services :as sv]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [yetti.response :as yres]))

(def schema:validation-error
  [:map
   [:code ::sm/text]
   [:hint ::sm/text]
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]
   [:shape-id {:optional true} ::sm/uuid]])

(defn- sanitize-error
  "Trim a validation error down to JSON-safe scalars.

  The raw error carries the broken `:shape` (arbitrary, potentially
  huge or unserializable data) and `:args`; neither travels over
  the wire."
  [error]
  (d/without-nils
   {:code     (name (:code error))
    :hint     (:hint error)
    :file-id  (:file-id error)
    :page-id  (:page-id error)
    :shape-id (:shape-id error)}))

(defn- get-live-file
  "Fetch a file that is neither missing nor marked for deletion."
  [cfg file-id]
  (or (bfc/get-file cfg file-id
                    :throw-if-not-exists? false
                    :realize? true)
      (ex/raise :type :not-found
                :code :file-not-found
                :hint (str "file " file-id " not found"))))

(defn- validate-file*
  [cfg file-id]
  (let [file   (get-live-file cfg file-id)
        libs   (bfc/get-resolved-file-libraries cfg file)
        errors (or (cfv/validate-file file libs) [])]
    (mapv sanitize-error errors)))

(def schema:validate-file-params
  [:map
   [:file-id ::sm/uuid]])

(def schema:validate-file-result
  [:map
   [:file-id ::sm/uuid]
   [:errors [:vector schema:validation-error]]])

(sv/defmethod ::validate-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:validate-file-params
   ::sm/result schema:validate-file-result}
  [cfg {:keys [file-id]}]
  (let [errors (db/tx-run! (assoc cfg ::db/rollback true)
                           (fn [cfg] (validate-file* cfg file-id)))]
    {:file-id file-id
     :errors  errors}))

(def schema:repair-file-params
  [:map
   [:file-id ::sm/uuid]
   [:skip-snapshot {:optional true} ::sm/boolean]])

(def schema:repair-file-result
  [:map
   [:file-id ::sm/uuid]
   [:errors [:vector schema:validation-error]]
   [:changes ::sm/int]
   [:snapshot-taken ::sm/boolean]])

;; NOTE: no `::db/transaction` here on purpose: `h/process-file!`
;; opens its own transaction, and nesting `tx-run!` calls reuses the
;; outer one instead of a savepoint.
(sv/defmethod ::repair-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:repair-file-params
   ::sm/result schema:repair-file-result}
  [cfg {:keys [file-id skip-snapshot ::rpc/profile-id]}]
  ;; Existence (and not-deleted) check up front: `process-file!`
  ;; silently does nothing on a missing file.
  (db/tx-run! (assoc cfg ::db/rollback true)
              (fn [cfg] (get-live-file cfg file-id)))

  (let [state     (atom {:changes 0})
        update-fn (fn [file libs _opts]
                    (let [errors (or (cfv/validate-file file libs) [])]
                      (if (empty? errors)
                        file
                        (let [changes (cfr/repair-file file libs errors)]
                          (swap! state assoc :changes (count changes))
                          (-> file
                              (update :data cfc/process-changes changes))))))
        wrote?    (db/tx-run! cfg h/process-file!
                              file-id update-fn
                              {::h/with-libraries? true
                               ::h/validate? false
                               ::h/profile-id profile-id
                               ::h/snapshot-label (when-not skip-snapshot "repair")})
        errors    (db/tx-run! (assoc cfg ::db/rollback true)
                              (fn [cfg] (validate-file* cfg file-id)))]
    {:file-id        file-id
     :errors         errors
     :changes        (:changes @state)
     :snapshot-taken (boolean (and wrote? (not skip-snapshot)))}))

(def ^:private files-default-limit 50)
(def ^:private files-max-limit 200)

(def schema:file-summary
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:project-id ::sm/uuid]
   [:project-name ::sm/text]
   [:team-id ::sm/uuid]
   [:team-name ::sm/text]
   [:created-at ct/schema:inst]
   [:modified-at ct/schema:inst]
   [:deleted-at {:optional true} ct/schema:inst]])

(def schema:get-files-params
  [:map {:title "get-files-params"}
   [:since {:optional true} ct/schema:inst]
   [:since-id {:optional true} ::sm/uuid]
   [:limit {:optional true}
    [:and ::sm/int [:fn #(<= 1 % files-max-limit)]]]
   [:id {:optional true} ::sm/uuid]
   [:team-id {:optional true} ::sm/uuid]
   [:project-id {:optional true} ::sm/uuid]
   [:deleted {:optional true} ::sm/boolean]])

(def schema:get-files-result
  [:map
   [:items [:vector schema:file-summary]]
   [:next-since {:optional true} ct/schema:inst]
   [:next-id {:optional true} ::sm/uuid]])

(defn- build-files-list-query
  "List files newest-first with keyset pagination.

  Lookup is by exact `id` only; see `app.rpc.admin.list` for the
  shared list contract. The parent-project/team guards stay
  regardless."
  [{:keys [since since-id id team-id project-id deleted limit]
    :or {limit files-default-limit}}]
  (let [clauses   (keep identity
                        [(when id
                           {:where "f.id = ?"
                            :params [id]})
                         (when team-id
                           {:where "p.team_id = ?"
                            :params [team-id]})
                         (when project-id
                           {:where "f.project_id = ?"
                            :params [project-id]})
                         (adml/deleted-clause "f.deleted_at" deleted)
                         (adml/since-clause "f.created_at" "f.id" since since-id)])
        sql-parts  (map :where clauses)
        sql-params (mapcat :params clauses)
        sql        (str "SELECT f.id, f.name, f.project_id, "
                        "p.name AS project_name, "
                        "p.team_id AS team_id, "
                        "t.name AS team_name, "
                        "f.created_at, f.modified_at, f.deleted_at "
                        "FROM file AS f "
                        "JOIN project AS p ON (p.id = f.project_id) "
                        "JOIN team AS t ON (t.id = p.team_id) "
                        "WHERE p.deleted_at IS NULL "
                        "AND t.deleted_at IS NULL "
                        (when (seq sql-parts)
                          (str "AND " (str/join " AND " sql-parts) " "))
                        "ORDER BY f.created_at DESC, f.id DESC "
                        "LIMIT ?")]
    (into [sql] (concat sql-params [limit]))))

(sv/defmethod ::get-files
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-files-params
   ::sm/result schema:get-files-result}
  [cfg params]
  (let [[limit params]   (adml/with-fetch-limit params files-default-limit files-max-limit)
        [sql & sql-args] (build-files-list-query params)
        rows             (db/exec! cfg (into [sql] sql-args))]
    (adml/paginate rows limit)))

(def schema:get-file-params
  [:map
   [:id ::sm/uuid]])

(def ^:private sql:file
  (str "SELECT f.id, f.name, f.project_id, "
       "p.name AS project_name, "
       "p.team_id AS team_id, "
       "t.name AS team_name, "
       "f.created_at, f.modified_at, f.deleted_at "
       "FROM file AS f "
       "JOIN project AS p ON (p.id = f.project_id) "
       "JOIN team AS t ON (t.id = p.team_id) "
       "WHERE f.id = ?"))

(sv/defmethod ::get-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:get-file-params
   ::sm/result schema:file-summary}
  [cfg {:keys [id]}]
  (or (some-> (db/exec-one! cfg [sql:file id])
              (d/without-nils))
      (ex/raise :type :not-found
                :code :file-not-found
                :hint (str "file " id " not found"))))

(def schema:restore-file-params
  [:map {:title "restore-file"}
   [:id ::sm/uuid]])

(def schema:restore-file-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::restore-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:restore-file-params
   ::sm/result schema:restore-file-result}
  [cfg {:keys [id]}]
  (let [row (db/get* cfg :file {:id id} {::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :file-not-found
                :hint (str "file " id " not found"))))
  ;; NOTE: the file row and its project/team chain are restored
  ;; inline so they read back instantly; the file rows cascade runs
  ;; as a worker task. Refuses ownerless teams before writing
  ;; anything. Profiles are never revived by a file restore.
  (cascade/restore-chain (::db/conn cfg) :file id)
  (db/update! cfg :file
              {:deleted-at nil
               :has-media-trimmed false}
              {:id id}
              {::db/return-keys false})
  (jobs/submit cfg
               {::jobs/name :restore-object
                ::jobs/params {:object :file
                               :id id}})
  {:id id})

(def schema:delete-file-params
  [:map {:title "delete-file"}
   [:id ::sm/uuid]])

(def schema:delete-file-result
  [:map
   [:id ::sm/uuid]])

(sv/defmethod ::delete-file
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::db/transaction true
   ::sm/params schema:delete-file-params
   ::sm/result schema:delete-file-result}
  [cfg {:keys [id]}]
  (let [row (db/get* cfg :file {:id id} {::db/remove-deleted false})]
    (when-not row
      (ex/raise :type :not-found
                :code :file-not-found
                :hint (str "file " id " not found")))
    (if (some? (:deleted-at row))
      ;; NOTE: already deleted is a no-op success: the date stays
      ;; untouched and no task is submitted.
      {:id id}
      (let [deleted-at (ct/minus (ct/now) (cf/get-deletion-delay))]
        ;; NOTE: the file row is stamped inline so it reads back
        ;; instantly; unsharing, the rows and the libraries cascade
        ;; run as a worker task with the same date.
        (db/update! cfg :file
                    {:deleted-at deleted-at}
                    {:id id}
                    {::db/return-keys false})
        (jobs/submit cfg
                     {::jobs/name :delete-object
                      ::jobs/params {:object :file
                                     :deleted-at deleted-at
                                     :id id}})
        {:id id}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE TRANSFER (same behavior as the old `/dbg` handlers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; NOTE: no `::sm/result` on these two on purpose: like the SSE
;; commands, they return a raw response function instead of data
;; (a download stream, a plain `"OK"`), so there is no transit/JSON
;; shape to validate.

(def schema:export-files-params
  [:map {:title "export-files-params"}
   [:file-ids [:vector {:min 1} ::sm/uuid]]
   [:includelibs {:optional true} ::sm/boolean]
   [:clone {:optional true} ::sm/boolean]
   [:embedassets {:optional true} ::sm/boolean]])

(sv/defmethod ::export-files
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:export-files-params}
  [{:keys [::db/pool] :as cfg} {:keys [file-ids includelibs clone embedassets
                                       ::rpc/profile-id]}]
  (let [path (tmp/tempfile :prefix "penpot.export." :min-age "30m")]
    (with-open [output (io/output-stream path)]
      (-> cfg
          (assoc ::bfc/ids (set file-ids))
          (assoc ::bfc/embed-assets (boolean embedassets))
          (assoc ::bfc/include-libraries (boolean includelibs))
          (bf.v3/export-files! output)))

    (if clone
      (let [profile    (profile/get-profile pool profile-id)
            project-id (:default-project-id profile)
            team       (teams/get-team pool
                                       :profile-id profile-id
                                       :project-id project-id)
            cfg        (assoc cfg
                              ::bfc/overwrite false
                              ::bfc/profile-id profile-id
                              ::bfc/project-id project-id
                              ::bfc/team-id (:id team)
                              ::bfc/input path
                              ::bfc/import-max-binary-entry-size (cf/get :binfile-import-max-binary-entry-size)
                              ::bfc/import-max-text-entry-size (cf/get :binfile-import-max-text-entry-size)
                              ::bfc/import-max-text-total-size (cf/get :binfile-import-max-text-total-size)
                              ::bfc/import-max-zip-entries (cf/get :binfile-import-max-zip-entries))]
        (bf.v3/import-files! cfg)
        (fn [_request]
          {::yres/status  200
           ::yres/headers {"content-type" "text/plain"}
           ::yres/body    "OK CLONED"}))

      (let [filename (str (first file-ids) ".penpot")]
        (fn [_request]
          {::yres/status  200
           ::yres/body    (io/input-stream path)
           ::yres/headers {"content-type"        "application/octet-stream"
                           "content-disposition" (str "attachment; filename=" filename)}})))))

(def schema:import-files-params
  ;; Mirrors what the multipart parser hands over: `:path` is a
  ;; `java.nio.file.Path` object, not a string.
  [:map {:title "import-files-params"}
   [:file [:map
           [:path [:or ::sm/text [:fn #(instance? java.nio.file.Path %)]]]
           [:filename {:optional true} ::sm/text]
           [:size {:optional true} ::sm/int]]]])

(sv/defmethod ::import-files
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:import-files-params}
  [{:keys [::db/pool] :as cfg} {:keys [file ::rpc/profile-id]}]
  (let [profile    (profile/get-profile pool profile-id)
        project-id (:default-project-id profile)
        team       (teams/get-team pool
                                   :profile-id profile-id
                                   :project-id project-id)]
    (when-not project-id
      (ex/raise :type :validation
                :code :missing-project
                :hint "project not found"))

    (let [path   (:path file)
          format (bfc/parse-file-format path)
          cfg    (assoc cfg
                        ::bfc/profile-id profile-id
                        ::bfc/project-id project-id
                        ::bfc/input path
                        ::bfc/team-id (:id team)
                        ::bfc/features (cfeat/get-team-enabled-features cf/flags team)
                        ::bfc/import-max-binary-entry-size (cf/get :binfile-import-max-binary-entry-size)
                        ::bfc/import-max-text-entry-size (cf/get :binfile-import-max-text-entry-size)
                        ::bfc/import-max-text-total-size (cf/get :binfile-import-max-text-total-size)
                        ::bfc/import-max-zip-entries (cf/get :binfile-import-max-zip-entries))]

      (if (= format :binfile-v3)
        (bf.v3/import-files! cfg)
        (bf.v1/import-files! cfg))

      (fn [_request]
        {::yres/status  200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body    "OK"}))))

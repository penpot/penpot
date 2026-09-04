;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.debug
  (:refer-clojure :exclude [error-handler])
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
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.pprint :as pp]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-migrations :as feat.fmig]
   [app.http.session :as session]
   [app.redis :as rds]
   [app.rpc.commands.auth :as auth]
   [app.rpc.commands.files-create :refer [create-file]]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.setup :as-alias setup]
   [app.setup.clock :as clock]
   [app.srepl.helpers :as h]
   [app.srepl.main :as srepl]
   [app.storage :as-alias sto]
   [app.storage.tmp :as tmp]
   [app.util.template :as tmpl]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [emoji.core :as emj]
   [integrant.core :as ig]
   [markdown.core :as md]
   [markdown.transformers :as mdt]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

;; (selmer.parser/cache-off!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INDEX
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private max-export-jobs 200)

(defn- scan-export-job-keys
  "Note: no index for now, get them all and filter"
  [conn pattern]
  (loop [cursor "0"
         found  []]
    (let [[cursor keys] (rds/scan conn cursor pattern max-export-jobs)
          found         (into found keys)]
      (if (or (nil? cursor)
              (= "0" cursor)
              (>= (count found) max-export-jobs))
        (into [] (take max-export-jobs) found)
        (recur cursor found)))))

(defn- get-export-jobs
  [cfg job-id]
  (let [filtered? (not (str/empty-or-nil? job-id))
        job-uuid  (when filtered? (parse-uuid job-id))]
    (if (and filtered? (nil? job-uuid))
      []
      (try
        (let [pattern (str "penpot.exporter." (cf/get :tenant) ".job." (or job-uuid "*"))]
          (->> (rds/run! cfg (fn [{:keys [::rds/conn]}]
                               (->> (scan-export-job-keys conn pattern)
                                    (mapv (fn [key] (rds/hget conn key "data"))))))
               (keep (fn [blob]
                       (try
                         (t/decode-str blob)
                         (catch Throwable _ nil))))
               (sort-by :created-at #(compare %2 %1))
               ;; The exporter stores instants as epoch millis.
               (map (fn [{:keys [created-at ended-at] :as job}]
                      (-> job
                          (assoc :created-at (some-> created-at ct/inst (ct/format-inst :rfc1123)))
                          (assoc :ended-at (some-> ended-at ct/inst (ct/format-inst :rfc1123))))))
               (vec)))
        (catch Throwable cause
          (l/warn :hint "unable to read export jobs" :cause cause)
          [])))))

(defn index-handler
  [cfg request]
  (let [profile-id (::session/profile-id request)
        offset     (clock/get-offset profile-id)
        profile    (profile/get-profile cfg profile-id)
        job-filter (some-> request :params :job-id str/trim)]
    {::yres/status  200
     ::yres/headers {"content-type" "text/html"}
     ::yres/body    (-> (io/resource "app/templates/debug.tmpl")
                        (tmpl/render {:version (:full cf/version)
                                      :profile profile
                                      :graph-enabled (contains? cf/flags :graph)
                                      :current-clock  ct/*clock*
                                      :current-offset (if offset
                                                        (ct/format-duration offset)
                                                        "NO OFFSET")
                                      :current-time  (ct/format-inst (ct/now) :http)
                                      :export-jobs (get-export-jobs cfg job-filter)
                                      :export-job-filter job-filter
                                      :supported-features cfeat/supported-features}))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE CHANGES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-resolved-file
  [cfg file-id]
  (bfc/get-file cfg file-id :migrate? false :decode? false))

(defn prepare-download
  [file filename]
  {::yres/status 200
   ::yres/headers
   {"content-disposition" (str "attachment; filename=" filename ".json")
    "content-type" "application/octet-stream"}
   ::yres/body
   (t/encode file {:type :json-verbose})})

(def sql:retrieve-range-of-changes
  "select revn, changes from file_change where file_id=? and revn >= ? and revn <= ? order by revn")

(def sql:retrieve-single-change
  "select revn, changes, data from file_change where file_id=? and revn = ?")

(defn- download-file-data
  [cfg {:keys [params ::session/profile-id] :as request}]
  (let [file-id  (some-> params :file-id parse-uuid)
        filename (str file-id)]

    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    (if-let [file (get-resolved-file cfg file-id)]
      (cond
        (contains? params :download)
        (prepare-download file filename)

        (contains? params :clone)
        (db/tx-run! cfg
                    (fn [{:keys [::db/conn] :as cfg}]
                      (let [profile    (profile/get-profile conn profile-id)
                            project-id (:default-project-id profile)
                            file       (-> (create-file cfg {:id (uuid/next)
                                                             :name (str "Cloned: " (:name file))
                                                             :features (:features file)
                                                             :project-id project-id
                                                             :profile-id profile-id})
                                           (assoc :data (:data file))
                                           (assoc :migrations (:migrations file)))]

                        (feat.fmig/reset-migrations! conn file)
                        (db/update! conn :file
                                    {:data (:data file)}
                                    {:id (:id file)}
                                    {::db/return-keys false})


                        {::yres/status 201
                         ::yres/body "OK CLONED"})))

        :else
        (ex/raise :type :validation
                  :code :invalid-params
                  :hint "invalid button"))

      (ex/raise :type :not-found
                :code :empty-data
                :hint "empty response"))))

(defn- is-file-exists?
  [pool id]
  (let [sql "select exists (select 1 from file where id=?) as exists;"]
    (-> (db/exec-one! pool [sql id]) :exists)))

(defn- upload-file-data
  [{:keys [::db/pool] :as cfg} {:keys [::session/profile-id params] :as request}]
  (let [profile    (profile/get-profile pool profile-id)
        project-id (:default-project-id profile)
        file       (some-> params :file :path io/read* t/decode)]

    (if (and file project-id)
      (let [fname     (str "Imported: " (:name file) "(" (ct/now) ")")
            reuse-id? (contains? params :reuseid)
            file-id   (or (and reuse-id? (ex/ignoring (-> params :file :filename parse-uuid)))
                          (uuid/next))]

        (if (and reuse-id? file-id
                 (is-file-exists? pool file-id))
          (db/tx-run! cfg
                      (fn [{:keys [::db/conn] :as cfg}]
                        (db/update! conn :file
                                    {:data (:data file)
                                     :features (into-array (:features file))
                                     :deleted-at nil}
                                    {:id file-id}
                                    {::db/return-keys false})
                        (feat.fmig/reset-migrations! conn file)
                        {::yres/status 200
                         ::yres/body "OK UPDATED"}))

          (db/tx-run! cfg
                      (fn [{:keys [::db/conn] :as cfg}]
                        (let [file (-> (create-file cfg {:id file-id
                                                         :name fname
                                                         :features (:features file)
                                                         :project-id project-id
                                                         :profile-id profile-id})
                                       (assoc :data (:data file))
                                       (assoc :migrations (:migrations file)))]

                          (db/update! conn :file
                                      {:data (:data file)}
                                      {:id file-id}
                                      {::db/return-keys false})
                          (feat.fmig/reset-migrations! conn file)
                          {::yres/status 201
                           ::yres/body "OK CREATED"})))))

      (ex/raise :type :validation
                :code :invalid-params
                :hint "invalid file uploaded"))))

(defn raw-export-import-handler
  [cfg request]
  (case (yreq/method request)
    :get (download-file-data cfg request)
    :post (upload-file-data cfg request)
    (ex/raise :type :http
              :code :method-not-found)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ERROR BROWSER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn error-handler
  [{:keys [::db/pool]} request]
  (letfn [(get-report [{:keys [path-params]}]
            (ex/ignoring
             (let [report-id (some-> path-params :id parse-uuid)]
               (some-> (db/get-by-id pool :server-error-report report-id)
                       (update :content db/decode-transit-pgobject)))))

          (render-template-v1 [{:keys [content]}]
            (let [context (dissoc content
                                  :trace :cause :params :data :spec-problems :message
                                  :spec-explain :spec-value :error :explain :hint)
                  params  {:context       (pp/pprint-str context :width 200)
                           :hint          (:hint content)
                           :spec-explain  (:spec-explain content)
                           :spec-problems (:spec-problems content)
                           :spec-value    (:spec-value content)
                           :data          (:data content)
                           :trace         (or (:trace content)
                                              (some-> content :error :trace))
                           :params        (:params content)}]
              (-> (io/resource "app/templates/error-report.tmpl")
                  (tmpl/render params))))

          (render-template-v2 [{report :content}]
            (-> (io/resource "app/templates/error-report.v2.tmpl")
                (tmpl/render report)))

          (render-template-v3 [{:keys [content id created-at]}]
            (-> (io/resource "app/templates/error-report.v3.tmpl")
                (tmpl/render (-> content
                                 (assoc :id id)
                                 (assoc :source 3)
                                 (assoc :created-at (ct/format-inst created-at :rfc1123))))))

          (render-template-v4 [{:keys [content id created-at]}]
            (-> (io/resource "app/templates/error-report.v4.tmpl")
                (tmpl/render (-> content
                                 (assoc :id id)
                                 (assoc :source 4)
                                 (assoc :kind (or (:kind content) (:origin content)))
                                 (assoc :trace (or (:trace content) (:report content)))
                                 (assoc :created-at (ct/format-inst created-at :rfc1123))))))

          (render-template-v5 [{:keys [content id created-at]}]
            (-> (io/resource "app/templates/error-report.v5.tmpl")
                (tmpl/render (-> content
                                 (assoc :id id)
                                 (assoc :source 5)
                                 (assoc :value (or (:value content) (:result content)))
                                 (assoc :created-at (ct/format-inst created-at :rfc1123))))))]

    (if-let [report (get-report request)]
      (let [result (case (:source report)
                     1 (render-template-v1 report)
                     2 (render-template-v2 report)
                     3 (render-template-v3 report)
                     4 (render-template-v4 report)
                     5 (render-template-v5 report))]
        {::yres/status 200
         ::yres/body result
         ::yres/headers {"content-type" "text/html; charset=utf-8"
                         "x-robots-tag" "noindex"}})
      {::yres/status 404
       ::yres/body "not found"})))

(def ^:private sql:error-reports
  "SELECT id, created_at,
          content->>'~:hint' AS hint
     FROM server_error_report
     WHERE (version = ? OR source = ? OR ? = 0)
     ORDER BY created_at DESC
     LIMIT 300")

(defn- error-list-handler
  [{:keys [::db/pool]} {:keys [params]}]
  (let [source (or (some-> (get params :source) parse-long) 3)
        items  (->> (db/exec! pool [sql:error-reports source source source])
                    (map #(update % :created-at ct/format-inst :rfc1123)))]

    {::yres/status 200
     ::yres/body (-> (io/resource "app/templates/error-list.tmpl")
                     (tmpl/render {:items items :source source}))
     ::yres/headers {"content-type" "text/html; charset=utf-8"
                     "x-robots-tag" "noindex"}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EXPORT/IMPORT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export-handler
  [{:keys [::db/pool] :as cfg} {:keys [params ::session/profile-id] :as request}]

  (let [file-ids (into #{}
                       (comp (remove empty?)
                             (map parse-uuid))
                       (:file-ids params))
        libs?    (contains? params :includelibs)
        clone?   (contains? params :clone)
        embed?   (contains? params :embedassets)]

    (when-not (seq file-ids)
      (ex/raise :type :validation
                :code :missing-arguments))

    (let [path (tmp/tempfile :prefix "penpot.export." :min-age "30m")]
      (with-open [output (io/output-stream path)]
        (-> cfg
            (assoc ::bfc/ids file-ids)
            (assoc ::bfc/embed-assets embed?)
            (assoc ::bfc/include-libraries libs?)
            (bf.v3/export-files! output)))

      (if clone?
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
                                ::bfc/import-max-object-size (cf/get :binfile-import-max-object-size)
                                ::bfc/import-max-zip-entries (cf/get :binfile-import-max-zip-entries))]
          (bf.v3/import-files! cfg)
          {::yres/status  200
           ::yres/headers {"content-type" "text/plain"}
           ::yres/body    "OK CLONED"})

        {::yres/status  200
         ::yres/body    (io/input-stream path)
         ::yres/headers {"content-type" "application/octet-stream"
                         "content-disposition" (str "attachmen; filename=" (first file-ids) ".penpot")}}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GRAPH (flag: :graph)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; `app.graph.*` resolves at call time, never at the top of this namespace.
;; `app.graph.ladybug` imports `com.ladybugdb.*`, so requiring it links the
;; Ladybug native library into the JVM, and this namespace loads on every
;; backend boot. The routes below are registered only under the `:graph` flag,
;; so with the flag off nothing resolves and no native code loads.

(defn- graph-export-file
  "Path of a freshly projected graph for `file-id`."
  [cfg file-id]
  (let [ingest-file!      (requiring-resolve 'app.graph.ingest/ingest-file!)
        {:keys [db-path]} (ingest-file! cfg file-id :skip-stats? true)]
    (when-not (fs/exists? db-path)
      (ex/raise :type :internal
                :code :graph-file-not-found
                :hint "graph database file missing after ingest"
                :file-id (str file-id)
                :db-path db-path))
    db-path))

(defn- graph-export-session
  "Path of a snapshot of the caller's live in-memory graph for `file-id`."
  [profile-id file-id]
  (let [session-info             (requiring-resolve 'app.graph.debug/session-info)
        export-session-database! (requiring-resolve 'app.graph.debug/export-session-database!)
        info                     (session-info profile-id)]
    (when-not info
      (ex/raise :type :not-found
                :code :graph-session-not-loaded
                :hint "no in-memory graph is loaded; load one first, or use source=file"))
    (when-not (= file-id (:file-id info))
      (ex/raise :type :validation
                :code :graph-session-file-mismatch
                :hint "the loaded session holds a different file"
                :requested (str file-id)
                :loaded (str (:file-id info))))
    (export-session-database! profile-id)))

(defn graph-export-handler
  "Stream a Ladybug `.lbug` database for a file.

  `source=file` (default) projects the file afresh from the database — the
  reproducible artifact. `source=session` snapshots the caller's live
  in-memory console graph instead, which live-sync may have moved away from a
  fresh projection; taking that away to query it elsewhere is the whole point
  of asking for it. Synchronous on each request."
  [cfg {:keys [params] :as request}]
  (let [file-id (some-> params :file-id parse-uuid)
        source  (or (some-> params :source str/lower) "file")]
    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments
                :hint "missing file-id"))
    (when-not (contains? #{"file" "session"} source)
      (ex/raise :type :validation
                :code :invalid-arguments
                :hint "source must be 'file' or 'session'"
                :source source))

    (let [session? (= "session" source)
          db-path  (if session?
                     (graph-export-session (::session/profile-id request) file-id)
                     (graph-export-file cfg file-id))]
      {::yres/status  200
       ;; A session export is a temp file this request owns; deleting it on
       ;; close would race the streaming body, so it is left for the OS temp
       ;; sweep. A file export is the canonical per-file database and is meant
       ;; to persist.
       ::yres/body    (io/input-stream db-path)
       ::yres/headers {"content-type" "application/octet-stream"
                       "content-disposition"
                       (str "attachment; filename=" file-id
                            (when session? "-session") ".lbug")}})))

(defn- graph-console-response
  [data]
  {::yres/status  200
   ::yres/headers {"content-type" "text/html; charset=utf-8"
                   "x-robots-tag" "noindex"}
   ::yres/body    (-> (io/resource "app/templates/graph-console.tmpl")
                      (tmpl/render (assoc data :version (:full cf/version))))})

(defn graph-console-handler
  [_cfg {:keys [::session/profile-id]}]
  (let [console-context (requiring-resolve 'app.graph.debug/console-context)]
    (graph-console-response (console-context profile-id))))

(defn graph-load-handler
  [cfg {:keys [params ::session/profile-id]}]
  (let [file-id       (some-> (:file-id params) parse-uuid)
        load-session! (requiring-resolve 'app.graph.debug/load-session!)]
    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments
                :hint "missing file-id"))
    (load-session! cfg profile-id file-id)
    {::yres/status  302
     ::yres/headers {"location" "/dbg/graph"}}))

(defn graph-unload-handler
  [_cfg {:keys [::session/profile-id]}]
  ((requiring-resolve 'app.graph.debug/unload-session!) profile-id)
  {::yres/status  302
   ::yres/headers {"location" "/dbg/graph"}})

(defn graph-reload-handler
  "Re-ingest the currently loaded file into the in-memory graph session."
  [cfg {:keys [::session/profile-id]}]
  (let [session-info  (requiring-resolve 'app.graph.debug/session-info)
        load-session! (requiring-resolve 'app.graph.debug/load-session!)]
    (if-let [file-id (some-> (session-info profile-id) :file-id)]
      (do
        (load-session! cfg profile-id file-id)
        {::yres/status  302
         ::yres/headers {"location" "/dbg/graph"}})
      (ex/raise :type :not-found
                :code :graph-session-not-loaded
                :hint "load a file graph before reloading"))))

(defn graph-sync-status-handler
  [_cfg {:keys [::session/profile-id]}]
  (if-let [status ((requiring-resolve 'app.graph.debug/sync-status) profile-id)]
    {::yres/status  200
     ::yres/headers {"content-type" "application/json; charset=utf-8"}
     ::yres/body    (t/encode-str status {:type :json-verbose})}
    {::yres/status  404
     ::yres/headers {"content-type" "application/json; charset=utf-8"}
     ::yres/body    (t/encode-str {:error "no-session"} {:type :json-verbose})}))

(defn graph-data-handler
  "Export the in-memory session graph as plain JSON (not transit) for the
  G6 graph view embedded in the console page."
  [_cfg {:keys [::session/profile-id]}]
  (if-let [data ((requiring-resolve 'app.graph.debug/export-graph-data!) profile-id)]
    {::yres/status  200
     ::yres/headers {"content-type" "application/json; charset=utf-8"}
     ::yres/body    (json/encode data)}
    {::yres/status  404
     ::yres/headers {"content-type" "application/json; charset=utf-8"}
     ::yres/body    (json/encode {:error "no-session"})}))

(def ^:private sql:graph-files
  "select t.id   as team_id,    t.name as team_name,
          p.id   as project_id, p.name as project_name,
          f.id   as file_id,    f.name as file_name
     from team as t
     join team_profile_rel as tpr on (tpr.team_id = t.id)
     join project as p on (p.team_id = t.id)
     join file as f on (f.project_id = p.id)
    where tpr.profile_id = ?
      and t.deleted_at is null
      and p.deleted_at is null
      and f.deleted_at is null
    order by t.name, p.name, f.name
    limit 500")

(defn- graph-files-tree
  [rows]
  (->> (group-by (juxt :team-id :team-name) rows)
       (mapv (fn [[[team-id team-name] team-rows]]
               {:id   (str team-id)
                :name team-name
                :projects
                (->> (group-by (juxt :project-id :project-name) team-rows)
                     (mapv (fn [[[project-id project-name] project-rows]]
                             {:id    (str project-id)
                              :name  project-name
                              :files (mapv (fn [{:keys [file-id file-name]}]
                                             {:id (str file-id) :name file-name})
                                           project-rows)}))
                     (sort-by :name)
                     (vec))}))
       (sort-by :name)
       (vec)))

(defn graph-files-handler
  "List teams -> projects -> files reachable by the current profile, as
  plain JSON for the graph console file tree."
  [{:keys [::db/pool]} {:keys [::session/profile-id]}]
  (let [rows (db/exec! pool [sql:graph-files profile-id])]
    {::yres/status  200
     ::yres/headers {"content-type" "application/json; charset=utf-8"}
     ::yres/body    (json/encode {:teams (graph-files-tree rows)})}))

(defn- json-request?
  [request]
  (some-> request
          (yreq/get-header "accept")
          (str/includes? "application/json")))

(defn graph-query-handler
  [_cfg {:keys [params ::session/profile-id] :as request}]
  (let [query           (:query params)
        query-session!  (requiring-resolve 'app.graph.debug/query-session!)
        console-context (requiring-resolve 'app.graph.debug/console-context)]
    (try
      (let [result (query-session! profile-id query)]
        (if (json-request? request)
          {::yres/status  200
           ::yres/headers {"content-type" "application/json; charset=utf-8"}
           ::yres/body    (t/encode-str {:query         query
                                         :query-result result}
                                        {:type :json-verbose})}
          (graph-console-response (console-context profile-id
                                                   :query query
                                                   :query-result result))))
      (catch Throwable e
        (let [error (or (:hint (ex-data e)) (ex-message e))]
          (if (json-request? request)
            {::yres/status  200
             ::yres/headers {"content-type" "application/json; charset=utf-8"}
             ::yres/body    (t/encode-str {:query query :error error}
                                          {:type :json-verbose})}
            (graph-console-response (console-context profile-id
                                                     :query query
                                                     :error error))))))))

(defn import-handler
  [{:keys [::db/pool] :as cfg} {:keys [params ::session/profile-id] :as request}]
  (when-not (contains? params :file)
    (ex/raise :type :validation
              :code :missing-upload-file
              :hint "missing upload file"))

  (let [profile    (profile/get-profile pool profile-id)
        project-id (:default-project-id profile)
        team       (teams/get-team pool
                                   :profile-id profile-id
                                   :project-id project-id)]

    (when-not project-id
      (ex/raise :type :validation
                :code :missing-project
                :hint "project not found"))

    (let [path   (-> params :file :path)
          format (bfc/parse-file-format path)
          cfg    (assoc cfg
                        ::bfc/profile-id profile-id
                        ::bfc/project-id project-id
                        ::bfc/input path
                        ::bfc/team-id (:id team)
                        ::bfc/features (cfeat/get-team-enabled-features cf/flags team)
                        ::bfc/import-max-object-size (cf/get :binfile-import-max-object-size)
                        ::bfc/import-max-zip-entries (cf/get :binfile-import-max-zip-entries))]

      (if (= format :binfile-v3)
        (bf.v3/import-files! cfg)
        (bf.v1/import-files! cfg))

      {::yres/status  200
       ::yres/headers {"content-type" "text/plain"}
       ::yres/body    "OK"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resend-email-notification
  [cfg {:keys [params] :as request}]
  (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                    (when-not (contains? params :force)
                      (ex/raise :type :validation
                                :code :missing-force
                                :hint "missing force checkbox"))

                    (let [profile (some->> params
                                           :email
                                           (profile/clean-email)
                                           (profile/get-profile-by-email conn))]

                      (when-not profile
                        (ex/raise :type :validation
                                  :code :missing-profile
                                  :hint "unable to find profile by email"))

                      (cond
                        (contains? params :block)
                        (do
                          (db/update! conn :profile {:is-blocked true} {:id (:id profile)})
                          (db/delete! conn :http-session {:profile-id (:id profile)})

                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    (str/ffmt "PROFILE '%' BLOCKED" (:email profile))})

                        (contains? params :unblock)
                        (do
                          (db/update! conn :profile {:is-blocked false} {:id (:id profile)})
                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    (str/ffmt "PROFILE '%' UNBLOCKED" (:email profile))})

                        (contains? params :resend)
                        (if (:is-blocked profile)
                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    "PROFILE ALREADY BLOCKED"}
                          (do
                            (#'auth/send-email-verification! cfg profile)
                            {::yres/status  200
                             ::yres/headers {"content-type" "text/plain"}
                             ::yres/body    (str/ffmt "RESENDED FOR '%'" (:email profile))}))

                        :else
                        (do
                          (db/update! conn :profile {:is-active true} {:id (:id profile)})
                          {::yres/status  200
                           ::yres/headers {"content-type" "text/plain"}
                           ::yres/body    (str/ffmt "PROFILE '%' ACTIVATED" (:email profile))}))))))

(defn- handle-team-features
  [cfg {:keys [params] :as request}]
  (let [team-id    (some-> params :team-id d/parse-uuid)
        feature    (some-> params :feature str)
        action     (some-> params :action)
        skip-check (contains? params :skip-check)]

    (when (nil? team-id)
      (ex/raise :type :validation
                :code :invalid-team-id
                :hint "provided invalid team id"))

    (if (= action "show")
      (let [team (db/run! cfg teams/get-team-info {:id team-id})]
        {::yres/status 200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body (apply str "Team features:\n"
                            (->> (:features team)
                                 (map (fn [feature]
                                        (str "- " feature "\n")))))})

      (do
        (when-not (contains? params :force)
          (ex/raise :type :validation
                    :code :missing-force
                    :hint "missing force checkbox"))

        (cond
          (= action "enable")
          (srepl/enable-team-feature! team-id feature :skip-check skip-check)

          (= action "disable")
          (srepl/disable-team-feature! team-id feature :skip-check skip-check)

          :else
          (ex/raise :type :validation
                    :code :invalid-action
                    :hint (str "invalid action: " action)))


        {::yres/status  200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body    "OK"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VIRTUAL CLOCK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-virtual-clock
  [_ {:keys [params] :as request}]
  (let [offset     (some-> params :offset str/trim not-empty ct/duration)
        profile-id (::session/profile-id request)
        reset?     (contains? params :reset)]
    (if (= "production" (cf/get :tenant))
      {::yres/status 501
       ::yres/body "OPERATION NOT ALLOWED"}
      (do
        (if (or reset? (zero? (inst-ms offset)))
          (clock/assign-offset profile-id nil)
          (clock/assign-offset profile-id offset))
        {::yres/status 302
         ::yres/headers {"location" "/dbg"}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VALIDATE / REPAIR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- validate-file
  [cfg {:keys [params] :as request}]
  (let [file-id  (some-> params :file-id parse-uuid)]

    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    (db/tx-run! (assoc cfg ::db/rollback true)
                (fn [cfg]
                  (let [file (bfc/get-file cfg file-id)
                        libs (bfc/get-resolved-file-libraries cfg file-id)]
                    (if file
                      (let [errors (cfv/validate-file file libs)]
                        {::yres/status  200
                         ::yres/headers {"content-type" "text/plain"}
                         ::yres/body    (if (empty? errors)
                                          "NO VALIDATION ERRORS FOUND"
                                          (pp/pprint-str errors))})
                      (ex/raise :type :not-found
                                :code :empty-data
                                :hint "empty response")))))))

(defn- repair-file
  [cfg {:keys [params] :as request}]
  (let [file-id        (some-> params :file-id parse-uuid)
        skip-snapshot? (contains? params :skip-snapshot)
        profile-id     (:app.http.session/profile-id request)]

    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    (let [output (StringBuilder.)

          repair-file
          (fn [file libs _]
            (let [errors (cfv/validate-file file libs)]
              (.append output (if (empty? errors)
                                "NO VALIDATION ERRORS FOUND\n"
                                (str "VALIDATION ERRORS FOUND:\n"
                                     (pp/pprint-str errors) "\n")))
              (if (empty? errors)
                file
                (let [changes (cfr/repair-file file libs errors)]
                  (-> file
                      (update :revn inc)
                      (update :data cfc/process-changes changes))))))]

      (add-watch l/log-record ::repair-watcher
                 (fn [_ _ _ record]
                   (when (= "app.common.files.repair" (::l/logger record))
                     (let [props (::l/props record)
                           hint (get props :hint "")
                           args (dissoc props :hint)
                           message (str hint " "
                                        (when-not (empty? args)
                                          args)
                                        "\n")]
                       (.append output message)))))
      (try
        (db/tx-run! cfg
                    h/process-file!
                    file-id
                    repair-file
                    {::h/with-libraries? true
                     ::h/validate? false
                     ::h/profile-id profile-id
                     ::h/snapshot-label (when-not skip-snapshot? "repair")})

        (.append output "\nREPAIR FINISHED")

        {::yres/status  200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body    (.toString output)}

        (finally
          (remove-watch l/log-record ::repair-watcher))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OTHER SMALL VIEWS/HANDLERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn health-handler
  "Mainly a task that performs a health check."
  [{:keys [::db/pool]} _]
  (try
    (db/exec-one! pool ["select count(*) as count from server_prop;"])
    {::yres/status 200
     ::yres/body "OK"}
    (catch Throwable cause
      (l/warn :hint "unable to execute query on health handler"
              :cause cause)
      {::yres/status 503
       ::yres/body "KO"})))

(defn changelog-handler
  [_ _]
  (letfn [(transform-emoji [text state]
            [(emj/emojify text) state])
          (md->html [text]
            (md/md-to-html-string text :replacement-transformers (into [transform-emoji] mdt/transformer-vector)))]
    (if-let [clog (io/resource "changelog.md")]
      {::yres/status 200
       ::yres/headers {"content-type" "text/html; charset=utf-8"}
       ::yres/body (-> clog slurp md->html)}
      {::yres/status 404
       ::yres/body "NOT FOUND"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn authorized?
  [pool {:keys [::session/profile-id]}]
  (or (and (= "devenv" (cf/get :host)) profile-id)
      (let [profile (ex/ignoring (profile/get-profile pool profile-id))
            admins  (or (cf/get :admins) #{})]
        (contains? admins (:email profile)))))

(def with-authorization
  {:compile
   (fn [& _]
     (fn [handler pool]
       (fn [request]
         (if (authorized? pool request)
           (handler request)
           (ex/raise :type :authentication
                     :code :only-admins-allowed)))))})

(def errors
  (letfn [(handle-error [cause]
            (when-let [data (ex-data cause)]
              (when (= :validation (:type data))
                (let [hint (or (:hint data) (ex-message cause))
                      explain (ex/explain data)]
                  (str "Error: " hint
                       (when (and explain (not (str/includes? hint explain)))
                         (str "\n" explain))
                       "\n")))))]
    {:name ::errors
     :compile
     (fn [& _params]
       (fn [handler]
         (fn [request]
           (try
             (handler request)
             (catch Throwable cause
               (let [body (or (handle-error cause)
                              (ex/format-throwable cause))]
                 {::yres/status 400
                  ::yres/headers {"content-type" "text/plain"}
                  ::yres/body body}))))))}))

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool")
  (assert (session/manager? (::session/manager params)) "expected a valid session manager"))

(defn- graph-action-routes
  [cfg]
  [["/graph-export" {:handler (partial graph-export-handler cfg)}]
   ["/graph-load" {:handler (partial graph-load-handler cfg)}]
   ["/graph-query" {:handler (partial graph-query-handler cfg)}]
   ["/graph-unload" {:handler (partial graph-unload-handler cfg)}]
   ["/graph-reload" {:handler (partial graph-reload-handler cfg)}]
   ["/graph-sync-status" {:handler (partial graph-sync-status-handler cfg)}]
   ["/graph-data" {:handler (partial graph-data-handler cfg)}]
   ["/graph-files" {:handler (partial graph-files-handler cfg)}]])

(defmethod ig/init-key ::routes
  [_ {:keys [::db/pool] :as cfg}]
  ;; The graph routes are registered only under the `:graph` flag. Left
  ;; unregistered they 404, and nothing ever resolves `app.graph.*`. The `/dbg`
  ;; admin gate is unchanged: it covers the graph routes exactly as before.
  (let [graph?  (contains? cf/flags :graph)
        actions (cond-> ["/actions" {:middleware [[errors]]}
                         ["/set-virtual-clock"
                          {:handler (partial set-virtual-clock cfg)}]
                         ["/resend-email-verification"
                          {:handler (partial resend-email-notification cfg)}]
                         ["/handle-team-features"
                          {:handler (partial handle-team-features cfg)}]
                         ["/file-export" {:handler (partial export-handler cfg)}]
                         ["/file-import" {:handler (partial import-handler cfg)}]
                         ["/file-raw-export-import" {:handler (partial raw-export-import-handler cfg)}]
                         ["/file-validate" {:handler (partial validate-file cfg)}]
                         ["/file-repair" {:handler (partial repair-file cfg)}]]
                  graph? (into (graph-action-routes cfg)))
        dbg     (cond-> ["/dbg" {:middleware [[session/authz cfg]
                                              [with-authorization pool]]}
                         ["" {:handler (partial index-handler cfg)}]
                         ["/health" {:handler (partial health-handler cfg)}]
                         ["/changelog" {:handler (partial changelog-handler cfg)}]
                         ["/error/:id" {:handler (partial error-handler cfg)}]
                         ["/error" {:handler (partial error-list-handler cfg)}]
                         actions]
                  graph? (conj ["/graph" {:handler (partial graph-console-handler cfg)}]))]
    (when graph?
      ;; With the flag on, the Ladybug native library belongs to this process,
      ;; so load it here. A missing or unusable library then fails the boot
      ;; instead of the first console request.
      (require 'app.graph.debug 'app.graph.ingest))

    [["/readyz" {:handler (partial health-handler cfg)}]
     dbg]))


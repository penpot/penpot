;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.admin
  "Static file serving for the administration panel (`/admin` SPA).

  Every path under `/admin` answers with a file from
  `backend/resources/admin/`, or with `index.html` when there is no
  file behind it (subpath support, so a deep reload never 404s).
  There is no authentication here: the page is public like any
  static asset, and the JS gate reads `:is-superuser` from
  `get-profile` once loaded. The real protection lives in the RPC
  guard, which rejects non-superusers with 403."
  (:require
   [app.auth :as auth]
   [app.binfile.common :as bfc]
   [app.binfile.v1 :as bf.v1]
   [app.binfile.v3 :as bf.v3]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.access-token :as-alias actoken]
   [app.http.session :as-alias session]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.storage.tmp :as tmp]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [integrant.core :as ig]
   [yetti.response :as-alias yres]))

(def ^:private resource-prefix
  "admin/")

(def ^:private fallback-resource
  "index.html")

(def ^:private content-types
  {".html" "text/html"
   ".js"   "text/javascript"
   ".css"  "text/css"
   ".json" "application/json"
   ".svg"  "image/svg+xml"
   ".png"  "image/png"
   ".ico"  "image/x-icon"})

(defn- extension
  [rel]
  (let [name (last (str/split rel "/"))]
    (when (str/includes? name ".")
      (str "." (str/lower (last (str/split name ".")))))))

(defn- safe-rel?
  "True when `rel` cannot escape `resource-prefix`: no parent
  references, no backslashes, plain file-name characters only."
  [rel]
  (boolean
   (and (seq rel)
        (not (str/includes? rel "\\"))
        (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" rel)
        (not-any? #(= ".." %) (str/split rel "/")))))

(defn- resolve-resource
  "Map the path behind `/admin` to a file under `resource-prefix`.

  A blank path (the `/admin/` root) serves the index; only a safe
  relative path with a known extension and an existing resource
  serves its file. Anything else (SPA state travels in the query
  string, so there are no subpath pages) is nil, answered 404."
  [rel]
  (let [rel (str/trim (or rel ""))]
    (cond
      (= "" rel)
      fallback-resource

      (and (safe-rel? rel)
           (contains? content-types (extension rel))
           (io/resource (str resource-prefix rel)))
      rel)))

(defn- content-type-for
  [rel]
  (get content-types (extension rel)))

(defn admin-handler
  [_cfg request]
  (if-let [rel (resolve-resource (some-> request :path-params :path))]
    {::yres/status  200
     ::yres/headers {"content-type"  (content-type-for rel)
                     ;; The panel ships unversioned filenames and changes
                     ;; often: never let browsers cache it, or the sidebar
                     ;; keeps pointing at screens that no longer exist.
                     "cache-control" "no-store"}
     ::yres/body    (slurp (io/resource (str resource-prefix rel)) :encoding "UTF-8")}
    {::yres/status  404
     ::yres/headers {"content-type" "text/plain"}
     ::yres/body    "not found"}))

(defn admin-redirect-handler
  "Redirect the bare `/admin` to `/admin/` with a relative target.

  Without the trailing slash, relative asset URLs in index.html
  (`css/admin.css`, `js/main.js`) resolve against `/` instead of
  `/admin/` and the panel loads unstyled and inert. The relative
  target keeps subpath deployments working."
  [_cfg _request]
  {::yres/status  302
   ::yres/headers {"location" "admin/"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FILE TRANSFER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- require-superuser!
  "Enforce the shared superuser rule on an HTTP request.

  Mirrors the RPC wrapper: 401 when anonymous, 403 with
  `:superuser-required` otherwise. Returns the caller profile id."
  [cfg request]
  (let [profile-id (or (::session/profile-id request)
                       (::actoken/profile-id request))]
    (when-not (uuid? profile-id)
      (ex/raise :type :authentication
                :code :authentication-required
                :hint "authentication required for this endpoint"))
    (when-not (auth/superuser-allowed? cfg profile-id (::actoken/perms request))
      (ex/raise :type :authorization
                :code :superuser-required
                :hint "superuser required for this endpoint"))
    profile-id))

(defn- parse-file-ids
  "Normalize the `file-ids` query param into a set of uuids.

  Accepts both repeated params (`?file-ids=a&file-ids=b`) and a
  single comma-separated one (`?file-ids=a,b`)."
  [v]
  (let [items (if (coll? v) v [v])]
    (into #{}
          (comp (mapcat #(str/split (str %) #","))
                (map str/trim)
                (remove str/empty?)
                (map uuid/parse*)
                (remove nil?))
          items)))

(defn file-export-handler
  "Export files as a `.penpot` download, or clone them into the
  caller's default project. Same behavior as the old `/dbg`
  `/file-export`, behind the superuser gate."
  [{:keys [::db/pool] :as cfg} {:keys [params] :as request}]
  (let [profile-id (require-superuser! cfg request)
        file-ids   (parse-file-ids (:file-ids params))
        libs?      (contains? params :includelibs)
        clone?     (contains? params :clone)
        embed?     (contains? params :embedassets)]

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
                                ::bfc/import-max-binary-entry-size (cf/get :binfile-import-max-binary-entry-size)
                                ::bfc/import-max-text-entry-size (cf/get :binfile-import-max-text-entry-size)
                                ::bfc/import-max-text-total-size (cf/get :binfile-import-max-text-total-size)
                                ::bfc/import-max-zip-entries (cf/get :binfile-import-max-zip-entries))]
          (bf.v3/import-files! cfg)
          {::yres/status  200
           ::yres/headers {"content-type" "text/plain"}
           ::yres/body    "OK CLONED"})

        {::yres/status  200
         ::yres/body    (io/input-stream path)
         ::yres/headers {"content-type" "application/octet-stream"
                         "content-disposition" (str "attachment; filename=" (first file-ids) ".penpot")}}))))

(defn file-import-handler
  "Import a `.penpot` upload into the caller's default project.
  Same behavior as the old `/dbg` `/file-import`, behind the
  superuser gate. The multipart file arrives parsed by the HTTP
  server itself, no extra middleware involved."
  [{:keys [::db/pool] :as cfg} {:keys [params] :as request}]
  (let [profile-id (require-superuser! cfg request)]

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
                          ::bfc/import-max-binary-entry-size (cf/get :binfile-import-max-binary-entry-size)
                          ::bfc/import-max-text-entry-size (cf/get :binfile-import-max-text-entry-size)
                          ::bfc/import-max-text-total-size (cf/get :binfile-import-max-text-total-size)
                          ::bfc/import-max-zip-entries (cf/get :binfile-import-max-zip-entries))]

        (if (= format :binfile-v3)
          (bf.v3/import-files! cfg)
          (bf.v1/import-files! cfg))

        {::yres/status  200
         ::yres/headers {"content-type" "text/plain"}
         ::yres/body    "OK"}))))

(defmethod ig/init-key ::routes
  [_ cfg]
  (assert (io/resource (str resource-prefix fallback-resource))
          "missing admin/index.html on the classpath")
  [["/admin"
    ["" {:handler (partial admin-redirect-handler cfg)}]
    ["/" {:handler (partial admin-handler cfg)}]
    ["/*path" {:handler (partial admin-handler cfg)}]]])

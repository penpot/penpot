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

(defmethod ig/init-key ::routes
  [_ cfg]
  (assert (io/resource (str resource-prefix fallback-resource))
          "missing admin/index.html on the classpath")
  [["/admin"
    ["" {:handler (partial admin-redirect-handler cfg)}]
    ["/" {:handler (partial admin-handler cfg)}]
    ["/*path" {:handler (partial admin-handler cfg)}]]])

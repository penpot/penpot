;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.http.unfurl
  "Link unfurl (Open Graph metadata) related handlers.

  Serves a minimal HTML page with Open Graph metadata used by link
  preview crawlers (Slack, Discord, Twitter, ...). The reverse proxy
  routes crawler requests for the application root to this endpoint,
  preserving the query string params that the frontend mirrors on
  navigation (`file-id`, `project-id` and `team-id`)."
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.db :as db]
   [app.util.template :as tmpl]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [yetti.response :as-alias yres]))

(def ^:private default-context
  {:title "Penpot | Full-stack design"
   :description "Penpot is the open-source design platform for teams that build digital products at scale."})

(def ^:private sql:get-file
  "SELECT f.name, ft.media_id
     FROM file AS f
     LEFT JOIN file_thumbnail AS ft
            ON (ft.file_id = f.id AND ft.deleted_at IS NULL)
    WHERE f.id = ?
      AND f.deleted_at IS NULL
    ORDER BY ft.revn DESC NULLS LAST
    LIMIT 1")

(defn- resolve-image-uri
  [media-id]
  (str (cf/get :public-uri) "/assets/by-id/" media-id))

(defn- resolve-default-image-uri
  []
  (str (cf/get :public-uri) "/images/penpot-link-preview.png"))

(defn- get-file-context
  "Return the unfurl context for a file link: the file name as title
  and, when available, the last dashboard thumbnail as image."
  [pool file-id]
  (when-let [{:keys [name media-id]} (db/exec-one! pool [sql:get-file file-id])]
    (cond-> (assoc default-context :title (str name " | Penpot"))
      (some? media-id)
      (assoc :image (resolve-image-uri media-id)))))

(defn- get-context
  [pool params]
  (let [file-id    (some-> (:file-id params) d/parse-uuid)
        project-id (some-> (:project-id params) d/parse-uuid)
        team-id    (some-> (:team-id params) d/parse-uuid)]
    (cond
      (some? file-id)    (get-file-context pool file-id)
      (some? project-id) (assoc default-context :title "Project | Penpot")
      (some? team-id)    (assoc default-context :title "Team dashboard | Penpot"))))

(defn- handler
  [{:keys [::db/pool]} request]
  (let [context (when (contains? cf/flags :link-unfurl)
                  (get-context pool (:query-params request)))
        context (-> (or context default-context)
                    (update :image #(or % (resolve-default-image-uri))))]
    {::yres/status 200
     ::yres/headers {"content-type" "text/html; charset=utf-8"
                     "cache-control" "no-store, no-cache, max-age=0"}
     ::yres/body (-> (io/resource "app/templates/unfurl.tmpl")
                     (tmpl/render context))}))

;; --- Initialization

(defmethod ig/assert-key ::routes
  [_ params]
  (assert (db/pool? (::db/pool params)) "expect valid database pool"))

(defmethod ig/init-key ::routes
  [_ cfg]
  ["/unfurl" {:handler (partial handler cfg)}])

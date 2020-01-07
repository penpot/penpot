;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.fixtures
  "A initial fixtures."
  (:require
   [clojure.tools.logging :as log]
   [buddy.hashers :as hashers]
   [mount.core :as mount]
   [promesa.core :as p]
   [uxbox.config :as cfg]
   [uxbox.core]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.migrations]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]))

(defn- mk-uuid
  [prefix & args]
  (uuid/namespaced uuid/oid (apply str prefix (interpose "-" args))))

;; --- Users creation

(def create-user-sql
  "insert into users (id, fullname, username, email, password, photo)
   values ($1, $2, $3, $4, $5, $6)
   returning *;")

(def password (hashers/encrypt "123123"))

(defn create-user
  [conn user-index]
  (log/info "create user" user-index)
  (let [sql create-user-sql
        id (mk-uuid "user" user-index)
        fullname (str "User " user-index)
        username (str "user" user-index)
        email (str "user" user-index ".test@uxbox.io")
        photo ""]
  (db/query-one conn [sql id fullname username email password photo])))

;; --- Project User Relation Creation

(def create-project-user-sql
  "insert into project_users (project_id, user_id, can_edit)
   values ($1, $2, true)
  returning *")

(defn create-additional-project-user
  [conn [project-index user-index]]
  (log/info "create project user" user-index project-index)
  (let [sql create-project-user-sql
        project-id (mk-uuid "project" project-index user-index)
        user-id (mk-uuid "user" (dec user-index))]
    (db/query-one conn [sql project-id user-id])))

;; --- Projects creation

(def create-project-sql
  "insert into projects (id, user_id, name)
   values ($1, $2, $3)
   returning *;")

(defn create-project
  [conn [project-index user-index]]
  (log/info "create project" user-index project-index)
  (let [sql create-project-sql
        id (mk-uuid "project" project-index user-index)
        user-id (mk-uuid "user" user-index)
        name (str "project " project-index "," user-index)]
    (p/do! (db/query-one conn [sql id user-id name])
           (when (and (= project-index 0)
                      (> user-index 0))
             (create-additional-project-user conn [project-index user-index])))))


;; --- Create Page Files

(def create-file-sql
  "insert into project_files (id, user_id, project_id, name)
   values ($1, $2, $3, $4) returning id")

(defn create-file
  [conn [file-index project-index user-index]]
  (log/info "create page file" user-index project-index file-index)
  (let [sql create-file-sql
        id (mk-uuid "page-file" file-index project-index user-index)
        user-id (mk-uuid "user" user-index)
        project-id (mk-uuid "project" project-index user-index)
        name (str "file " file-index "," project-index "," user-index)]
    (db/query-one conn [sql id user-id project-id name])))

;; --- Create Pages

(def create-page-sql
  "insert into project_pages (id, user_id, file_id, name,
                      version, ordering, data, metadata)
   values ($1, $2, $3, $4, $5, $6, $7, $8)
   returning id;")

(def create-page-history-sql
  "insert into project_page_history (page_id, user_id, version, data)
   values ($1, $2, $3, $4)
   returning id;")

(defn create-page
  [conn [page-index file-index project-index user-index]]
  (log/info "create page" user-index project-index file-index page-index)
  (let [canvas {:id (mk-uuid "canvas" 1)
                :name "Canvas-1"
                :type :canvas
                :x 200
                :y 200
                :width 1024
                :height 768}
        data {:shapes []
              :canvas [(:id canvas)]
              :shapes-by-id {(:id canvas) canvas}}

        sql1 create-page-sql
        sql2 create-page-history-sql

        id (mk-uuid "page" page-index file-index project-index user-index)
        user-id (mk-uuid "user" user-index)
        file-id (mk-uuid "page-file" file-index project-index user-index)
        name (str "page " page-index)
        version 0
        ordering page-index
        data (blob/encode data)
        mdata (blob/encode {})]
    (p/do!
     (db/query-one conn [sql1 id user-id file-id name version ordering data mdata])
     #_(db/query-one conn [sql2 id user-id version data]))))

(def preset-small
  {:users 50
   :projects 5
   :files 5
   :pages 3})

(def preset-medium
  {:users 500
   :projects 20
   :files 5
   :pages 3})

(def preset-big
  {:users 5000
   :projects 50
   :files 5
   :pages 4})

(defn run
  [opts]
  (db/with-atomic [conn db/pool]
    (p/do!
     (p/run! #(create-user conn %) (range (:users opts)))
     (p/run! #(create-project conn %)
             (for [user-index    (range (:users opts))
                   project-index (range (:projects opts))]
               [project-index user-index]))
     (p/run! #(create-file conn %)
             (for [user-index    (range (:users opts))
                   project-index (range (:projects opts))
                   file-index  (range (:files opts))]
               [file-index project-index user-index]))
     (p/run! #(create-page conn %)
             (for [user-index    (range (:users opts))
                   project-index (range (:projects opts))
                   file-index  (range (:files opts))
                   page-index    (range (:pages opts))]
               [page-index file-index project-index user-index]))
     (p/promise nil))))

(defn -main
  [& args]
  (try
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.config/secret
                      #'uxbox.core/system
                      #'uxbox.db/pool
                      #'uxbox.migrations/migrations})
        (mount/start))
    (let [preset (case (first args)
                   (nil "small") preset-small
                   "medium" preset-medium
                   "big" preset-big
                   preset-small)]
      (log/info "Using preset:" (pr-str preset))
      (deref (run preset)))
    (finally
      (mount/stop))))

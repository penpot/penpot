;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.fixtures
  "A initial fixtures."
  (:require
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
  (uuid/namespaced uuid/oid (apply str prefix args)))

;; --- Users creation

(def create-user-sql
  "insert into users (id, fullname, username, email, password, metadata, photo)
   values ($1, $2, $3, $4, $5, $6, $7)
   returning *;")

(defn create-user
  [conn i]
  (println "create user" i)
  (db/query-one conn [create-user-sql
                      (mk-uuid "user" i)
                      (str "User " i)
                      (str "user" i)
                      (str "user" i ".test@uxbox.io")
                      (hashers/encrypt "123123")
                      (blob/encode {})
                      ""]))

;; --- Projects creation

(def create-project-sql
  "insert into projects (id, user_id, name)
   values ($1, $2, $3)
   returning *;")

(def create-project-role-sql
  "insert into projects_roles (project_id, user_id, role)
   values ($1, $2, 'owner');")

(defn create-project
  [conn [pjid uid]]
  (println "create project" pjid "(for user=" uid ")")
  (p/do!
   (db/query-one conn [create-project-sql
                       (mk-uuid "project" pjid uid)
                       (mk-uuid "user" uid)
                       (str "sample project " pjid)])
   (db/query-one conn [create-project-role-sql
                       (mk-uuid "project" pjid uid)
                       (mk-uuid "user" uid)])))

;; --- Pages creation

(def create-page-sql
  "insert into pages (id, user_id, project_id, name,
                      version, ordering, data, metadata)
   values ($1, $2, $3, $4, $5, $6, $7, $8)
   returning id;")

(def create-page-history-sql
  "insert into pages_history (page_id, user_id, version, data, metadata)
   values ($1, $2, $3, $4, $5)
   returning id;")

(defn create-page
  [conn [pjid paid uid]]
  (println "create page" paid "(for project=" pjid ", user=" uid ")")
  (let [data {:shapes [{:id (mk-uuid "canvas" 1)
                        :name "Canvas-1"
                        :type :canvas
                        :page (mk-uuid "page" pjid paid uid)
                        :x1 200
                        :y1 200
                        :x2 1224
                        :y2 968}]}
        data (blob/encode data)
        mdata (blob/encode {})]
    (p/do!
     (db/query-one conn [create-page-sql
                         (mk-uuid "page" pjid paid uid)
                         (mk-uuid "user" uid)
                         (mk-uuid "project" pjid uid)
                         (str "page " paid)
                         0
                         paid
                         data
                         mdata])
     (db/query-one conn [create-page-history-sql
                         (mk-uuid "page" pjid paid uid)
                         (mk-uuid "user" uid)
                         0
                         data
                         mdata]))))


(def num-users 5)
(def num-projects 5)
(def num-pages 5)

(defn -main
  [& args]
  (try
    (-> (mount/only #{#'uxbox.config/config
                      #'uxbox.config/secret
                      #'uxbox.core/system
                      #'uxbox.db/pool
                      #'uxbox.migrations/migrations})
        (mount/start))
    @(db/with-atomic [conn db/pool]
       (p/do!
        (p/run! #(create-user conn %) (range num-users))
        (p/run! #(create-project conn %)
                (for [uid (range num-users)
                      pjid  (range num-projects)]
                  [pjid uid]))
        (p/run! #(create-page conn %)
                (for [pjid(range num-projects)
                      paid  (range num-pages)
                      uid (range num-users)]
                  [pjid paid uid]))
        (p/promise 1)))
    (finally
      (mount/stop))))

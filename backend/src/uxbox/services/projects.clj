;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.util.spec :as us]
   [uxbox.services.core :as sv]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::token ::us/string)
(s/def ::user ::us/uuid)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Query: Projects

(s/def ::projects-query
  (s/keys :req-un [::user]))

(sv/defquery :projects
  {:doc "Query all projects"
   :spec ::projects-query}
  [{:keys [user] :as params}]
  (let [sql "select distinct on (p.id, p.created_at)
                    p.*,
                    array_agg(pg.id) over (
                      partition by p.id
                      order by pg.created_at
                      range between unbounded preceding and unbounded following
                    ) as pages
              from projects as p
             right join pages as pg
                     on (pg.project_id = p.id)
             where p.user_id = $1
             order by p.created_at asc"]
    (-> (db/query db/pool [sql user])
        (p/then (fn [rows]
                  (mapv #(update % :pages vec) rows))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Mutation: Create Project

(s/def ::create-project
  (s/keys :req-un [::user ::name]
          :opt-un [::id]))

(sv/defmutation :create-project
  {:doc "Create a project."
   :spec ::create-project}
  [{:keys [id user name] :as params}]
  (let [id (or id (uuid/next))
        sql "insert into projects (id, user_id, name)
             values ($1, $2, $3) returning *"]
    (db/query-one db/pool [sql id user name])))

;; --- Mutation: Update Project

(s/def ::update-project
  (s/keys :req-un [::user ::name ::id]))

(sv/defmutation :update-project
  {:doc "Update project."
   :spec ::update-project}
  [{:keys [id name user] :as params}]
  (let [sql "update projects
                set name = $3
              where id = $1
                and user_id = $2
                and deleted_at is null
             returning *"]
    (db/query-one db/pool [sql id user name])))

;; --- Mutation: Delete Project

(s/def ::delete-project
  (s/keys :req-un [::id ::user]))

(sv/defmutation :delete-project
  {:doc "Delete project"
   :spec ::delete-project}
  [{:keys [id user] :as params}]
  (let [sql "update projects
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
                and deleted_at is null
             returning id"]
    (-> (db/query-one db/pool [sql id user])
        (p/then' sv/raise-not-found-if-nil)
        (p/then' sv/constantly-nil))))


;; --- Retrieve Project by share token

;; (defn- get-project-by-share-token
;;   [conn token]
;;   (let [sqlv (sql/get-project-by-share-token {:token token})
;;         project (some-> (db/fetch-one conn sqlv)
;;                         (data/normalize))]
;;     (when-let [id (:id project)]
;;       (let [pages (vec (pages/get-pages-for-project conn id))]
;;         (assoc project :pages pages)))))

;; (defmethod core/query :retrieve-project-by-share-token
;;   [{:keys [token]}]
;;   (s/assert ::token token)
;;   (with-open [conn (db/connection)]
;;     (get-project-by-share-token conn token)))

;; --- Retrieve share tokens

;; (defn get-share-tokens-for-project
;;   [conn project]
;;   (s/assert ::project project)
;;   (let [sqlv (sql/get-share-tokens-for-project {:project project})]
;;     (db/fetch conn sqlv)))


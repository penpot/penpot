;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.mutations.projects
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.util.spec :as us]
   [uxbox.services.mutations :as sm]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.exceptions :as ex]
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::token ::us/string)
(s/def ::user ::us/uuid)

;; --- Permissions Checks

(def ^:private sql:project-permissions
  "select p.id,
          pu.can_edit as can_edit
     from projects as p
    inner join project_users as pu
       on (pu.project_id = p.id)
    where pu.user_id = $1
      and p.id = $2
      for update of p;")

(defn check-edition-permissions!
  [conn user project-id]
  (-> (db/query-one conn [sql:project-permissions user project-id])
      (p/then' su/raise-not-found-if-nil)
      (p/then' (fn [{:keys [id can-edit] :as proj}]
                 (when-not can-edit
                   (ex/raise :type :validation
                             :code :not-authorized))))))

;; --- Mutation: Create Project

(declare create-project)

(s/def ::create-project
  (s/keys :req-un [::user ::name]
          :opt-un [::id]))

(sm/defmutation ::create-project
  [params]
  (db/with-atomic [conn db/pool]
    (create-project conn params)))

(defn create-project
  [conn {:keys [id user name] :as params}]
  (let [id (or id (uuid/next))
        sql "insert into projects (id, user_id, name)
             values ($1, $2, $3) returning *"]
    (db/query-one conn [sql id user name])))

;; --- Mutation: Update Project

(declare update-project)

(s/def ::update-project
  (s/keys :req-un [::user ::name ::id]))

(sm/defmutation ::update-project
  [{:keys [id user] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn user id)
    (update-project conn params)))

(defn update-project
  [conn {:keys [id name user] :as params}]
  (let [sql "update projects
                set name = $3
              where id = $1
                and user_id = $2
                and deleted_at is null
             returning *"]
    (db/query-one conn [sql id user name])))

;; --- Mutation: Delete Project

(declare delete-project)

(s/def ::delete-project
  (s/keys :req-un [::id ::user]))

(sm/defmutation ::delete-project
  [{:keys [id user] :as params}]
  (db/with-atomic [conn db/pool]
    (check-edition-permissions! conn user id)
    (delete-project conn params)))

(def ^:private sql:delete-project
  "update projects
      set deleted_at = clock_timestamp()
    where id = $1
      and deleted_at is null
   returning id")

(defn delete-project
  [conn {:keys [id user] :as params}]
  (let [sql sql:delete-project]
    (-> (db/query-one conn [sql id])
        (p/then' su/constantly-nil))))

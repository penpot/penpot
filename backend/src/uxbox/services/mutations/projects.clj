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
   [uxbox.util.uuid :as uuid]))

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::token ::us/string)
(s/def ::user ::us/uuid)

;; --- Mutation: Create Project

(declare create-project)

(s/def ::create-project
  (s/keys :req-un [::user ::name]
          :opt-un [::id]))

(sm/defmutation ::create-project
  [{:keys [id user name] :as params}]
  (let [id (or id (uuid/next))
        sql "insert into projects (id, user_id, name)
             values ($1, $2, $3) returning *"]
    (db/query-one db/pool [sql id user name])))

(defn create-project
  [conn {:keys [id user name] :as params}]
  (let [id (or id (uuid/next))
        sql "insert into projects (id, user_id, name)
             values ($1, $2, $3) returning *"]
    (db/query-one conn [sql id user name])))

;; --- Mutation: Update Project

(s/def ::update-project
  (s/keys :req-un [::user ::name ::id]))

(sm/defmutation ::update-project
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

(sm/defmutation ::delete-project
  [{:keys [id user] :as params}]
  (let [sql "update projects
                set deleted_at = clock_timestamp()
              where id = $1
                and user_id = $2
                and deleted_at is null
             returning id"]
    (-> (db/query-one db/pool [sql id user])
        (p/then' su/raise-not-found-if-nil)
        (p/then' su/constantly-nil))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.project-files
  (:require
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

(declare decode-row)

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::user ::us/uuid)

(su/defstr sql:generic-project-files
  "select distinct on (pf.id, pf.created_at)
          pf.*,
          p.name as project_name,
          array_agg(pp.id) over pages_w as pages,
          first_value(pp.data) over pages_w as data
     from project_files as pf
    inner join projects as p on (pf.project_id = p.id)
    inner join project_users as pu on (p.id = pu.project_id)
     left join project_pages as pp on (pf.id = pp.file_id)
    where pu.user_id = $1
      and pu.can_edit = true
      and pf.deleted_at is null
      and pp.deleted_at is null
   window pages_w as (partition by pf.id order by pp.created_at
                      range BETWEEN UNBOUNDED PRECEDING
                                AND UNBOUNDED FOLLOWING)")

;; --- Query: Project Files

(declare retrieve-recent-files)
(declare retrieve-project-files)

(s/def ::project-files
  (s/keys :req-un [::user]
          :opt-un [::project-id]))

(sq/defquery ::project-files
  [{:keys [project-id] :as params}]
  (if (nil? project-id)
    (retrieve-recent-files db/pool params)
    (retrieve-project-files db/pool params)))

(su/defstr sql:project-files
  "with files as (~{sql:generic-project-files})
   select * from files where project_id = $2
    order by created_at asc")

(su/defstr sql:recent-files
  "with files as (~{sql:generic-project-files})
   select * from files
    order by modified_at desc
    limit $2")

(defn retrieve-project-files
  [conn {:keys [user project-id]}]
  (-> (db/query conn [sql:project-files user project-id])
      (p/then' (partial mapv decode-row))))

(defn retrieve-recent-files
  [conn {:keys [user]}]
  (-> (db/query conn [sql:recent-files user 20])
      (p/then' (partial mapv decode-row))))


;; --- Query: Project File (By ID)

(su/defstr sql:project-file
  "with files as (~{sql:generic-project-files})
   select * from files where id = $2")

(s/def ::project-file
  (s/keys :req-un [::user ::id]))

(sq/defquery ::project-file
  [{:keys [user id] :as params}]
  (-> (db/query-one db/pool [sql:project-file user id])
      (p/then' decode-row)))


;; --- Query: Users of the File

(su/defstr sql:file-users
  "select u.id, u.fullname, u.photo
     from users as u
     join project_file_users as pfu on (pfu.user_id = u.id)
    where pfu.file_id = $1
   union all
   select u.id, u.fullname, u.photo
     from users as u
     join project_users as pu on (pu.user_id = u.id)
    where pu.project_id = $2")

(declare retrieve-minimal-file)

(su/defstr sql:minimal-file
  "with files as (~{sql:generic-project-files})
   select id, project_id from files where id = $2")

(s/def ::project-file-users
  (s/keys :req-un [::user ::file-id]))

(sq/defquery ::project-file-users
  [{:keys [user file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (-> (retrieve-minimal-file conn user file-id)
        (p/then (fn [{:keys [id project-id]}]
                  (db/query conn [sql:file-users id project-id]))))))

(defn- retrieve-minimal-file
  [conn user-id file-id]
  (-> (db/query-one conn [sql:minimal-file user-id file-id])
      (p/then' su/raise-not-found-if-nil)))

;; --- Helpers

(defn decode-row
  [{:keys [metadata pages data] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data))
      pages (assoc :pages (vec (remove nil? pages)))
      metadata (assoc :metadata (blob/decode metadata)))))

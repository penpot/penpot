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
(s/def ::user ::us/uuid)

(su/defsql sql:generic-project-files
  "select pf.*,
          array_agg(pp.id) as pages
     from project_files as pf
    inner join projects as p on (pf.project_id = p.id)
    inner join project_users as pu on (p.id = pu.project_id)
     left join project_pages as pp on (pf.id = pp.file_id)
    where pu.user_id = $1
      and pu.can_edit = true
    group by pf.id")

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

(def ^:private sql:project-files
  (str "with files as (" sql:generic-project-files ")"
       " select * from files where project_id = $2"
       " order by created_at asc"))

(defn retrieve-project-files
  [conn {:keys [user project-id]}]
  (-> (db/query conn [sql:project-files user project-id])
      (p/then' (partial mapv decode-row))))

(su/defsql sql:recent-files
  "with files as (~{sql:generic-project-files})
   select * from files
    order by modified_at desc
    limit $2")

(defn retrieve-recent-files
  [conn {:keys [user]}]
  (-> (db/query conn [sql:recent-files user 20])
      (p/then' (partial mapv decode-row))))

;; --- Query: Project File (By ID)

(def ^:private sql:project-file
  (str "with files as (" sql:generic-project-files ")"
       " select * from files where id = $2"))

(s/def ::project-file
  (s/keys :req-un [::user ::id]))

(sq/defquery ::project-file
  [{:keys [user id] :as params}]
  (-> (db/query-one db/pool [sql:project-file user id])
      (p/then' decode-row)))

;; --- Helpers

(defn decode-row
  [{:keys [metadata pages] :as row}]
  (when row
    (cond-> row
      pages (assoc :pages (vec (remove nil? pages)))
      metadata (assoc :metadata (blob/decode metadata)))))

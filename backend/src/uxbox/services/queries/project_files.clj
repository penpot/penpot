;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.project-files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.services.queries :as sq]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

(declare decode-row)

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::user ::us/uuid)

(def ^:private sql:generic-project-files
  "select pf.*,
          array_agg(pp.id) as pages
     from project_files as pf
    inner join projects as p on (pf.project_id = p.id)
    inner join project_users as pu on (p.id = pu.project_id)
     left join project_pages as pp on (pf.id = pp.file_id)
    where pu.user_id = $1
      and pu.can_edit = true
    group by pf.id
    order by pf.created_at asc")

;; --- Query: Project Files

(def ^:private sql:project-files
  (str "with files as (" sql:generic-project-files ")"
       " select * from files where project_id = $2"))

(s/def ::project-files
  (s/keys :req-un [::user ::project-id]))

(sq/defquery ::project-files
  [{:keys [user project-id] :as params}]
  (-> (db/query db/pool [sql:project-files user project-id])
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

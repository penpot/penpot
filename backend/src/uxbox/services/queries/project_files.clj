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

;; --- Query: Project Files

(def ^:private sql:project-files
  "select pf.*,
          array_agg(pp.id) as pages
     from project_files as pf
    inner join projects as p on (pf.project_id = p.id)
    inner join project_users as pu on (p.id = pu.project_id)
     left join project_pages as pp on (pf.id = pp.file_id)
    where pu.user_id = $1
      and pu.project_id = $2
      and pu.can_edit = true
    group by pf.id
    order by pf.created_at asc;")

(s/def ::project-files
  (s/keys :req-un [::user ::project-id]))

(sq/defquery ::project-files
  [{:keys [user project-id] :as params}]
  (-> (db/query db/pool [sql:project-files user project-id])
      (p/then' (partial mapv decode-row))))

;; --- Helpers

(defn decode-row
  [{:keys [metadata pages] :as row}]
  (when row
    (cond-> row
      pages (assoc :pages (vec (remove nil? pages)))
      metadata (assoc :metadata (blob/decode metadata)))))

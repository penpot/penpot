;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.recent-files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.common.spec :as us]
   [uxbox.services.queries :as sq]
   [uxbox.services.queries.projects :refer [retrieve-projects]]
   [uxbox.services.queries.files :refer [decode-row]]))

(def ^:private sql:project-files-recent
  "select distinct
          f.*,
          array_agg(pg.id) over pages_w as pages,
          first_value(pg.data) over pages_w as data
     from file as f
    inner join file_profile_rel as fp_r on (fp_r.file_id = f.id)
     left join page as pg on (f.id = pg.file_id)
    where fp_r.profile_id = $1
      and f.project_id = $2
      and f.deleted_at is null
      and pg.deleted_at is null
      and (fp_r.is_admin = true or
           fp_r.is_owner = true or
           fp_r.can_edit = true)
   window pages_w as (partition by f.id order by pg.ordering
                      range between unbounded preceding
                                and unbounded following)
    order by f.modified_at desc
    limit 5")

(defn recent-by-project [profile-id project]
  (let [project-id (:id project)]
    (-> (db/query db/pool [sql:project-files-recent profile-id project-id])
        (p/then (partial mapv decode-row)))))

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::recent-files
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::recent-files
  [{:keys [profile-id team-id]}]
  (-> (retrieve-projects db/pool profile-id team-id)
      ;; Retrieve for each proyect the 5 more recent files
      (p/then #(p/all (map (partial recent-by-project profile-id) %)))
      ;; Change the structure so it's a map with project-id as keys
      (p/then #(->> % (flatten) (group-by :project-id)))))

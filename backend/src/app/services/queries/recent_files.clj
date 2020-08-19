;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.services.queries.recent-files
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [app.db :as db]
   [app.common.spec :as us]
   [app.services.queries :as sq]
   [app.services.queries.teams :as teams]
   [app.services.queries.projects :as projects :refer [retrieve-projects]]
   [app.services.queries.files :refer [decode-row]]))

(def sql:project-recent-files
  "select distinct
          f.*,
          array_agg(pg.id) over pages_w as pages,
          first_value(pg.data) over pages_w as data
     from file as f
     left join page as pg on (f.id = pg.file_id)
    where f.project_id = ?
      and f.deleted_at is null
      and pg.deleted_at is null
   window pages_w as (partition by f.id order by pg.ordering
                      range between unbounded preceding
                                and unbounded following)
    order by f.modified_at desc
    limit 5")

(defn recent-by-project
  [conn profile-id project]
  (let [project-id (:id project)]
    (projects/check-edition-permissions! conn profile-id project)
    (->> (db/exec! conn [sql:project-recent-files project-id])
         (map decode-row))))

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::recent-files
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::recent-files
  [{:keys [profile-id team-id]}]
  (with-open [conn (db/open)]
    (teams/check-read-permissions! conn profile-id team-id)
    (->> (retrieve-projects conn team-id)
         ;; Retrieve for each proyect the 5 more recent files
         (map (partial recent-by-project conn profile-id))
         ;; Change the structure so it's a map with project-id as keys
         (flatten)
         (group-by :project-id))))

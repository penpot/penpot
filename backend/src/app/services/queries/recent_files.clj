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
   [app.services.queries.files :refer [decode-row-xf]]))

(def sql:recent-files
  "with recent_files as (
     select f.*, row_number() over w as row_num
       from file as f
       join project as p on (p.id = f.project_id)
      where p.team_id = ?
        and p.deleted_at is null
     window w as (partition by f.project_id order by f.modified_at desc)
      order by f.modified_at desc
   )
   select * from recent_files where row_num <= 6;")

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::recent-files
  (s/keys :req-un [::profile-id ::team-id]))

(sq/defquery ::recent-files
  [{:keys [profile-id team-id]}]
  (with-open [conn (db/open)]
    (teams/check-read-permissions! conn profile-id team-id)
    (let [files (db/exec! conn [sql:recent-files team-id])]
      (into [] decode-row-xf files))))



;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.rpc.queries.recent-files
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.queries.files :refer [decode-row-xf]]
   [app.rpc.queries.teams :as teams]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(def sql:recent-files
  "with recent_files as (
     select f.*, row_number() over w as row_num
       from file as f
       join project as p on (p.id = f.project_id)
      where p.team_id = ?
        and p.deleted_at is null
        and f.deleted_at is null
     window w as (partition by f.project_id order by f.modified_at desc)
      order by f.modified_at desc
   )
   select * from recent_files where row_num <= 6;")

(s/def ::team-id ::us/uuid)
(s/def ::profile-id ::us/uuid)

(s/def ::recent-files
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::recent-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (let [files (db/exec! conn [sql:recent-files team-id])]
      (into [] decode-row-xf files))))



;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.queries.files
  (:require
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc.permissions :as perms]
   [app.rpc.queries.projects :as projects]
   [app.rpc.queries.teams :as teams]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(declare decode-row)
(declare decode-row-xf)

;; --- Helpers & Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::project-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::search-term ::us/string)


;; --- Query: File Permissions

(def ^:private sql:file-permissions
  "select fpr.is_owner,
          fpr.is_admin,
          fpr.can_edit
     from file_profile_rel as fpr
    where fpr.file_id = ?
      and fpr.profile_id = ?
   union all
   select tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit
     from team_profile_rel as tpr
    inner join project as p on (p.team_id = tpr.team_id)
    inner join file as f on (p.id = f.project_id)
    where f.id = ?
      and tpr.profile_id = ?
   union all
   select ppr.is_owner,
          ppr.is_admin,
          ppr.can_edit
     from project_profile_rel as ppr
    inner join file as f on (f.project_id = ppr.project_id)
    where f.id = ?
      and ppr.profile_id = ?")

(defn- retrieve-file-permissions
  [conn profile-id file-id]
  (db/exec! conn [sql:file-permissions
                  file-id profile-id
                  file-id profile-id
                  file-id profile-id]))

(def check-edition-permissions!
  (perms/make-edition-check-fn retrieve-file-permissions))

(def check-read-permissions!
  (perms/make-read-check-fn retrieve-file-permissions))


;; --- Query: Files search

;; TODO: this query need to a good refactor

(def ^:private sql:search-files
  "with projects as (
     select p.*
       from project as p
      inner join team_profile_rel as tpr on (tpr.team_id = p.team_id)
      where tpr.profile_id = ?
        and p.team_id = ?
        and p.deleted_at is null
        and (tpr.is_admin = true or
             tpr.is_owner = true or
             tpr.can_edit = true)
      union
     select p.*
       from project as p
      inner join project_profile_rel as ppr on (ppr.project_id = p.id)
      where ppr.profile_id = ?
        and p.team_id = ?
        and p.deleted_at is null
        and (ppr.is_admin = true or
             ppr.is_owner = true or
             ppr.can_edit = true)
   )
   select distinct
          f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    inner join projects as pr on (f.project_id = pr.id)
    where f.name ilike ('%' || ? || '%')
      and f.deleted_at is null
    order by f.created_at asc")

(s/def ::search-files
  (s/keys :req-un [::profile-id ::team-id ::search-term]))

(sv/defmethod ::search-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id search-term] :as params}]
  (db/exec! pool [sql:search-files
                  profile-id team-id
                  profile-id team-id
                  search-term]))

;; --- Query: Files

;; DEPRECATED: should be removed probably on 1.6.x

(def ^:private sql:files
  "select f.*
     from file as f
    where f.project_id = ?
      and f.deleted_at is null
    order by f.modified_at desc")

(s/def ::project-id ::us/uuid)
(s/def ::files
  (s/keys :req-un [::profile-id ::project-id]))

(sv/defmethod ::files
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (into [] decode-row-xf (db/exec! conn [sql:files project-id]))))


;; --- Query: Project Files

(def ^:private sql:project-files
  "select f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    where f.project_id = ?
      and f.deleted_at is null
    order by f.modified_at desc")

(s/def ::project-files
  (s/keys :req-un [::profile-id ::project-id]))

(sv/defmethod ::project-files
  [{:keys [pool] :as cfg} {:keys [profile-id project-id] :as params}]
  (with-open [conn (db/open pool)]
    (projects/check-read-permissions! conn profile-id project-id)
    (db/exec! conn [sql:project-files project-id])))

;; --- Query: File (By ID)

(defn retrieve-file
  [conn id]
  (-> (db/get-by-id conn :file id)
      (decode-row)
      (pmg/migrate-file)))

(s/def ::file
  (s/keys :req-un [::profile-id ::id]))

(sv/defmethod ::file
  [{:keys [pool] :as cfg} {:keys [profile-id id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id id)
    (retrieve-file conn id)))

(s/def ::page
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::page
  [{:keys [pool] :as cfg} {:keys [profile-id file-id]}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (let [file    (retrieve-file conn file-id)
          page-id (get-in file [:data :pages 0])]
      (get-in file [:data :pages-index page-id]))))

;; --- Query: Shared Library Files

;; DEPRECATED: and will be removed on 1.6.x

(def ^:private sql:shared-files
  "select f.*
     from file as f
    inner join project as p on (p.id = f.project_id)
    where f.is_shared = true
      and f.deleted_at is null
      and p.deleted_at is null
      and p.team_id = ?
    order by f.modified_at desc")

(s/def ::shared-files
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::shared-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (into [] decode-row-xf (db/exec! pool [sql:shared-files team-id])))


;; --- Query: Shared Library Files

(def ^:private sql:team-shared-files
  "select f.id,
          f.project_id,
          f.created_at,
          f.modified_at,
          f.name,
          f.is_shared
     from file as f
    inner join project as p on (p.id = f.project_id)
    where f.is_shared = true
      and f.deleted_at is null
      and p.deleted_at is null
      and p.team_id = ?
    order by f.modified_at desc")

(s/def ::team-shared-files
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-shared-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id] :as params}]
  (db/exec! pool [sql:team-shared-files team-id]))


;; --- Query: File Libraries used by a File

(def ^:private sql:file-libraries
  "select fl.*,
          flr.synced_at as synced_at
     from file as fl
    inner join file_library_rel as flr on (flr.library_file_id = fl.id)
    where flr.file_id = ?
      and fl.deleted_at is null")

(defn retrieve-file-libraries
  [conn is-indirect file-id]
  (let [libraries (->> (db/exec! conn [sql:file-libraries file-id])
                       (map #(assoc % :is-indirect is-indirect))
                       (into #{} decode-row-xf))]
    (reduce #(into %1 (retrieve-file-libraries conn true %2))
            libraries
            (map :id libraries))))

(s/def ::file-libraries
  (s/keys :req-un [::profile-id ::file-id]))

(sv/defmethod ::file-libraries
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (check-edition-permissions! conn profile-id file-id)
    (retrieve-file-libraries conn false file-id)))

;; --- QUERY: team-recent-files

(def sql:team-recent-files
  "with recent_files as (
     select f.id,
            f.project_id,
            f.created_at,
            f.modified_at,
            f.name,
            f.is_shared,
            row_number() over w as row_num
       from file as f
       join project as p on (p.id = f.project_id)
      where p.team_id = ?
        and p.deleted_at is null
        and f.deleted_at is null
     window w as (partition by f.project_id order by f.modified_at desc)
      order by f.modified_at desc
   )
   select * from recent_files where row_num <= 10;")

(s/def ::team-recent-files
  (s/keys :req-un [::profile-id ::team-id]))

(sv/defmethod ::team-recent-files
  [{:keys [pool] :as cfg} {:keys [profile-id team-id]}]
  (with-open [conn (db/open pool)]
    (teams/check-read-permissions! conn profile-id team-id)
    (db/exec! conn [sql:team-recent-files team-id])))


;; --- Helpers

(defn decode-row
  [{:keys [data changes] :as row}]
  (when row
    (cond-> row
      changes (assoc :changes (blob/decode changes))
      data    (assoc :data (blob/decode data)))))

(def decode-row-xf
  (comp (map decode-row)
        (map pmg/migrate-file)))
